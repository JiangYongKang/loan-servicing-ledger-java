package com.github.highcumontoa.loanservicingledgerjava.web;

import com.github.highcumontoa.loanservicingledgerjava.domain.Allocation;
import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.domain.PayoffQuote;
import com.github.highcumontoa.loanservicingledgerjava.domain.Payment;
import com.github.highcumontoa.loanservicingledgerjava.service.AccountSnapshot;
import com.github.highcumontoa.loanservicingledgerjava.service.PaymentResult;

import java.time.LocalDate;
import java.util.List;

/**
 * 响应组装：领域对象 -&gt; 对外 JSON。金额一律输出字符串，杜绝浮点/精度歧义。
 */
public final class LoanResponses {

    private LoanResponses() {
    }

    public record LoanView(
            String loanId, String principal, String annualRate, int termMonths,
            LocalDate originationDate, LocalDate firstDueDate,
            int graceDays, String dailyPenaltyRate, String status,
            List<InstallmentView> installments,
            String outstandingPrincipal, String outstandingInterest,
            String outstandingPenalty, String totalOutstanding) {
    }

    public record InstallmentView(
            int periodNo, LocalDate dueDate, String status,
            String principalDue, String interestDue, String penaltyDue,
            String principalPaid, String interestPaid, String penaltyPaid,
            String interestWaived, String outstanding) {
    }

    public record PaymentView(
            String paymentId, String loanId, String idempotencyKey, String amount,
            LocalDate valueDate, String type, String status, boolean idempotentReplay,
            List<EntryView> allocation) {
    }

    public record EntryView(int periodNo, String penaltyPaid, String interestPaid, String principalPaid) {
    }

    public record SnapshotView(
            String loanId, String status, LocalDate settlementDate,
            String outstandingPrincipal, String outstandingInterest, String outstandingPenalty,
            String totalOutstanding, List<SnapshotInstallmentView> installments, List<String> basis) {
    }

    public record SnapshotInstallmentView(
            int periodNo, LocalDate dueDate, String status, boolean overdue, int overdueDays,
            String principalDue, String interestDue, String penaltyDue,
            String principalPaid, String interestPaid, String penaltyPaid, String outstanding) {
    }

    public record PayoffView(
            String loanId, LocalDate settlementDate, String outstandingPenalty,
            String outstandingInterest, String outstandingPrincipal, String totalPayoff,
            List<String> basis) {
    }

    public record ErrorBody(String code, String message) {
    }

    public static LoanView loanView(Loan l) {
        return new LoanView(
                l.getLoanId(), str(l.getPrincipal()), l.getAnnualRate().toPlainString(),
                l.getTermMonths(), l.getOriginationDate(), l.getFirstDueDate(),
                l.getGraceDays(), l.getDailyPenaltyRate().toPlainString(), l.getStatus().name(),
                l.getInstallments().stream().map(LoanResponses::installmentView).toList(),
                str(l.outstandingPrincipal()), str(l.outstandingInterest()),
                str(l.outstandingPenalty()), str(l.totalOutstanding()));
    }

    private static InstallmentView installmentView(Installment i) {
        return new InstallmentView(
                i.getPeriodNo(), i.getDueDate(), i.getStatus().name(),
                str(i.getPrincipalDue()), str(i.getInterestDue()), str(i.getPenaltyDue()),
                str(i.getPrincipalPaid()), str(i.getInterestPaid()), str(i.getPenaltyPaid()),
                str(i.getInterestWaived()), str(i.outstanding()));
    }

    public static PaymentView paymentView(PaymentResult r) {
        Payment p = r.payment();
        Allocation a = r.allocation();
        List<EntryView> entries = a == null ? List.of()
                : a.entries().stream()
                .map(e -> new EntryView(e.periodNo(), str(e.penaltyPaid()),
                        str(e.interestPaid()), str(e.principalPaid())))
                .toList();
        return new PaymentView(
                p.getPaymentId(), p.getLoanId(), p.getIdempotencyKey(), str(p.getAmount()),
                p.getValueDate(), p.getType().name(), p.getStatus().name(),
                r.idempotentReplay(), entries);
    }

    public static SnapshotView snapshotView(AccountSnapshot s) {
        List<SnapshotInstallmentView> list = s.installments().stream()
                .map(i -> new SnapshotInstallmentView(
                        i.periodNo(), i.dueDate(), i.status(), i.overdue(), i.overdueDays(),
                        str(i.principalDue()), str(i.interestDue()), str(i.penaltyDue()),
                        str(i.principalPaid()), str(i.interestPaid()), str(i.penaltyPaid()),
                        str(i.outstanding())))
                .toList();
        return new SnapshotView(s.loanId(), s.status().name(), s.settlementDate(),
                str(s.outstandingPrincipal()), str(s.outstandingInterest()),
                str(s.outstandingPenalty()), str(s.totalOutstanding()), list, s.basis());
    }

    public static PayoffView payoffView(PayoffQuote q) {
        return new PayoffView(q.loanId(), q.settlementDate(),
                str(q.outstandingPenalty()), str(q.outstandingInterest()),
                str(q.outstandingPrincipal()), str(q.totalPayoff()), q.basis());
    }

    private static String str(com.github.highcumontoa.loanservicingledgerjava.domain.Money m) {
        return m == null ? "0.00" : m.toString();
    }
}
