package com.github.highcumontoa.loanservicingledgerjava.service;

import com.github.highcumontoa.loanservicingledgerjava.domain.LoanStatus;
import com.github.highcumontoa.loanservicingledgerjava.domain.Money;

import java.time.LocalDate;
import java.util.List;

/**
 * 某结算时点的账户快照：含派生的逾期判定与守恒口径总额。
 */
public record AccountSnapshot(
        String loanId,
        LoanStatus status,
        LocalDate settlementDate,
        Money outstandingPrincipal,
        Money outstandingInterest,
        Money outstandingPenalty,
        Money totalOutstanding,
        List<InstallmentView> installments,
        List<String> basis) {

    /** 期次视图。 */
    public record InstallmentView(
            int periodNo,
            LocalDate dueDate,
            String status,
            boolean overdue,
            int overdueDays,
            Money principalDue,
            Money interestDue,
            Money penaltyDue,
            Money principalPaid,
            Money interestPaid,
            Money penaltyPaid,
            Money outstanding) {
    }
}
