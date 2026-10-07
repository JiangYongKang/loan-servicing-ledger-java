package com.github.highcumontoa.loanservicingledgerjava.api;

import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.domain.Payment;
import com.github.highcumontoa.loanservicingledgerjava.domain.ReconciliationReport;
import com.github.highcumontoa.loanservicingledgerjava.service.LoanLedgerService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 贷款账本 REST 入口。 */
@RestController
@RequestMapping("/loans")
public class LoanController {

    private final LoanLedgerService ledgerService;

    public LoanController(LoanLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    public record DisburseRequest(String loanId, BigDecimal principal, BigDecimal annualRate,
                                  int termMonths, LocalDate disbursementDate, BigDecimal monthlyFee,
                                  Integer gracePeriodDays, BigDecimal penaltyDailyRate) {}

    public record RepayRequest(String idempotencyKey, BigDecimal amount, LocalDate valueDate) {}

    public record PayoffRequest(String idempotencyKey, LocalDate valueDate) {}

    public record AccrueRequest(LocalDate asOfDate) {}

    public record InstallmentView(int period, LocalDate dueDate, String status,
                                  BigDecimal principalDue, BigDecimal interestDue, BigDecimal feeDue,
                                  BigDecimal principalPaid, BigDecimal interestPaid, BigDecimal feePaid,
                                  BigDecimal penaltyAccrued, BigDecimal penaltyPaid,
                                  BigDecimal interestWaived, BigDecimal feeWaived,
                                  BigDecimal totalRemaining) {
        static InstallmentView of(Installment i) {
            return new InstallmentView(i.getPeriod(), i.getDueDate(), i.getStatus().name(),
                    i.getPrincipalDue(), i.getInterestDue(), i.getFeeDue(),
                    i.getPrincipalPaid(), i.getInterestPaid(), i.getFeePaid(),
                    i.getPenaltyAccrued(), i.getPenaltyPaid(),
                    i.getInterestWaived(), i.getFeeWaived(), i.totalRemaining());
        }
    }

    public record LoanView(String loanId, String status, BigDecimal outstandingPrincipal,
                           int gracePeriodDays, BigDecimal penaltyDailyRate,
                           List<InstallmentView> schedule, List<Payment> payments) {
        static LoanView of(Loan loan) {
            return new LoanView(loan.getLoanId(), loan.getStatus().name(),
                    loan.getOutstandingPrincipal(),
                    loan.getOverdueRules().gracePeriodDays(),
                    loan.getOverdueRules().penaltyDailyRate(),
                    loan.getSchedule().stream().map(InstallmentView::of).toList(),
                    List.copyOf(loan.getPayments()));
        }
    }

    @PostMapping
    public LoanView disburse(@RequestBody DisburseRequest req) {
        return LoanView.of(ledgerService.disburse(req.loanId(), req.principal(), req.annualRate(),
                req.termMonths(), req.disbursementDate(), req.monthlyFee(),
                req.gracePeriodDays(), req.penaltyDailyRate()));
    }

    @GetMapping("/{loanId}")
    public LoanView getLoan(@PathVariable String loanId) {
        return LoanView.of(ledgerService.getLoan(loanId));
    }

    @PostMapping("/{loanId}/payments")
    public Payment repay(@PathVariable String loanId, @RequestBody RepayRequest req) {
        return ledgerService.repay(loanId, req.idempotencyKey(), req.amount(), req.valueDate());
    }

    @PostMapping("/{loanId}/payoff")
    public Payment payoff(@PathVariable String loanId, @RequestBody PayoffRequest req) {
        return ledgerService.payoff(loanId, req.idempotencyKey(), req.valueDate());
    }

    @PostMapping("/{loanId}/accrue")
    public LoanView accrue(@PathVariable String loanId, @RequestBody AccrueRequest req) {
        return LoanView.of(ledgerService.accrueOverdue(loanId, req.asOfDate()));
    }

    @GetMapping("/{loanId}/reconciliation")
    public ReconciliationReport reconcile(@PathVariable String loanId,
                                          @RequestParam LocalDate asOf) {
        return ledgerService.reconcile(loanId, asOf);
    }
}
