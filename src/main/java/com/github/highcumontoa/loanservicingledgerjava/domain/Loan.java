package com.github.highcumontoa.loanservicingledgerjava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 贷款聚合根：贷款信息（规则快照）+ 摊销计划 + 还款流水。
 *
 * <p>规则快照语义：发放时把 graceDays、dailyPenaltyRate、annualRate 固化在贷款上，
 * 之后全局规则调整不会影响既有贷款，对既有流水的解释永远基于快照，稳定可复现。
 *
 * <p>所有状态变更必须在仓库的贷款级锁内、由 LedgerService 完成。
 */
public class Loan {

    private String loanId;
    private Money principal;
    private BigDecimal annualRate;
    private int termMonths;
    private LocalDate originationDate;
    private LocalDate firstDueDate;
    private int graceDays;
    private BigDecimal dailyPenaltyRate;
    private LoanStatus status = LoanStatus.ACTIVE;
    private final List<Installment> installments = new ArrayList<>();
    private final List<Payment> payments = new ArrayList<>();

    public Loan() {
    }

    public Loan(String loanId, Money principal, BigDecimal annualRate, int termMonths,
                LocalDate originationDate, LocalDate firstDueDate,
                int graceDays, BigDecimal dailyPenaltyRate,
                List<Installment> schedule) {
        this.loanId = loanId;
        this.principal = principal;
        this.annualRate = annualRate;
        this.termMonths = termMonths;
        this.originationDate = originationDate;
        this.firstDueDate = firstDueDate;
        this.graceDays = graceDays;
        this.dailyPenaltyRate = dailyPenaltyRate;
        this.installments.addAll(schedule);
    }

    public String getLoanId() {
        return loanId;
    }

    public Money getPrincipal() {
        return principal;
    }

    public BigDecimal getAnnualRate() {
        return annualRate;
    }

    public int getTermMonths() {
        return termMonths;
    }

    public LocalDate getOriginationDate() {
        return originationDate;
    }

    public LocalDate getFirstDueDate() {
        return firstDueDate;
    }

    public int getGraceDays() {
        return graceDays;
    }

    public BigDecimal getDailyPenaltyRate() {
        return dailyPenaltyRate;
    }

    public LoanStatus getStatus() {
        return status;
    }

    public void markPaidOff() {
        this.status = LoanStatus.PAID_OFF;
    }

    public List<Installment> getInstallments() {
        return Collections.unmodifiableList(installments);
    }

    /** 供服务层在锁内取可变列表。 */
    List<Installment> installmentsMutable() {
        return installments;
    }

    public List<Payment> getPayments() {
        return Collections.unmodifiableList(payments);
    }

    /** 追加已入账还款流水（仅 LedgerService 在锁内调用）。 */
    public void addPayment(Payment payment) {
        payments.add(payment);
    }

    // ---- 派生汇总（守恒校验与快照使用）----

    public Money outstandingPrincipal() {
        return sum(installments, Installment::outstandingPrincipal);
    }

    public Money outstandingInterest() {
        return sum(installments, Installment::outstandingInterest);
    }

    public Money outstandingPenalty() {
        return sum(installments, Installment::outstandingPenalty);
    }

    public Money totalOutstanding() {
        return outstandingPenalty().plus(outstandingInterest()).plus(outstandingPrincipal());
    }

    public Money totalInterestWaived() {
        return sum(installments, Installment::getInterestWaived);
    }

    public Money totalPaid() {
        Money t = Money.ZERO;
        for (Payment p : payments) {
            t = t.plus(p.getAmount());
        }
        return t;
    }

    /**
     * 守恒：已还总额 + 当前未偿总额 = 发放本金 + 全部已计利息 + 全部已计罚息。
     * 即资金不会凭空产生或消失。
     */
    public Money totalDueEver() {
        Money t = Money.ZERO;
        for (Installment i : installments) {
            t = t.plus(i.getPrincipalDue()).plus(i.getInterestDue()).plus(i.getPenaltyDue());
        }
        return t;
    }

    private static Money sum(List<Installment> list, java.util.function.Function<Installment, Money> f) {
        Money t = Money.ZERO;
        for (Installment i : list) {
            t = t.plus(f.apply(i));
        }
        return t;
    }
}
