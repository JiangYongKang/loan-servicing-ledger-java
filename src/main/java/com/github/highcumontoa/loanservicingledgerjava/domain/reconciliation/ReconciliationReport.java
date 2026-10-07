package com.github.highcumontoa.loanservicingledgerjava.domain.reconciliation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 单笔贷款在某结算日(asOfDate)的对账结果。
 *
 * <p>两个平衡面：
 * <ul>
 *   <li>应收侧：历期应收合计 == 已冲抵 + 未偿 + 减免（按四科目逐科平衡）。</li>
 *   <li>现金侧：实际入账现金合计 == 已冲抵合计（截至结算日的入账流水口径）。</li>
 * </ul>
 * 不平衡时 discrepancies 逐条指出科目与差额。
 */
public record ReconciliationReport(
        String loanId,
        LocalDate asOfDate,
        String loanStatus,
        List<ComponentBalance> components,
        BigDecimal totalDue,
        BigDecimal totalPaid,
        BigDecimal totalOutstanding,
        BigDecimal totalWaived,
        BigDecimal cashReceived,
        BigDecimal cashApplied,
        BigDecimal cashDifference,
        boolean receivablesBalanced,
        boolean cashBalanced,
        boolean balanced,
        List<Discrepancy> discrepancies) {
}
