package com.github.highcumontoa.loanservicingledgerjava.service;

import com.github.highcumontoa.loanservicingledgerjava.config.LedgerProperties;
import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import com.github.highcumontoa.loanservicingledgerjava.domain.InstallmentStatus;
import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.domain.LoanRules;
import com.github.highcumontoa.loanservicingledgerjava.domain.LoanStatus;
import com.github.highcumontoa.loanservicingledgerjava.domain.Payment;
import com.github.highcumontoa.loanservicingledgerjava.domain.PaymentAllocation;
import com.github.highcumontoa.loanservicingledgerjava.domain.WaiverEntry;
import com.github.highcumontoa.loanservicingledgerjava.domain.reconciliation.ReconciliationReport;
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
    private final Reconciler reconciler;

    private final Map<String, Loan> loans = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    /** loanId -> (idempotencyKey -> Payment)，用于幂等判定。 */
    private final Map<String, Map<String, Payment>> idempotencyIndex = new ConcurrentHashMap<>();

    public LoanLedgerService(AmortizationCalculator calculator, LedgerProperties properties) {
        this(calculator, properties, new Reconciler());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LoanLedgerService(AmortizationCalculator calculator, LedgerProperties properties,
                             Reconciler reconciler) {
        this.calculator = calculator;
        this.properties = properties;
        this.reconciler = reconciler;
    }

    /** 发放贷款（逾期口径沿用全局默认）并生成摊销计划。 */
    public Loan disburse(String loanId, BigDecimal principal, BigDecimal annualRate,
                         int termMonths, LocalDate disbursementDate, BigDecimal monthlyFee) {
        return disburse(loanId, principal, annualRate, termMonths, disbursementDate,
                monthlyFee, null, null);
    }

    /**
     * 发放贷款并生成摊销计划。
     *
     * @param customGracePeriodDays 本笔贷款单独指定的宽限期天数；null 沿用全局默认
     * @param customPenaltyDailyRate 本笔贷款单独指定的罚息日利率；null 沿用全局默认
     *
     * <p>规则在放款当时固化：传入 null 的项取<b>当前</b>全局默认快照，
     * 之后全局默认如何修改都不再影响本笔贷款的逾期判定与罚息。
     */
    public Loan disburse(String loanId, BigDecimal principal, BigDecimal annualRate,
                         int termMonths, LocalDate disbursementDate, BigDecimal monthlyFee,
                         Integer customGracePeriodDays, BigDecimal customPenaltyDailyRate) {
        if (loanId == null || loanId.isBlank()
                || principal == null || principal.signum() <= 0
                || annualRate == null || annualRate.signum() < 0
                || termMonths <= 0 || disbursementDate == null) {
            throw new LedgerException(LedgerErrorCode.INVALID_DISBURSEMENT,
                    "非法放款参数: loanId=" + loanId + ", principal=" + principal
                            + ", annualRate=" + annualRate + ", termMonths=" + termMonths);
        }
        if (customGracePeriodDays != null && customGracePeriodDays < 0) {
            throw new LedgerException(LedgerErrorCode.INVALID_DISBURSEMENT,
                    "宽限期天数不能为负: " + customGracePeriodDays);
        }
        if (customPenaltyDailyRate != null && customPenaltyDailyRate.signum() < 0) {
            throw new LedgerException(LedgerErrorCode.INVALID_DISBURSEMENT,
                    "罚息日利率不能为负: " + customPenaltyDailyRate);
        }
        Object lock = locks.computeIfAbsent(loanId, k -> new Object());
        synchronized (lock) {
            if (loans.containsKey(loanId)) {
                throw new LedgerException(LedgerErrorCode.INVALID_DISBURSEMENT,
                        "贷款已存在: " + loanId);
            }
            // 放款当时固化逾期口径：未指定项取当前全局默认的快照
            int graceDays = customGracePeriodDays != null
                    ? customGracePeriodDays : properties.getGracePeriodDays();
            BigDecimal penaltyRate = customPenaltyDailyRate != null
                    ? customPenaltyDailyRate.setScale(8, RoundingMode.HALF_UP)
                    : properties.getPenaltyDailyRate().setScale(8, RoundingMode.HALF_UP);
            LoanRules rules = new LoanRules(graceDays, penaltyRate,
                    customGracePeriodDays != null, customPenaltyDailyRate != null);

            BigDecimal fee = monthlyFee != null ? monthlyFee : properties.getDefaultMonthlyFee();
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
            // 结清后关闭所有期次：剩余未收的利息/费用/罚息逐期逐项按减免处理，
            // 本金已由结清款全额冲抵（不减免本金），保证各期未偿合计为 0
            for (Installment inst : loan.getSchedule()) {
                BigDecimal wInterest = inst.remainingInterest();
                BigDecimal wFee = inst.remainingFee();
                BigDecimal wPenalty = inst.remainingPenalty();
                if (wInterest.signum() > 0 || wFee.signum() > 0 || wPenalty.signum() > 0) {
                    if (wInterest.signum() > 0) {
                        inst.addInterestWaived(wInterest);
                    }
                    if (wFee.signum() > 0) {
                        inst.addFeeWaived(wFee);
                    }
                    if (wPenalty.signum() > 0) {
                        inst.addPenaltyWaived(wPenalty);
                    }
                    loan.getWaivers().add(new WaiverEntry(payment.paymentId(), inst.getPeriod(),
                            valueDate, wInterest, wFee, wPenalty));
                }
                inst.setStatus(InstallmentStatus.PAID);
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
     * 按结算日导出单笔贷款的对账结果（账本自身现金口径）。
     *
     * <p>结算口径：先在锁内把逾期/罚息幂等推进到 asOfDate（同结算日重复或乱序补跑
     * 都不重复计提），再基于账本状态出报告。
     */
    public ReconciliationReport reconcile(String loanId, LocalDate asOfDate) {
        return reconcile(loanId, asOfDate, null);
    }

    /**
     * 按结算日导出对账结果；externalCashReceived 非 null 时以外部现金系统口径对比，
     * 用于把现金侧对不上的差额直接指出来。
     */
    public ReconciliationReport reconcile(String loanId, LocalDate asOfDate,
                                          BigDecimal externalCashReceived) {
        if (asOfDate == null) {
            throw new LedgerException(LedgerErrorCode.INVALID_AMOUNT, "结算日不能为空");
        }
        Object lock = lockOf(loanId);
        synchronized (lock) {
            Loan loan = getLoan(loanId);
            if (loan.getStatus() != LoanStatus.SETTLED) {
                accrueOverdueLocked(loan, asOfDate);
                checkInvariants(loan);
            }
            return reconciler.build(loan, asOfDate, externalCashReceived);
        }
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
        // 逾期口径取放款时固化在贷款上的规则，全局默认后续变化不影响本贷款
        LoanRules rules = loan.getRules();
        for (Installment inst : loan.getSchedule()) {
            LocalDate graceEnd = inst.getDueDate().plusDays(rules.getGracePeriodDays());
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
                            .multiply(rules.getPenaltyDailyRate())
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
            // 应收侧守恒：应收 == 已冲抵 + 未偿 + 减免（逐期逐科目）
            assertComponentConserved(loan, inst, "PRINCIPAL",
                    inst.getPrincipalDue(), inst.getPrincipalPaid(),
                    inst.remainingPrincipal(), BigDecimal.ZERO.setScale(2));
            assertComponentConserved(loan, inst, "INTEREST",
                    inst.getInterestDue(), inst.getInterestPaid(),
                    inst.remainingInterest(), inst.getInterestWaived());
            assertComponentConserved(loan, inst, "FEE",
                    inst.getFeeDue(), inst.getFeePaid(),
                    inst.remainingFee(), inst.getFeeWaived());
            assertComponentConserved(loan, inst, "PENALTY",
                    inst.getPenaltyAccrued(), inst.getPenaltyPaid(),
                    inst.remainingPenalty(), inst.getPenaltyWaived());
            if (loan.getStatus() == LoanStatus.SETTLED && inst.totalRemaining().signum() != 0) {
                throw new IllegalStateException("已结清贷款存在未偿: loan=" + loan.getLoanId()
                        + ", period=" + inst.getPeriod() + ", remaining=" + inst.totalRemaining());
            }
        }
        if (loan.getStatus() == LoanStatus.ACTIVE
                && loan.getOutstandingPrincipal().compareTo(principalRemaining) != 0) {
            throw new IllegalStateException("本金不守恒: loan=" + loan.getLoanId()
                    + ", outstanding=" + loan.getOutstandingPrincipal()
                    + ", scheduleRemaining=" + principalRemaining);
        }
        if (loan.getStatus() == LoanStatus.SETTLED
                && loan.getOutstandingPrincipal().signum() != 0) {
            throw new IllegalStateException("已结清贷款本金余额非零: loan=" + loan.getLoanId()
                    + ", outstanding=" + loan.getOutstandingPrincipal());
        }
        BigDecimal paidTotal = loan.getPayments().stream()
                .map(Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (paidTotal.compareTo(appliedTotal) != 0) {
            throw new IllegalStateException("账本不守恒: loan=" + loan.getLoanId()
                    + ", paid=" + paidTotal + ", applied=" + appliedTotal);
        }
    }

    private void assertComponentConserved(Loan loan, Installment inst, String component,
                                          BigDecimal due, BigDecimal paid,
                                          BigDecimal outstanding, BigDecimal waived) {
        BigDecimal rhs = paid.add(outstanding).add(waived);
        if (due.compareTo(rhs) != 0) {
            throw new IllegalStateException("应收侧不守恒: loan=" + loan.getLoanId()
                    + ", period=" + inst.getPeriod() + ", component=" + component
                    + ", due=" + due + ", paid=" + paid
                    + ", outstanding=" + outstanding + ", waived=" + waived);
        }
    }
}
