package com.github.highcumontoa.loanservicingledgerjava.domain.reconciliation;

import java.math.BigDecimal;

/**
 * 一条对账不平的明细：指明是哪个平衡面、哪个科目、差多少。
 * side: RECEIVABLE（应收侧）或 CASH（现金侧）。
 */
public record Discrepancy(
        String side,
        String component,
        BigDecimal left,
        String leftLabel,
        BigDecimal right,
        String rightLabel,
        BigDecimal difference) {
}
