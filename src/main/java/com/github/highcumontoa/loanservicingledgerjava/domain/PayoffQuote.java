package com.github.highcumontoa.loanservicingledgerjava.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 提前结清报价（针对某结算日期一次性有效语义：金额可复现）。
 * 口径：已计未付罚息 + 已到期未付利息 + 全部剩余本金；未到期利息免除。
 */
public record PayoffQuote(
        String loanId,
        LocalDate settlementDate,
        Money outstandingPenalty,
        Money outstandingInterest,
        Money outstandingPrincipal,
        Money totalPayoff,
        List<String> basis) {
}
