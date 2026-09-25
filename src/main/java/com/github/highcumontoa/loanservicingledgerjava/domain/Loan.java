package com.github.highcumontoa.loanservicingledgerjava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 贷款账户聚合：本金余额、摊销计划与还款流水。 */
public class Loan {

    private final String loanId;
    private final BigDecimal principal;
    private final BigDecimal annualRate;
    private final int termMonths;
    private final LocalDate disbursementDate;
    private final BigDecimal monthlyFee;

    private LoanStatus status = LoanStatus.ACTIVE;
    private BigDecimal outstandingPrincipal;
    private final List<Installment> schedule = new ArrayList<>();
    private final List<Payment> payments = new ArrayList<>();

    public Loan(String loanId, BigDecimal principal, BigDecimal annualRate,
                int termMonths, LocalDate disbursementDate, BigDecimal monthlyFee) {
        this.loanId = loanId;
        this.principal = principal;
        this.annualRate = annualRate;
        this.termMonths = termMonths;
        this.disbursementDate = disbursementDate;
        this.monthlyFee = monthlyFee;
        this.outstandingPrincipal = principal;
    }

    public String getLoanId() { return loanId; }
    public BigDecimal getPrincipal() { return principal; }
    public BigDecimal getAnnualRate() { return annualRate; }
    public int getTermMonths() { return termMonths; }
    public LocalDate getDisbursementDate() { return disbursementDate; }
    public BigDecimal getMonthlyFee() { return monthlyFee; }
    public LoanStatus getStatus() { return status; }
    public BigDecimal getOutstandingPrincipal() { return outstandingPrincipal; }
    public List<Installment> getSchedule() { return schedule; }
    public List<Payment> getPayments() { return payments; }

    public void setStatus(LoanStatus s) { this.status = s; }
    public void setOutstandingPrincipal(BigDecimal v) { this.outstandingPrincipal = v; }
}
