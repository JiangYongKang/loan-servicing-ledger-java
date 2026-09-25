package com.github.highcumontoa.loanservicingledgerjava.domain;

import java.time.LocalDate;

/**
 * 罚息计收流水：每次罚息计提生成一条不可变记录，
 * 记录计提区间与当时的日罚息率，保证规则解释可复现、同期不重复计提。
 */
public record PenaltyCharge(
        int periodNo,
        LocalDate fromDate,
        LocalDate toDate,
        int days,
        Money baseAmount,
        java.math.BigDecimal dailyRate,
        Money amount) {
}
