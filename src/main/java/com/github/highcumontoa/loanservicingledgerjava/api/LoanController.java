package com.github.highcumontoa.loanservicingledgerjava.api;

import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.domain.Payment;
import com.github.highcumontoa.loanservicingledgerjava.domain.WaiverEntry;
import com.github.highcumontoa.loanservicingledgerjava.domain.reconciliation.ReconciliationReport;
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

    /**
     * @param gracePeriodDays 本笔贷款单独指定宽限期天数；为空沿用全局默认（放款时固化）
     * @param penaltyDailyRate 本笔贷款单独指定罚息日利率；为空沿用全局默认（放款时固化）
     */
    public record DisburseRequest(String loanId, BigDecimal principal, BigDecimal annualRate,
                                  int termMonths, LocalDate disbursementDate, BigDecimal monthlyFee,
                                  Integer gracePeriodDays, BigDecimal penaltyDailyRate) {}

    public record RepayRequest(String idempotencyKey, BigDecimal amount, LocalDate valueDate) {}

    public record PayoffRequest(String idempotencyKey, LocalDate valueDate) {}

    public record AccrueRequest(LocalDate asOfDate) {}

    public record LoanRulesView(int gracePeriodDays, BigDecimal penaltyDailyRate,
                                boolean customGracePeriod, boolean customPenaltyRate) {
        static LoanRulesView of(Loan loan) {
            return new LoanRulesView(loan.getRules().getGracePeriodDays(),
                    loan.getRules().getPenaltyDailyRate(),
                    loan.getRules().isCustomGracePeriod(),
                    loan.getRules().isCustomPenaltyRate());
        }
    }

    public record InstallmentView(int period, LocalDate dueDate, String status,
                                  BigDecimal principalDue, BigDecimal interestDue, BigDecimal feeDue,
                                  BigDecimal principalPaid, BigDecimal interestPaid, BigDecimal feePaid,
                                  BigDecimal penaltyAccrued, BigDecimal penaltyPaid,
                                  BigDecimal interestWaived, BigDecimal feeWaived,
                                  BigDecimal penaltyWaived, BigDecimal totalRemaining) {
        static InstallmentView of(Installment i) {
            return new InstallmentView(i.getPeriod(), i.getDueDate(), i.getStatus().name(),
                    i.getPrincipalDue(), i.getInterestDue(), i.getFeeDue(),
                    i.getPrincipalPaid(), i.getInterestPaid(), i.getFeePaid(),
                    i.getPenaltyAccrued(), i.getPenaltyPaid(),
                    i.getInterestWaived(), i.getFeeWaived(), i.getPenaltyWaived(),
                    i.totalRemaining());
        }
    }

    public record WaiverView(String paymentId, int period, LocalDate valueDate,
                             BigDecimal interestWaived, BigDecimal feeWaived,
                             BigDecimal penaltyWaived, BigDecimal total) {
        static WaiverView of(WaiverEntry w) {
            return new WaiverView(w.paymentId(), w.period(), w.valueDate(),
                    w.interestWaived(), w.feeWaived(), w.penaltyWaived(), w.total());
        }
    }

    public record LoanView(String loanId, String status, BigDecimal outstandingPrincipal,
                           LoanRulesView rules,
                           List<InstallmentView> schedule, List<Payment> payments,
                           List<WaiverView> waivers) {
        static LoanView of(Loan loan) {
            return new LoanView(loan.getLoanId(), loan.getStatus().name(),
                    loan.getOutstandingPrincipal(), LoanRulesView.of(loan),
                    loan.getSchedule().stream().map(InstallmentView::of).toList(),
                    List.copyOf(loan.getPayments()),
                    loan.getWaivers().stream().map(WaiverView::of).toList());
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

    /**
     * 对账：按结算日导出这笔贷款的对账结果。
     * 可选参数 cashReceived：外部现金系统口径的实际入账合计，用于比对现金侧差额。
     */
    @GetMapping("/{loanId}/reconciliation")
    public ReconciliationReport reconciliation(
            @PathVariable String loanId,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso =
                    org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @RequestParam(required = false) BigDecimal cashReceived) {
        return ledgerService.reconcile(loanId, asOfDate, cashReceived);
    }
}
