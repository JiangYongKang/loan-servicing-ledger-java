package com.github.highcumontoa.loanservicingledgerjava.service;

import com.github.highcumontoa.loanservicingledgerjava.domain.Allocation;
import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.domain.LoanStatus;
import com.github.highcumontoa.loanservicingledgerjava.domain.Money;
import com.github.highcumontoa.loanservicingledgerjava.domain.PayoffQuote;
import com.github.highcumontoa.loanservicingledgerjava.domain.Payment;
import com.github.highcumontoa.loanservicingledgerjava.domain.PaymentStatus;
import com.github.highcumontoa.loanservicingledgerjava.domain.PaymentType;
import com.github.highcumontoa.loanservicingledgerjava.domain.PenaltyCharge;
import com.github.highcumontoa.loanservicingledgerjava.error.ErrorCode;
import com.github.highcumontoa.loanservicingledgerjava.error.LedgerException;
import com.github.highcumontoa.loanservicingledgerjava.store.InMemoryLoanStore;
import com.github.highcumontoa.loanservicingledgerjava.store.InMemoryLoanStore.CommitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 账本核心服务。全部写操作在 {@link InMemoryLoanStore} 的贷款级锁内执行，
 * 并发还款被串行化，结果与逐笔串行执行完全一致。
 *
 * <p>资金分配顺序（固定、可解释）：
 * 先按期次先后处理，每期内部 <b>罚息 &rarr; 利息 &rarr; 本金</b>。
 *
 * <p>逾期/罚息口径（基于显式传入的结算日期 valueDate/settlementDate，不依赖系统时钟）：
 * 期次到期日 + graceDays 之后仍未结清即逾期；罚息自 (dueDate + graceDays + 1) 起算，
 * 基数 = 该期未偿本金 + 未偿利息，日罚息率取发放时快照；按日增量计提、区间不重复。
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final InMemoryLoanStore store;
    private final AmortizationService amortizationService;
    private final LedgerProperties props;

    public LedgerService(InMemoryLoanStore store, AmortizationService amortizationService,
                         LedgerProperties props) {
        this.store = store;
        this.amortizationService = amortizationService;
        this.props = props;
    }

    // ---------------- 发放 ----------------

    public Loan disburse(String loanId, Money principal, BigDecimal annualRate, int termMonths,
                         LocalDate originationDate, LocalDate firstDueDate,
                         Integer graceDays, BigDecimal dailyPenaltyRate) {
        if (loanId == null || loanId.isBlank()) {
            throw new LedgerException(ErrorCode.INVALID_PARAMETER, "loanId 不能为空");
        }
        int grace = graceDays != null ? graceDays : props.defaultGraceDays();
        BigDecimal rate = dailyPenaltyRate != null ? dailyPenaltyRate : props.defaultDailyPenaltyRate();
        if (grace < 0) {
            throw new LedgerException(ErrorCode.INVALID_PARAMETER, "宽限期天数不能为负");
        }
        if (rate.signum() < 0) {
            throw new LedgerException(ErrorCode.INVALID_PARAMETER, "日罚息率不能为负");
        }
        if (originationDate == null) {
            throw new LedgerException(ErrorCode.INVALID_PARAMETER, "发放日期不能为空");
        }
        LocalDate first = firstDueDate != null ? firstDueDate : originationDate.plusMonths(1);
        if (first.isBefore(originationDate)) {
            throw new LedgerException(ErrorCode.INVALID_PARAMETER, "首期到期日不能早于发放日");
        }

        List<Installment> schedule =
                amortizationService.generateSchedule(principal, annualRate, termMonths, first);
        Loan loan = new Loan(loanId, principal, annualRate, termMonths,
                originationDate, first, grace, rate, schedule);
        if (store.putIfAbsent(loan) != null) {
            throw new LedgerException(ErrorCode.LOAN_ALREADY_EXISTS, "贷款已存在: " + loanId);
        }
        log.info("发放贷款 input: loanId={}, principal={}, annualRate={}, termMonths={}, "
                        + "origination={}, firstDue={}, graceDays={}, dailyPenaltyRate={}",
                loanId, principal, annualRate, termMonths, originationDate, first, grace, rate);
        schedule.forEach(i -> log.info("摊销计划 loanId={} period={} due={} principal={} interest={}",
                loanId, i.getPeriodNo(), i.getDueDate(), i.getPrincipalDue(), i.getInterestDue()));
        return loan;
    }

    // ---------------- 还款 ----------------

    public PaymentResult pay(String loanId, String paymentId, String idempotencyKey,
                             Money amount, LocalDate valueDate, PaymentType type) {
        if (paymentId == null || paymentId.isBlank()) {
            throw new LedgerException(ErrorCode.INVALID_PARAMETER, "paymentId 不能为空");
        }
        if (amount == null || !amount.isPositive()) {
            throw new LedgerException(ErrorCode.PAYMENT_AMOUNT_NON_POSITIVE,
                    "还款金额必须为正数: " + amount);
        }
        if (valueDate == null) {
            throw new LedgerException(ErrorCode.INVALID_PARAMETER, "结算日期 valueDate 不能为空");
        }
        PaymentType payType = type != null ? type : PaymentType.NORMAL;
        String key = canonicalKey(idempotencyKey, paymentId);

        // 幂等快查（只读）：命中则判定重放或冲突，避免无谓抢锁
        Payment existing = store.findPaymentByKey(key);
        if (existing != null) {
            return reconcile(existing, loanId, paymentId, amount, payType);
        }

        return store.withPaymentLock(key, loanId, () -> {
            // 1) 锁内复查幂等键
            Payment inside = store.findPaymentByKey(key);
            if (inside != null) {
                return reconcile(inside, loanId, paymentId, amount, payType);
            }
            Loan loan = requireLoan(loanId);

            // 2) 索引占用检查（只读，先于任何账户变更）：paymentId 被其他键占用则拒绝
            Payment idOwner = store.findPaymentByPaymentId(paymentId);
            if (idOwner != null && !idOwner.getIdempotencyKey().equals(key)) {
                throw new LedgerException(ErrorCode.PAYMENT_CONFLICT,
                        "支付单号已被其他请求使用，无法安全合并: paymentId=" + paymentId
                                + ", ownerKey=" + idOwner.getIdempotencyKey());
            }

            // 3) 业务状态校验（只读）
            if (valueDate.isBefore(loan.getOriginationDate())) {
                throw new LedgerException(ErrorCode.SETTLEMENT_BEFORE_ORIGINATION,
                        "结算日期不能早于发放日: " + valueDate);
            }
            if (loan.getStatus() == LoanStatus.PAID_OFF) {
                throw new LedgerException(ErrorCode.LOAN_ALREADY_PAID_OFF,
                        "贷款已结清，不能再次还款: " + loanId);
            }

            // 4) 金额校验与分配规划（只读，不产生任何副作用；逾期报价内部基于当前快照重算）
            Allocation allocation = (payType == PaymentType.EARLY_PAYOFF)
                    ? planEarlyPayoff(loan, amount, valueDate)
                    : planNormalPayment(loan, amount, valueDate);

            // 5) 索引登记先落（账户尚未变更），保证同笔不重复入账、paymentId 不跨键复用
            Payment payment = new Payment(paymentId, loanId, key, amount, valueDate, payType,
                    PaymentStatus.ACCEPTED, java.time.LocalDateTime.now());
            payment.attachAllocation(allocation);
            CommitResult committed = store.commitPayment(key, payment);
            if (committed == CommitResult.PAYMENT_ID_TAKEN) {
                throw new LedgerException(ErrorCode.PAYMENT_CONFLICT,
                        "支付单号已被其他请求使用，无法安全合并: paymentId=" + paymentId);
            }
            if (committed == CommitResult.DUPLICATE_KEY) {
                throw new LedgerException(ErrorCode.PAYMENT_DUPLICATED,
                        "还款键已被并发提交: key=" + key);
            }

            // 6) 登记成功后：推进逾期/罚息（幂等增量），再按规划应用分配
            List<String> basis = accrue(loan, valueDate);
            applyPlannedAllocation(loan, allocation, payType);
            loan.addPayment(payment);

            if (loan.totalOutstanding().isZero()) {
                loan.markPaidOff();
            }
            verifyConservation(loan);

            log.info("还款入账 input: loanId={}, paymentId={}, key={}, amount={}, valueDate={}, type={}",
                    loanId, paymentId, key, amount, valueDate, payType);
            basis.forEach(b -> log.info("逾期判定依据 loanId={} {}", loanId, b));
            allocation.entries().forEach(e -> log.info(
                    "资金分配 loanId={} period={} penalty={} interest={} principal={}",
                    loanId, e.periodNo(), e.penaltyPaid(), e.interestPaid(), e.principalPaid()));
            log.info("还款结果 loanId={} totalPaid={} loanStatus={} remaining={}",
                    loanId, allocation.totalPaid(), loan.getStatus(), loan.totalOutstanding());
            return new PaymentResult(payment, allocation, false);
        });
    }

    /** 幂等命中：内容一致则重放，内容不一致则拒绝。 */
    private PaymentResult reconcile(Payment existing, String loanId, String paymentId,
                                    Money amount, PaymentType type) {
        boolean same = existing.getLoanId().equals(loanId)
                && existing.getPaymentId().equals(paymentId)
                && existing.getAmount().equals(amount)
                && existing.getType() == type;
        if (!same) {
            throw new LedgerException(ErrorCode.PAYMENT_CONFLICT,
                    "幂等键/支付单号已存在但请求内容不一致，已拒绝: existingPaymentId="
                            + existing.getPaymentId() + ", existingLoan=" + existing.getLoanId()
                            + ", existingAmount=" + existing.getAmount()
                            + ", existingType=" + existing.getType());
        }
        log.info("还款幂等重放 loanId={} paymentId={} amount={}（不重复入账）",
                loanId, paymentId, amount);
        return new PaymentResult(existing, existing.getAllocation(), true);
    }

    // ---------------- 正常还款（允许部分还款，拒绝超额）----------------

    /**
     * 只做金额校验与分配规划，不改动账户。
     * 规划必须反映“登记后将罚息计提到 valueDate”之后的应还口径，
     * 因此这里用 {@link #previewAccrue} 计算每期待计提罚息，临时并入视图做瀑布规划。
     */
    private Allocation planNormalPayment(Loan loan, Money requested, LocalDate valueDate) {
        List<Money> newPenalty = previewAccrue(loan, valueDate);
        Money totalDue = loan.totalOutstanding();
        for (Money p : newPenalty) {
            totalDue = totalDue.plus(p);
        }
        if (requested.gt(totalDue)) {
            throw new LedgerException(ErrorCode.PAYMENT_EXCEEDS_DUE,
                    "还款金额超过当前应还总额: paid=" + requested + ", due(含截至" + valueDate
                            + "计提罚息)=" + totalDue
                            + "；提前结清请使用提前结清入口并先获取报价");
        }
        List<Allocation.AllocationEntry> entries = new ArrayList<>();
        Money available = requested;
        for (int idx = 0; idx < loan.getInstallments().size(); idx++) {
            if (available.isZero()) {
                break;
            }
            Installment ins = loan.getInstallments().get(idx);
            Money projectedPenalty = ins.outstandingPenalty().plus(newPenalty.get(idx));
            if (projectedPenalty.isZero() && ins.outstandingInterest().isZero()
                    && ins.outstandingPrincipal().isZero()) {
                continue;
            }
            Money penaltyPay = minMoney(available, projectedPenalty);
            available = available.minus(penaltyPay);
            Money interestPay = minMoney(available, ins.outstandingInterest());
            available = available.minus(interestPay);
            Money principalPay = minMoney(available, ins.outstandingPrincipal());
            available = available.minus(principalPay);
            if (penaltyPay.isPositive() || interestPay.isPositive() || principalPay.isPositive()) {
                entries.add(new Allocation.AllocationEntry(
                        ins.getPeriodNo(), penaltyPay, interestPay, principalPay));
            }
        }
        if (!available.isZero()) {
            throw new LedgerException(ErrorCode.INTERNAL_CONSISTENCY, "还款后仍有未分配金额: " + available);
        }
        return new Allocation(requested, entries);
    }

    // ---------------- 提前结清 ----------------

    /**
     * 校验报价并构造分配计划（含未到期利息减免额），不改动账户。
     * 罚息取“截至结算日将计提”的口径（{@link #previewAccrue}），
     * 保证规划与登记后真实计提完全一致。
     */
    private Allocation planEarlyPayoff(Loan loan, Money requested, LocalDate settlementDate) {
        List<Money> newPenalty = previewAccrue(loan, settlementDate);
        List<Allocation.AllocationEntry> entries = new ArrayList<>();
        Money totalPayoff = Money.ZERO;
        for (int idx = 0; idx < loan.getInstallments().size(); idx++) {
            Installment ins = loan.getInstallments().get(idx);
            if (ins.isPaid()) {
                continue;
            }
            Money penalty = ins.outstandingPenalty().plus(newPenalty.get(idx));
            Money interestToPay = ins.outstandingInterest();
            Money principal = ins.outstandingPrincipal();
            boolean matured = !ins.getDueDate().isAfter(settlementDate);
            if (!matured) {
                // 未到期利息免除（在 apply 阶段 waive）
                interestToPay = Money.ZERO;
            }
            entries.add(new Allocation.AllocationEntry(
                    ins.getPeriodNo(), penalty, interestToPay, principal));
            totalPayoff = totalPayoff.plus(penalty).plus(interestToPay).plus(principal);
        }
        if (!requested.equals(totalPayoff)) {
            throw new LedgerException(ErrorCode.PAYOFF_AMOUNT_MISMATCH,
                    "提前结清金额与结算日报价不一致: paid=" + requested + ", quote="
                            + totalPayoff + " @ " + settlementDate
                            + "（口径=截至结算日罚息+到期未付利息+剩余本金；未到期利息免除）"
                            + "；请按报价金额提交");
        }
        return new Allocation(requested, entries);
    }

    /** 索引登记成功后应用计划：正常还款按瀑布冲抵，提前结清含利息减免。 */
    private void applyPlannedAllocation(Loan loan, Allocation allocation, PaymentType type) {
        if (type == PaymentType.EARLY_PAYOFF) {
            for (Allocation.AllocationEntry e : allocation.entries()) {
                Installment ins = loan.getInstallments().get(e.periodNo() - 1);
                Money unpaidInterest = ins.outstandingInterest();
                if (e.interestPaid().isZero() && unpaidInterest.isPositive()) {
                    ins.waiveInterest(unpaidInterest);
                }
                ins.applyPayoff(e.penaltyPaid(), e.interestPaid(), e.principalPaid());
            }
            return;
        }
        for (Allocation.AllocationEntry e : allocation.entries()) {
            Installment ins = loan.getInstallments().get(e.periodNo() - 1);
            Money want = e.penaltyPaid().plus(e.interestPaid()).plus(e.principalPaid());
            Allocation.AllocationEntry applied = ins.applyPayment(want);
            if (!applied.equals(e)) {
                throw new LedgerException(ErrorCode.INTERNAL_CONSISTENCY,
                        "应用的资金分配与规划不一致: period=" + e.periodNo());
            }
        }
    }

    private static Money minMoney(Money a, Money b) {
        return a.lte(b) ? a : b;
    }

    // ---------------- 逾期/罚息结算 ----------------

    public AccountSnapshot settle(String loanId, LocalDate settlementDate) {
        Loan loan = requireLoan(loanId);
        if (settlementDate == null) {
            throw new LedgerException(ErrorCode.INVALID_PARAMETER, "结算日期不能为空");
        }
        return store.withLoanLock(loanId, () -> {
            if (settlementDate.isBefore(loan.getOriginationDate())) {
                throw new LedgerException(ErrorCode.SETTLEMENT_BEFORE_ORIGINATION,
                        "结算日期不能早于发放日: " + settlementDate);
            }
            if (loan.getStatus() != LoanStatus.PAID_OFF) {
                accrue(loan, settlementDate);
                verifyConservation(loan);
            }
            return buildSnapshot(loan, settlementDate);
        });
    }

    /**
     * 把罚息计提到 settlementDate（含）。
     *
     * <p>确定口径（可复现、不重复）：
     * <ul>
     *   <li>计息起点 = 到期日 + 宽限期 + 1；</li>
     *   <li>基数 = 计提时点该期“未偿本金 + 未偿利息”；</li>
     *   <li>每次还款入账时本方法先被调用，计提水位随之推进到该还款日 —— 即部分还款
     *       只对“还款前仍欠的基数”计了罚息，还款日后按新基数继续，绝不会对已还部分计罚；</li>
     *   <li>增量计提：[penaltyAccruedThrough+1 .. settlementDate]，同一日期区间绝不重复；</li>
     *   <li>已结清期次/已结清贷款不再计提。</li>
     * </ul>
     */
    private List<String> accrue(Loan loan, LocalDate settlementDate) {
        List<String> basis = new ArrayList<>();
        basis.add("结算日=" + settlementDate + ", 宽限期=" + loan.getGraceDays()
                + "天, 日罚息率=" + loan.getDailyPenaltyRate()
                + "（发放时快照）, 罚息自到期日+宽限期+1起算");
        for (Accrual a : computeAccruals(loan, settlementDate)) {
            Installment ins = loan.getInstallments().get(a.periodNo() - 1);
            if (a.amount().isPositive()) {
                ins.addPenaltyCharge(new PenaltyCharge(a.periodNo(), a.from(), a.to(),
                        a.days(), a.base(), loan.getDailyPenaltyRate(), a.amount()));
                basis.add(String.format(
                        "期次%d逾期: due=%s, 罚息区间[%s..%s]共%d天, 基数(本+息)=%s, 罚息=%s",
                        a.periodNo(), ins.getDueDate(), a.from(), a.to(), a.days(),
                        a.base(), a.amount()));
            } else {
                // 基数为 0（如当期本息已清但水位未推进）：只推水位不产生费用
                ins.advancePenaltyWatermark(a.to());
            }
        }
        return basis;
    }

    /**
     * 只读计算到 settlementDate（含）每一期“尚需新增计提”的罚息，不修改贷款。
     * 与 {@link #accrue} 共用同一纯函数，保证还款规划所见 = 实际计提所得。
     *
     * @return 与期次对齐的新增罚息金额列表（无新增的期次为 0）
     */
    private List<Money> previewAccrue(Loan loan, LocalDate settlementDate) {
        List<Money> result = new ArrayList<>();
        for (Installment ins : loan.getInstallments()) {
            result.add(Money.ZERO);
        }
        for (Accrual a : computeAccruals(loan, settlementDate)) {
            result.set(a.periodNo() - 1, a.amount());
        }
        return result;
    }

    /** 单笔待计提罚息（纯计算结果）。 */
    private record Accrual(int periodNo, LocalDate from, LocalDate to, int days,
                           Money base, Money amount) {
    }

    /**
     * 罚息计提的纯计算（不写状态）：
     * 计息起点 = dueDate + graceDays + 1；区间增量（水位之后到 settlementDate）；
     * 基数 = 未偿本金 + 未偿利息；金额 = 基数 × 日罚息率 × 天数（HALF_UP 到分）。
     */
    private List<Accrual> computeAccruals(Loan loan, LocalDate settlementDate) {
        List<Accrual> list = new ArrayList<>();
        for (Installment ins : loan.getInstallments()) {
            LocalDate penaltyStart = ins.getDueDate().plusDays(loan.getGraceDays() + 1L);
            if (ins.isPaid()) {
                continue;
            }
            LocalDate from = ins.getPenaltyAccruedThrough() != null
                    ? ins.getPenaltyAccruedThrough().plusDays(1)
                    : penaltyStart;
            if (from.isAfter(settlementDate)) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(from, settlementDate) + 1;
            if (days <= 0) {
                continue;
            }
            Money base = ins.outstandingPrincipal().plus(ins.outstandingInterest());
            if (!base.isPositive()) {
                list.add(new Accrual(ins.getPeriodNo(), from, settlementDate, (int) days,
                        base, Money.ZERO));
                continue;
            }
            BigDecimal factor = loan.getDailyPenaltyRate()
                    .multiply(BigDecimal.valueOf(days))
                    .setScale(10, RoundingMode.HALF_UP);
            Money amount = base.multiplyScale4(factor);
            list.add(new Accrual(ins.getPeriodNo(), from, settlementDate, (int) days,
                    base, amount.isPositive() ? amount : Money.ZERO));
        }
        return list;
    }

    // ---------------- 提前结清报价 ----------------

    public PayoffQuote quotePayoff(String loanId, LocalDate settlementDate) {
        Loan loan = requireLoan(loanId);
        if (settlementDate == null) {
            throw new LedgerException(ErrorCode.INVALID_PARAMETER, "结算日期不能为空");
        }
        return store.withLoanLock(loanId, () -> {
            if (settlementDate.isBefore(loan.getOriginationDate())) {
                throw new LedgerException(ErrorCode.SETTLEMENT_BEFORE_ORIGINATION,
                        "结算日期不能早于发放日: " + settlementDate);
            }
            if (loan.getStatus() == LoanStatus.PAID_OFF) {
                throw new LedgerException(ErrorCode.LOAN_ALREADY_PAID_OFF,
                        "贷款已结清，无需提前结清: " + loanId);
            }
            accrue(loan, settlementDate);
            return buildQuote(loan, settlementDate);
        });
    }

    private PayoffQuote buildQuote(Loan loan, LocalDate settlementDate) {
        Money penalty = Money.ZERO;
        Money interest = Money.ZERO;
        Money principal = Money.ZERO;
        List<String> basis = new ArrayList<>();
        for (Installment ins : loan.getInstallments()) {
            if (ins.isPaid()) {
                continue;
            }
            penalty = penalty.plus(ins.outstandingPenalty());
            principal = principal.plus(ins.outstandingPrincipal());
            boolean matured = !ins.getDueDate().isAfter(settlementDate);
            if (matured) {
                interest = interest.plus(ins.outstandingInterest());
            }
        }
        Money total = penalty.plus(interest).plus(principal);
        basis.add("提前结清口径@结算日" + settlementDate + "：已计未付罚息 + 已到期未付利息 + 全部剩余本金；未到期利息免除");
        basis.add("罚息=" + penalty + ", 到期利息=" + interest + ", 剩余本金=" + principal
                + ", 应付合计=" + total);
        log.info("提前结清报价 loanId={} date={} penalty={} interest={} principal={} total={}",
                loan.getLoanId(), settlementDate, penalty, interest, principal, total);
        return new PayoffQuote(loan.getLoanId(), settlementDate, penalty, interest, principal,
                total, basis);
    }

    // ---------------- 查询 ----------------

    public Loan getLoan(String loanId) {
        return requireLoan(loanId);
    }

    public List<Payment> payments(String loanId) {
        return requireLoan(loanId).getPayments();
    }

    // ---------------- 快照 ----------------

    private AccountSnapshot buildSnapshot(Loan loan, LocalDate settlementDate) {
        List<AccountSnapshot.InstallmentView> views = new ArrayList<>();
        List<String> basis = new ArrayList<>();
        basis.add("逾期判定: 结算日 > 到期日+宽限期(" + loan.getGraceDays() + "天) 且期次未结清");
        for (Installment ins : loan.getInstallments()) {
            LocalDate deadline = ins.getDueDate().plusDays(loan.getGraceDays());
            boolean overdue = !ins.isPaid() && settlementDate.isAfter(deadline);
            int overdueDays = overdue ? (int) ChronoUnit.DAYS.between(deadline, settlementDate) : 0;
            views.add(new AccountSnapshot.InstallmentView(
                    ins.getPeriodNo(), ins.getDueDate(), ins.getStatus().name(), overdue,
                    overdueDays, ins.getPrincipalDue(), ins.getInterestDue(), ins.getPenaltyDue(),
                    ins.getPrincipalPaid(), ins.getInterestPaid(), ins.getPenaltyPaid(),
                    ins.outstanding()));
        }
        return new AccountSnapshot(loan.getLoanId(), loan.getStatus(), settlementDate,
                loan.outstandingPrincipal(), loan.outstandingInterest(), loan.outstandingPenalty(),
                loan.totalOutstanding(), views, basis);
    }

    // ---------------- 守恒校验 ----------------

    /**
     * 任一时点必须成立：
     * 1) 现金守恒：还款流水金额之和 = 各期已还(本+息+罚息)之和；
     * 2) 应收守恒：历期应收(本+息+罚息) = 已还 + 当前未偿 + 已减免；
     * 3) 任一期/任一项未偿不为负（Installment 不变量保证）。
     */
    private void verifyConservation(Loan loan) {
        Money cashPaid = loan.totalPaid();
        Money allocated = Money.ZERO;
        Money dueEver = Money.ZERO;
        Money outstanding = Money.ZERO;
        Money waived = Money.ZERO;
        for (Installment ins : loan.getInstallments()) {
            allocated = allocated.plus(ins.getPrincipalPaid())
                    .plus(ins.getInterestPaid()).plus(ins.getPenaltyPaid());
            dueEver = dueEver.plus(ins.getPrincipalDue())
                    .plus(ins.getInterestDue()).plus(ins.getPenaltyDue());
            outstanding = outstanding.plus(ins.outstanding());
            waived = waived.plus(ins.getInterestWaived());
            if (ins.outstandingPrincipal().value().signum() < 0
                    || ins.outstandingInterest().value().signum() < 0
                    || ins.outstandingPenalty().value().signum() < 0) {
                throw new LedgerException(ErrorCode.INTERNAL_CONSISTENCY,
                        "出现负数余额: period=" + ins.getPeriodNo());
            }
        }
        if (!cashPaid.equals(allocated)) {
            throw new LedgerException(ErrorCode.INTERNAL_CONSISTENCY,
                    "现金守恒被破坏: paid=" + cashPaid + ", allocated=" + allocated);
        }
        if (!dueEver.equals(allocated.plus(outstanding).plus(waived))) {
            throw new LedgerException(ErrorCode.INTERNAL_CONSISTENCY,
                    "应收守恒被破坏: dueEver=" + dueEver + ", paid=" + allocated
                            + ", outstanding=" + outstanding + ", waived=" + waived);
        }
    }

    /** 幂等重放时返回首次的原始分配结果，见 {@link Payment#getAllocation()}。 */

    private Loan requireLoan(String loanId) {
        Loan loan = store.get(loanId);
        if (loan == null) {
            throw new LedgerException(ErrorCode.LOAN_NOT_FOUND, "贷款不存在: " + loanId);
        }
        return loan;
    }

    private static String canonicalKey(String idempotencyKey, String paymentId) {
        return (idempotencyKey != null && !idempotencyKey.isBlank())
                ? "idem:" + idempotencyKey : "pid:" + paymentId;
    }
}
