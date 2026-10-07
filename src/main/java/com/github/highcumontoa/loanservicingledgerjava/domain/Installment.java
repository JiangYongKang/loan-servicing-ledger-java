package com.github.highcumontoa.loanservicingledgerjava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 单期摊销计划行。金额以分为最小精度（scale=2）。
 * 应还 = 本金 + 利息 + 费用 + 罚息；已冲抵部分记录在 paid* 字段。
 */
public class Installment {

    private final int period;
    private final LocalDate dueDate;
    private final BigDecimal principalDue;
    private final BigDecimal interestDue;
    private final BigDecimal feeDue;

    private BigDecimal principalPaid = BigDecimal.ZERO.setScale(2);
    private BigDecimal interestPaid = BigDecimal.ZERO.setScale(2);
    private BigDecimal feePaid = BigDecimal.ZERO.setScale(2);
    private BigDecimal penaltyAccrued = BigDecimal.ZERO.setScale(2);
    private BigDecimal penaltyPaid = BigDecimal.ZERO.setScale(2);

    // 提前结清时逐期、逐项减免的金额（未到期的利息/费用，及任何不再追收的罚息）
    private BigDecimal interestWaived = BigDecimal.ZERO.setScale(2);
    private BigDecimal feeWaived = BigDecimal.ZERO.setScale(2);
    private BigDecimal penaltyWaived = BigDecimal.ZERO.setScale(2);

    private InstallmentStatus status = InstallmentStatus.PENDING;
    /** 罚息已累计到的日期（含），防止同一期次重复计罚。 */
    private LocalDate penaltyAccruedThrough;

    public Installment(int period, LocalDate dueDate, BigDecimal principalDue,
                       BigDecimal interestDue, BigDecimal feeDue) {
        this.period = period;
        this.dueDate = dueDate;
        this.principalDue = principalDue;
        this.interestDue = interestDue;
        this.feeDue = feeDue;
    }

    public int getPeriod() { return period; }
    public LocalDate getDueDate() { return dueDate; }
    public BigDecimal getPrincipalDue() { return principalDue; }
    public BigDecimal getInterestDue() { return interestDue; }
    public BigDecimal getFeeDue() { return feeDue; }
    public BigDecimal getPrincipalPaid() { return principalPaid; }
    public BigDecimal getInterestPaid() { return interestPaid; }
    public BigDecimal getFeePaid() { return feePaid; }
    public BigDecimal getPenaltyAccrued() { return penaltyAccrued; }
    public BigDecimal getPenaltyPaid() { return penaltyPaid; }
    public BigDecimal getInterestWaived() { return interestWaived; }
    public BigDecimal getFeeWaived() { return feeWaived; }
    public BigDecimal getPenaltyWaived() { return penaltyWaived; }
    public InstallmentStatus getStatus() { return status; }
    public LocalDate getPenaltyAccruedThrough() { return penaltyAccruedThrough; }

    public BigDecimal remainingFee() { return feeDue.subtract(feePaid).subtract(feeWaived); }
    public BigDecimal remainingPenalty() { return penaltyAccrued.subtract(penaltyPaid).subtract(penaltyWaived); }
    public BigDecimal remainingInterest() { return interestDue.subtract(interestPaid).subtract(interestWaived); }
    public BigDecimal remainingPrincipal() { return principalDue.subtract(principalPaid); }

    /** 本期全部未偿余额合计。 */
    public BigDecimal totalRemaining() {
        return remainingFee().add(remainingPenalty()).add(remainingInterest()).add(remainingPrincipal());
    }

    /** 应计罚息基数：未还本金 + 未还利息。 */
    public BigDecimal penaltyBase() {
        return remainingPrincipal().add(remainingInterest());
    }

    public void addFeePaid(BigDecimal v) { feePaid = feePaid.add(v); }
    public void addPenaltyPaid(BigDecimal v) { penaltyPaid = penaltyPaid.add(v); }
    public void addInterestPaid(BigDecimal v) { interestPaid = interestPaid.add(v); }
    public void addPrincipalPaid(BigDecimal v) { principalPaid = principalPaid.add(v); }
    public void addPenaltyAccrued(BigDecimal v) { penaltyAccrued = penaltyAccrued.add(v); }
    public void addInterestWaived(BigDecimal v) { interestWaived = interestWaived.add(v); }
    public void addFeeWaived(BigDecimal v) { feeWaived = feeWaived.add(v); }
    public void addPenaltyWaived(BigDecimal v) { penaltyWaived = penaltyWaived.add(v); }
    public void setStatus(InstallmentStatus s) { this.status = s; }
    public void setPenaltyAccruedThrough(LocalDate d) { this.penaltyAccruedThrough = d; }
}
