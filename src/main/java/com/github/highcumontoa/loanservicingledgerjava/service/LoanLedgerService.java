package com.github.highcumontoa.loanservicingledgerjava.service;

import com.github.highcumontoa.loanservicingledgerjava.config.LedgerProperties;
import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import com.github.highcumontoa.loanservicingledgerjava.domain.InstallmentStatus;
import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.domain.LoanStatus;
import com.github.highcumontoa.loanservicingledgerjava.domain.OverdueRules;
import com.github.highcumontoa.loanservicingledgerjava.domain.Payment;
import com.github.highcumontoa.loanservicingledgerjava.domain.PaymentAllocation;
import com.github.highcumontoa.loanservicingledgerjava.domain.ReconciliationReport;
import com.github.highcumontoa.loanservicingledgerjava.error.LedgerErrorCode;
import com.github.highcumontoa.loanservicingledgerjava.error.LedgerException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 本地贷款账本核心服务：发放、还款、逾期/罚息、提前结清。
 *
 * <p>一致性设计：
 * <ul>
 *   <li>每个贷款一把锁，所有写操作在锁内串行执行，因此并发提交的终态
 *       与逐笔串行执行的终态一致。</li>
 *   <li>幂等键唯一标识一笔还款：同键同额重复提交返回首次入账结果；
 *       同键不同额属于无法安全合并的冲突，以 CONCURRENT_CONFLICT 拒绝。</li>
 *   <li>冲抵顺序固定：按期次升序，期次内 费用 -&gt; 罚息 -&gt; 利息 -&gt; 本金。</li>
 *   <li>守恒校验：每次变更后断言 入账总额 == 各期已冲抵合计，且本金余额
 *       == 各期未还本金合计，不出现负数余额。</li>
 * </ul>
 */
@Service
public class LoanLedgerService {

    private final AmortizationCalculator calculator;
    private final LedgerProperties properties;

    private final Map<String, Loan> loans = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    /** loanId -> (idempotencyKey -> Payment)，用于幂等判定。 */
    private final Map<String, Map<String, Payment>> idempotencyIndex = new ConcurrentHashMap<>();

    public LoanLedgerService(AmortizationCalculator calculator, LedgerProperties properties) {
        this.calculator = calculator;
        this.properties = properties;
    }

    /** 发放贷款并生成摊销计划（逾期口径沿用放款当时的全局默认）。 */
    public Loan disburse(String loanId, BigDecimal principal, BigDecimal annualRate,
                         int termMonths, LocalDate disbursementDate, BigDecimal monthlyFee) {
        return disburse(loanId, principal, annualRate, termMonths, disbursementDate,
                monthlyFee, null, null);
    }

    /**
     * 发放贷款并生成摊销计划，可为该笔贷款单独指定逾期口径。
     *
     * <p>gracePeriodDays / penaltyDailyRate 传 null 时取放款当时的全局默认；
     * 无论取值来源，规则在放款时固化到贷款上，之后全局默认的调整不影响本笔贷款。
     */
    public Loan disburse(String loanId, BigDecimal principal, BigDecimal annualRate,
                         int termMonths, LocalDate disbursementDate, BigDecimal monthlyFee,
                         Integer gracePeriodDays, BigDecimal penaltyDailyRate) {
        if (loanId == null || loanId.isBlank()
                || principal == null || principal.signum() <= 0
                || annualRate == null || annualRate.signum() < 0
                || termMonths <= 0 || disbursementDate == null) {
            throw new LedgerException(LedgerErrorCode.INVALID_DISBURSEMENT,
                    "非法放款参数: loanId=" + loanId + ", principal=" + principal
                            + ", annualRate=" + annualRate + ", termMonths=" + termMonths);
        }
        if (gracePeriodDays != null && gracePeriodDays < 0) {
            throw new LedgerException(LedgerErrorCode.INVALID_DISBURSEMENT,
                    "宽限期天数不能为负: " + gracePeriodDays);
        }
        if (penaltyDailyRate != null && penaltyDailyRate.signum() < 0) {
            throw new LedgerException(LedgerErrorCode.INVALID_DISBURSEMENT,
                    "罚息日利率不能为负: " + penaltyDailyRate);
        }
        Object lock = locks.computeIfAbsent(loanId, k -> new Object());
        synchronized (lock) {
            if (loans.containsKey(loanId)) {
                throw new LedgerException(LedgerErrorCode.INVALID_DISBURSEMENT,
                        "贷款已存在: " + loanId);
            }
            BigDecimal fee = monthlyFee != null ? monthlyFee : properties.getDefaultMonthlyFee();
            // 规则固化：单笔指定优先，否则快照放款当时的全局默认
            OverdueRules rules = new OverdueRules(
                    gracePeriodDays != null ? gracePeriodDays : properties.getGracePeriodDays(),
                    penaltyDailyRate != null ? penaltyDailyRate : properties.getPenaltyDailyRate());
            Loan loan = new Loan(loanId, principal.setScale(2, RoundingMode.HALF_UP),
                    annualRate, termMonths, disbursementDate,
                    fee.setScale(2, RoundingMode.HALF_UP), rules);
            loan.getSchedule().addAll(calculator.generateSchedule(
                    loan.getPrincipal(), annualRate, termMonths, disbursementDate, loan.getMonthlyFee()));
            loans.put(loanId, loan);
            idempotencyIndex.put(loanId, new ConcurrentHashMap<>());
            checkInvariants(loan);
            return loan;
        }
    }

    /** 查询贷款。 */
    public Loan getLoan(String loanId) {
        Loan loan = loans.get(loanId);
        if (loan == null) {
            throw new LedgerException(LedgerErrorCode.LOAN_NOT_FOUND, "贷款不存在: " + loanId);
        }
        return loan;
    }

    /**
     * 还款入账（幂等：同一 idempotencyKey 只入账一次，重复提交返回首次结果）。
     * 结算口径：先按 valueDate 推进逾期与罚息，再按固定顺序冲抵。
     */
    public Payment repay(String loanId, String idempotencyKey, BigDecimal amount, LocalDate valueDate) {
        Object lock = lockOf(loanId);
        synchronized (lock) {
            Loan loan = getLoan(loanId);
            Payment replay = checkIdempotency(loan, idempotencyKey, amount);
            if (replay != null) {
                return replay;
            }
            requireActive(loan);
            requirePositiveAmount(amount);
            if (valueDate == null) {
                throw new LedgerException(LedgerErrorCode.INVALID_AMOUNT, "结算日不能为空");
            }
            accrueOverdueLocked(loan, valueDate);

            BigDecimal totalOutstanding = totalOutstanding(loan);
            if (amount.compareTo(totalOutstanding) > 0) {
                throw new LedgerException(LedgerErrorCode.OVERPAYMENT,
                        "超额支付: amount=" + amount + " > outstanding=" + totalOutstanding);
            }
            Payment payment = applyPayment(loan, idempotencyKey, amount, valueDate);
            checkInvariants(loan);
            return payment;
        }
    }

    /**
     * 提前结清：应结清额 = 未偿本金 + 截至结算日已到期未付的(费用+罚息+利息)，
     * 未到期期次的利息与费用豁免。返回结清流水。
     */
    public Payment payoff(String loanId, String idempotencyKey, LocalDate valueDate) {
        Object lock = lockOf(loanId);
        synchronized (lock) {
            Loan loan = getLoan(loanId);
            Payment replay = checkIdempotency(loan, idempotencyKey, null);
            if (replay != null) {
                return replay;
            }
            requireActive(loan);
            if (valueDate == null) {
                throw new LedgerException(LedgerErrorCode.INVALID_AMOUNT, "结算日不能为空");
            }
            accrueOverdueLocked(loan, valueDate);

            BigDecimal dueCharges = BigDecimal.ZERO.setScale(2);
            for (Installment inst : loan.getSchedule()) {
                if (!inst.getDueDate().isAfter(valueDate)) {
                    dueCharges = dueCharges.add(inst.remainingFee())
                            .add(inst.remainingPenalty()).add(inst.remainingInterest());
                }
            }
            BigDecimal payoffAmount = loan.getOutstandingPrincipal().add(dueCharges);

            Payment payment = applyPayoffPayment(loan, idempotencyKey, payoffAmount, valueDate);
            // 结清减免：未到期期次尚未收取的利息与费用逐期逐项记为减免，
            // 记录后各期未偿归零，不再产生新的应还
            for (Installment inst : loan.getSchedule()) {
                if (inst.getDueDate().isAfter(valueDate)) {
                    inst.addInterestWaived(inst.remainingInterest());
                    inst.addFeeWaived(inst.remainingFee());
                }
                if (inst.getStatus() != InstallmentStatus.PAID) {
                    inst.setStatus(InstallmentStatus.PAID);
                }
            }
            loan.setStatus(LoanStatus.SETTLED);
            checkInvariants(loan);
            return payment;
        }
    }

    /** 按结算日口径推进逾期判定与罚息累计（幂等，可重复调用；已结清贷款为无操作）。 */
    public Loan accrueOverdue(String loanId, LocalDate asOfDate) {
        Object lock = lockOf(loanId);
        synchronized (lock) {
            Loan loan = getLoan(loanId);
            if (loan.getStatus() == LoanStatus.SETTLED) {
                return loan; // 已结清不再进入逾期、不产生新的应还
            }
            accrueOverdueLocked(loan, asOfDate);
            checkInvariants(loan);
            return loan;
        }
    }

    /** 全部贷款（只读视图用）。 */
    public List<Loan> listLoans() {
        return List.copyOf(loans.values());
    }

    /**
     * 按结算日导出对账结果：先按 asOfDate 推进逾期/罚息（幂等），再核对两条平衡式。
     *
     * <p>平衡式一（按科目）：历期应收合计 = 已还 + 未偿 + 减免；
     * 平衡式二（现金侧）：实际入账现金合计 = 已冲抵合计。
     * 任何一处不平，都会在 discrepancies 中指明科目与差额。
     */
    public ReconciliationReport reconcile(String loanId, LocalDate asOfDate) {
        if (asOfDate == null) {
            throw new LedgerException(LedgerErrorCode.INVALID_AMOUNT, "结算日不能为空");
        }
        Object lock = lockOf(loanId);
        synchronized (lock) {
            Loan loan = getLoan(loanId);
            if (loan.getStatus() != LoanStatus.SETTLED) {
                accrueOverdueLocked(loan, asOfDate);
            }

            BigDecimal billedPrincipal = BigDecimal.ZERO, billedInterest = BigDecimal.ZERO;
            BigDecimal billedFee = BigDecimal.ZERO, billedPenalty = BigDecimal.ZERO;
            BigDecimal paidPrincipal = BigDecimal.ZERO, paidInterest = BigDecimal.ZERO;
            BigDecimal paidFee = BigDecimal.ZERO, paidPenalty = BigDecimal.ZERO;
            BigDecimal outInterest = BigDecimal.ZERO;
            BigDecimal outFee = BigDecimal.ZERO, outPenalty = BigDecimal.ZERO;
            BigDecimal waivedInterest = BigDecimal.ZERO, waivedFee = BigDecimal.ZERO;
            for (Installment inst : loan.getSchedule()) {
                billedPrincipal = billedPrincipal.add(inst.getPrincipalDue());
                billedInterest = billedInterest.add(inst.getInterestDue());
                billedFee = billedFee.add(inst.getFeeDue());
                billedPenalty = billedPenalty.add(inst.getPenaltyAccrued());
                paidPrincipal = paidPrincipal.add(inst.getPrincipalPaid());
                paidInterest = paidInterest.add(inst.getInterestPaid());
                paidFee = paidFee.add(inst.getFeePaid());
                paidPenalty = paidPenalty.add(inst.getPenaltyPaid());
                outInterest = outInterest.add(inst.remainingInterest());
                outFee = outFee.add(inst.remainingFee());
                outPenalty = outPenalty.add(inst.remainingPenalty());
                waivedInterest = waivedInterest.add(inst.getInterestWaived());
                waivedFee = waivedFee.add(inst.getFeeWaived());
            }
            BigDecimal zero = BigDecimal.ZERO.setScale(2);
            List<ReconciliationReport.SubjectBalance> subjects = List.of(
                    // 本金科目的未偿取账户级余额，与计划明细(应收-已还)交叉核对
                    subject("PRINCIPAL", billedPrincipal, paidPrincipal,
                            loan.getOutstandingPrincipal(), zero),
                    subject("INTEREST", billedInterest, paidInterest, outInterest, waivedInterest),
                    subject("FEE", billedFee, paidFee, outFee, waivedFee),
                    subject("PENALTY", billedPenalty, paidPenalty, outPenalty, zero));

            BigDecimal cashReceived = loan.getPayments().stream()
                    .map(Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalApplied = paidPrincipal.add(paidInterest).add(paidFee).add(paidPenalty);
            ReconciliationReport.CashBalance cash = new ReconciliationReport.CashBalance(
                    cashReceived, totalApplied, cashReceived.subtract(totalApplied));

            List<ReconciliationReport.Discrepancy> discrepancies = new ArrayList<>();
            for (ReconciliationReport.SubjectBalance s : subjects) {
                if (s.difference().signum() != 0) {
                    discrepancies.add(new ReconciliationReport.Discrepancy(
                            s.subject(), s.difference(),
                            "应收(" + s.billed() + ") != 已还(" + s.paid() + ") + 未偿("
                                    + s.outstanding() + ") + 减免(" + s.waived() + ")"));
                }
            }
            if (cash.difference().signum() != 0) {
                discrepancies.add(new ReconciliationReport.Discrepancy(
                        "CASH", cash.difference(),
                        "入账现金(" + cash.cashReceived() + ") != 已冲抵合计(" + cash.totalApplied() + ")"));
            }
            return new ReconciliationReport(loanId, asOfDate, discrepancies.isEmpty(),
                    subjects, cash, List.copyOf(discrepancies));
        }
    }

    private static ReconciliationReport.SubjectBalance subject(
            String name, BigDecimal billed, BigDecimal paid, BigDecimal outstanding, BigDecimal waived) {
        return new ReconciliationReport.SubjectBalance(name, billed, paid, outstanding, waived,
                billed.subtract(paid).subtract(outstanding).subtract(waived));
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private Object lockOf(String loanId) {
        return locks.computeIfAbsent(loanId, k -> new Object());
    }

    private void requireActive(Loan loan) {
        if (loan.getStatus() != LoanStatus.ACTIVE) {
            throw new LedgerException(LedgerErrorCode.LOAN_NOT_ACTIVE,
                    "贷款已结清，不允许该操作: " + loan.getLoanId());
        }
    }

    private void requirePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new LedgerException(LedgerErrorCode.INVALID_AMOUNT,
                    "还款金额必须为正数: " + amount);
        }
    }

    /**
     * 幂等判定：同键同额 -> 返回首次流水；同键不同额 -> 冲突拒绝。
     * expectedAmount 为 null 时（提前结清）只按键匹配。
     */
    private Payment checkIdempotency(Loan loan, String idempotencyKey, BigDecimal expectedAmount) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new LedgerException(LedgerErrorCode.INVALID_AMOUNT, "幂等键不能为空");
        }
        Payment existing = idempotencyIndex.get(loan.getLoanId()).get(idempotencyKey);
        if (existing == null) {
            return null;
        }
        if (expectedAmount != null
                && existing.amount().compareTo(expectedAmount.setScale(2, RoundingMode.HALF_UP)) != 0) {
            throw new LedgerException(LedgerErrorCode.CONCURRENT_CONFLICT,
                    "同一幂等键提交了不同金额，无法安全合并: key=" + idempotencyKey
                            + ", first=" + existing.amount() + ", now=" + expectedAmount);
        }
        return existing;
    }

    /**
     * 逾期判定与罚息累计（结算口径 = asOfDate）：
     * 到期日 + 宽限期 < asOfDate 且未足额 -> OVERDUE；
     * 罚息按日累计，基数 = 该期未还本金 + 未还利息，
     * 累计区间 (penaltyAccruedThrough, asOfDate]，已计部分不重复计。
     */
    private void accrueOverdueLocked(Loan loan, LocalDate asOfDate) {
        OverdueRules rules = loan.getOverdueRules();
        for (Installment inst : loan.getSchedule()) {
            LocalDate graceEnd = inst.getDueDate().plusDays(rules.gracePeriodDays());
            boolean fullyPaid = inst.totalRemaining().signum() == 0;
            if (fullyPaid) {
                inst.setStatus(InstallmentStatus.PAID);
                continue;
            }
            if (asOfDate.isAfter(graceEnd)) {
                inst.setStatus(InstallmentStatus.OVERDUE);
                LocalDate from = inst.getPenaltyAccruedThrough() != null
                        ? inst.getPenaltyAccruedThrough()
                        : graceEnd;
                if (asOfDate.isAfter(from)) {
                    long days = ChronoUnit.DAYS.between(from, asOfDate);
                    BigDecimal increment = inst.penaltyBase()
                            .multiply(rules.penaltyDailyRate())
                            .multiply(BigDecimal.valueOf(days))
                            .setScale(2, RoundingMode.HALF_UP);
                    if (increment.signum() > 0) {
                        inst.addPenaltyAccrued(increment);
                    }
                    inst.setPenaltyAccruedThrough(asOfDate);
                }
            } else if (inst.getStatus() != InstallmentStatus.PARTIALLY_PAID) {
                inst.setStatus(InstallmentStatus.PENDING);
            }
        }
    }

    /** 全部未偿余额（费用+罚息+利息+本金，跨全部期次）。 */
    private BigDecimal totalOutstanding(Loan loan) {
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Installment inst : loan.getSchedule()) {
            total = total.add(inst.totalRemaining());
        }
        return total;
    }

    /**
     * 资金分配：期次升序，期次内 费用 -&gt; 罚息 -&gt; 利息 -&gt; 本金。
     * 调用方保证 amount &lt;= totalOutstanding，因此可全额分配，无剩余、无负数。
     */
    private Payment applyPayment(Loan loan, String idempotencyKey, BigDecimal amount, LocalDate valueDate) {
        BigDecimal remaining = amount.setScale(2, RoundingMode.HALF_UP);
        List<PaymentAllocation> allocations = new ArrayList<>();

        for (Installment inst : loan.getSchedule()) {
            if (remaining.signum() == 0) {
                break;
            }
            remaining = allocateInstallment(inst, remaining, allocations, false);
        }
        if (remaining.signum() != 0) {
            // 防御：调用方已校验不超超额，理论上不可达
            throw new LedgerException(LedgerErrorCode.OVERPAYMENT,
                    "内部错误：存在未分配金额 " + remaining);
        }
        return recordPayment(loan, idempotencyKey, amount, valueDate, allocations);
    }

    /**
     * 提前结清分配：先对已到期期次按 费用 -&gt; 罚息 -&gt; 利息 -&gt; 本金 冲抵，
     * 余款全部冲抵各期剩余本金（未到期利息/费用豁免，不参与分配）。
     */
    private Payment applyPayoffPayment(Loan loan, String idempotencyKey,
                                       BigDecimal amount, LocalDate valueDate) {
        BigDecimal remaining = amount.setScale(2, RoundingMode.HALF_UP);
        List<PaymentAllocation> allocations = new ArrayList<>();

        for (Installment inst : loan.getSchedule()) {
            if (remaining.signum() == 0) {
                break;
            }
            if (!inst.getDueDate().isAfter(valueDate)) {
                remaining = allocateInstallment(inst, remaining, allocations, false);
            }
        }
        for (Installment inst : loan.getSchedule()) {
            if (remaining.signum() == 0) {
                break;
            }
            remaining = allocateInstallment(inst, remaining, allocations, true);
        }
        if (remaining.signum() != 0) {
            throw new LedgerException(LedgerErrorCode.OVERPAYMENT,
                    "内部错误：结清金额未完全分配 " + remaining);
        }
        return recordPayment(loan, idempotencyKey, amount, valueDate, allocations);
    }

    /**
     * 在单期内按 费用 -&gt; 罚息 -&gt; 利息 -&gt; 本金 顺序冲抵；
     * principalOnly=true 时仅冲抵本金（提前结清第二阶段用）。
     *
     * @return 冲抵后剩余金额
     */
    private BigDecimal allocateInstallment(Installment inst, BigDecimal remaining,
                                           List<PaymentAllocation> allocations, boolean principalOnly) {
        BigDecimal fee = BigDecimal.ZERO.setScale(2);
        BigDecimal penalty = BigDecimal.ZERO.setScale(2);
        BigDecimal interest = BigDecimal.ZERO.setScale(2);
        if (!principalOnly) {
            fee = remaining.min(inst.remainingFee());
            inst.addFeePaid(fee);
            remaining = remaining.subtract(fee);

            penalty = remaining.min(inst.remainingPenalty());
            inst.addPenaltyPaid(penalty);
            remaining = remaining.subtract(penalty);

            interest = remaining.min(inst.remainingInterest());
            inst.addInterestPaid(interest);
            remaining = remaining.subtract(interest);
        }
        BigDecimal principal = remaining.min(inst.remainingPrincipal());
        inst.addPrincipalPaid(principal);
        remaining = remaining.subtract(principal);

        if (fee.signum() > 0 || penalty.signum() > 0 || interest.signum() > 0 || principal.signum() > 0) {
            // 同一期可能被分配两次（结清场景），合并为一条记录
            final BigDecimal f = fee, pen = penalty, intr = interest, prin = principal;
            PaymentAllocation merged = allocations.stream()
                    .filter(a -> a.period() == inst.getPeriod())
                    .findFirst()
                    .map(a -> new PaymentAllocation(inst.getPeriod(),
                            a.feeApplied().add(f), a.penaltyApplied().add(pen),
                            a.interestApplied().add(intr), a.principalApplied().add(prin)))
                    .orElse(new PaymentAllocation(inst.getPeriod(), f, pen, intr, prin));
            allocations.removeIf(a -> a.period() == inst.getPeriod());
            allocations.add(merged);
        }
        if (inst.totalRemaining().signum() == 0) {
            inst.setStatus(InstallmentStatus.PAID);
        } else if (inst.getStatus() != InstallmentStatus.OVERDUE) {
            inst.setStatus(InstallmentStatus.PARTIALLY_PAID);
        }
        return remaining;
    }

    /** 更新本金余额与状态，登记流水与幂等索引。 */
    private Payment recordPayment(Loan loan, String idempotencyKey, BigDecimal amount,
                                  LocalDate valueDate, List<PaymentAllocation> allocations) {
        BigDecimal principalRepaid = allocations.stream()
                .map(PaymentAllocation::principalApplied)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        loan.setOutstandingPrincipal(loan.getOutstandingPrincipal().subtract(principalRepaid));
        if (loan.getOutstandingPrincipal().signum() == 0 && totalOutstanding(loan).signum() == 0) {
            loan.setStatus(LoanStatus.SETTLED);
        }

        Payment payment = new Payment(
                "PAY-" + loan.getLoanId() + "-" + (loan.getPayments().size() + 1),
                idempotencyKey, loan.getLoanId(),
                amount.setScale(2, RoundingMode.HALF_UP), valueDate, allocations);
        loan.getPayments().add(payment);
        idempotencyIndex.get(loan.getLoanId()).put(idempotencyKey, payment);
        return payment;
    }

    /**
     * 守恒校验：
     * 1) 未偿本金 == 各期未还本金合计，且非负；
     * 2) 所有还款入账总额 == 各期已冲抵(费用+罚息+利息+本金)合计；
     * 3) 任何已冲抵额不超过应还额（无负数余额）。
     */
    private void checkInvariants(Loan loan) {
        BigDecimal principalRemaining = BigDecimal.ZERO;
        BigDecimal appliedTotal = BigDecimal.ZERO;
        for (Installment inst : loan.getSchedule()) {
            principalRemaining = principalRemaining.add(inst.remainingPrincipal());
            appliedTotal = appliedTotal
                    .add(inst.getFeePaid()).add(inst.getPenaltyPaid())
                    .add(inst.getInterestPaid()).add(inst.getPrincipalPaid());
            if (inst.remainingFee().signum() < 0 || inst.remainingPenalty().signum() < 0
                    || inst.remainingInterest().signum() < 0 || inst.remainingPrincipal().signum() < 0) {
                throw new IllegalStateException("出现负数余额: loan=" + loan.getLoanId()
                        + ", period=" + inst.getPeriod());
            }
        }
        if (loan.getStatus() == LoanStatus.ACTIVE
                && loan.getOutstandingPrincipal().compareTo(principalRemaining) != 0) {
            throw new IllegalStateException("本金不守恒: loan=" + loan.getLoanId()
                    + ", outstanding=" + loan.getOutstandingPrincipal()
                    + ", scheduleRemaining=" + principalRemaining);
        }
        BigDecimal paidTotal = loan.getPayments().stream()
                .map(Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (paidTotal.compareTo(appliedTotal) != 0) {
            throw new IllegalStateException("账本不守恒: loan=" + loan.getLoanId()
                    + ", paid=" + paidTotal + ", applied=" + appliedTotal);
        }
    }
}
