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
    /** 放款时固化的逾期口径（宽限期/罚息日利率），此后不再受全局默认变化影响。 */
    private final LoanRules rules;

    private LoanStatus status = LoanStatus.ACTIVE;
    private BigDecimal outstandingPrincipal;
    private final List<Installment> schedule = new ArrayList<>();
    private final List<Payment> payments = new ArrayList<>();
    /** 提前结清产生的逐期逐项减免记录。 */
    private final List<WaiverEntry> waivers = new ArrayList<>();

    public Loan(String loanId, BigDecimal principal, BigDecimal annualRate,
                int termMonths, LocalDate disbursementDate, BigDecimal monthlyFee,
                LoanRules rules) {
        this.loanId = loanId;
        this.principal = principal;
        this.annualRate = annualRate;
        this.termMonths = termMonths;
        this.disbursementDate = disbursementDate;
        this.monthlyFee = monthlyFee;
        this.rules = rules;
        this.outstandingPrincipal = principal;
    }

    public String getLoanId() { return loanId; }
    public BigDecimal getPrincipal() { return principal; }
    public BigDecimal getAnnualRate() { return annualRate; }
    public int getTermMonths() { return termMonths; }
    public LocalDate getDisbursementDate() { return disbursementDate; }
    public BigDecimal getMonthlyFee() { return monthlyFee; }
    public LoanRules getRules() { return rules; }
    public LoanStatus getStatus() { return status; }
    public BigDecimal getOutstandingPrincipal() { return outstandingPrincipal; }
    public List<Installment> getSchedule() { return schedule; }
    public List<Payment> getPayments() { return payments; }
    public List<WaiverEntry> getWaivers() { return waivers; }

    public void setStatus(LoanStatus s) { this.status = s; }
    public void setOutstandingPrincipal(BigDecimal v) { this.outstandingPrincipal = v; }
}
