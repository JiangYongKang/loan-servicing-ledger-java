package com.github.highcumontoa.loanservicingledgerjava.domain;

import com.github.highcumontoa.loanservicingledgerjava.error.ErrorCode;
import com.github.highcumontoa.loanservicingledgerjava.error.LedgerException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 摊销计划中的一期。
 *
 * <p>不变量（由 LedgerService 在锁内维护）：
 * <ul>
 *   <li>paid 三项分别 &le; due 三项，任何未偿余额不为负；</li>
 *   <li>罚息按 {@link PenaltyCharge} 流水增量计提，同一日期区间绝不重复计提；</li>
 *   <li>状态随已还金额派生：未还 PENDING、部分 PARTIAL、当期清 PAID。</li>
 * </ul>
 */
public class Installment {

    private int periodNo;
    private LocalDate dueDate;
    private Money principalDue;
    private Money interestDue;
    private Money principalPaid = Money.ZERO;
    private Money interestPaid = Money.ZERO;
    private Money penaltyDue = Money.ZERO;
    private Money penaltyPaid = Money.ZERO;
    /** 提前结清时被免除的未到期利息（留痕，不再构成应收）。 */
    private Money interestWaived = Money.ZERO;
    private InstallmentStatus status = InstallmentStatus.PENDING;
    /** 罚息已计提到哪一天（含）；null 表示尚未计提。 */
    private LocalDate penaltyAccruedThrough;
    private final List<PenaltyCharge> penaltyCharges = new ArrayList<>();

    public Installment() {
    }

    public Installment(int periodNo, LocalDate dueDate, Money principalDue, Money interestDue) {
        this.periodNo = periodNo;
        this.dueDate = dueDate;
        this.principalDue = principalDue;
        this.interestDue = interestDue;
    }

    public int getPeriodNo() {
        return periodNo;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public Money getPrincipalDue() {
        return principalDue;
    }

    public Money getInterestDue() {
        return interestDue;
    }

    public Money getPrincipalPaid() {
        return principalPaid;
    }

    public Money getInterestPaid() {
        return interestPaid;
    }

    public Money getPenaltyDue() {
        return penaltyDue;
    }

    public Money getPenaltyPaid() {
        return penaltyPaid;
    }

    public Money getInterestWaived() {
        return interestWaived;
    }

    public InstallmentStatus getStatus() {
        return status;
    }

    public LocalDate getPenaltyAccruedThrough() {
        return penaltyAccruedThrough;
    }

    public List<PenaltyCharge> getPenaltyCharges() {
        return Collections.unmodifiableList(penaltyCharges);
    }

    // ---- 派生汇总 ----

    public Money outstandingPrincipal() {
        return principalDue.minus(principalPaid);
    }

    public Money outstandingInterest() {
        return interestDue.minus(interestPaid).minus(interestWaived);
    }

    public Money outstandingPenalty() {
        return penaltyDue.minus(penaltyPaid);
    }

    /** 当期全部未偿：罚息 + 利息 + 本金。 */
    public Money outstanding() {
        return outstandingPenalty().plus(outstandingInterest()).plus(outstandingPrincipal());
    }

    public boolean isPaid() {
        return outstanding().isZero();
    }

    // ---- 状态变更（仅账本服务调用）----

    /** 追加一笔罚息计提：金额非负、区间不重复由服务层保证。 */
    public void addPenaltyCharge(PenaltyCharge charge) {
        if (!charge.amount().isPositive()) {
            throw new LedgerException(ErrorCode.INTERNAL_CONSISTENCY,
                    "罚息计提金额必须为正: period=" + periodNo);
        }
        this.penaltyDue = penaltyDue.plus(charge.amount());
        this.penaltyCharges.add(charge);
        this.penaltyAccruedThrough = charge.toDate();
    }

    /**
     * 把罚息计提水位推进到指定日期但不产生金额（基数为 0，例如当期本息已清、
     * 或零罚息率）。保证同一区间不会被重复计提。
     */
    public void advancePenaltyWatermark(LocalDate through) {
        if (penaltyAccruedThrough == null || through.isAfter(penaltyAccruedThrough)) {
            this.penaltyAccruedThrough = through;
        }
    }

    /**
     * 冲抵当期应收，返回实际分配结果与剩余金额。固定顺序：罚息 &rarr; 利息 &rarr; 本金。
     * 任何一项分配不超过其应收，余额永不为负。
     */
    public Allocation.AllocationEntry applyPayment(Money available) {
        Money penaltyPay = min(available, outstandingPenalty());
        available = available.minus(penaltyPay);
        Money interestPay = min(available, outstandingInterest());
        available = available.minus(interestPay);
        Money principalPay = min(available, outstandingPrincipal());

        penaltyPaid = penaltyPaid.plus(penaltyPay);
        interestPaid = interestPaid.plus(interestPay);
        principalPaid = principalPaid.plus(principalPay);
        recomputeStatus();

        return new Allocation.AllocationEntry(periodNo, penaltyPay, interestPay, principalPay);
    }

    /** 提前结清场景：免除指定金额的未到期利息（不得超过未付利息）。 */
    public void waiveInterest(Money amount) {
        if (amount.gt(interestDue.minus(interestPaid))) {
            throw new LedgerException(ErrorCode.INTERNAL_CONSISTENCY,
                    "利息减免超过未付利息: period=" + periodNo);
        }
        interestWaived = interestWaived.plus(amount);
        recomputeStatus();
    }

    /** 提前结清场景：一次性冲抵罚息、利息与全部剩余本金，要求金额恰好覆盖。 */
    public Allocation.AllocationEntry applyPayoff(Money penaltyPay, Money interestPay, Money principalPay) {
        if (penaltyPay.gt(outstandingPenalty()) || interestPay.gt(outstandingInterest())
                || principalPay.gt(outstandingPrincipal())) {
            throw new LedgerException(ErrorCode.INTERNAL_CONSISTENCY,
                    "提前结清冲抵超过应收: period=" + periodNo);
        }
        penaltyPaid = penaltyPaid.plus(penaltyPay);
        interestPaid = interestPaid.plus(interestPay);
        principalPaid = principalPaid.plus(principalPay);
        recomputeStatus();
        return new Allocation.AllocationEntry(periodNo, penaltyPay, interestPay, principalPay);
    }

    private void recomputeStatus() {
        if (outstandingPrincipal().isZero() && outstandingInterest().isZero()
                && outstandingPenalty().isZero()) {
            status = InstallmentStatus.PAID;
        } else if (principalPaid.isPositive() || interestPaid.isPositive() || penaltyPaid.isPositive()) {
            status = InstallmentStatus.PARTIAL;
        } else {
            status = InstallmentStatus.PENDING;
        }
    }

    private static Money min(Money a, Money b) {
        return a.lte(b) ? a : b;
    }
}
