package com.github.highcumontoa.loanservicingledgerjava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 某笔贷款按结算日导出的对账结果。
 * 平衡公式一：历期应收合计 = 已还 + 未偿 + 减免（按科目逐项核对）；
 * 平衡公式二：实际入账现金合计 = 已冲抵合计。
 */
public record ReconciliationReport(
        String loanId,
        LocalDate asOfDate,
        boolean balanced,
        List<SubjectBalance> subjects,
        CashBalance cash,
        List<Discrepancy> discrepancies) {

    /** 单个科目的平衡明细：应收 = 已还 + 未偿 + 减免。 */
    public record SubjectBalance(
            String subject,
            BigDecimal billed,
            BigDecimal paid,
            BigDecimal outstanding,
            BigDecimal waived,
            BigDecimal difference) {
    }

    /** 现金侧平衡：入账现金合计 对 已冲抵合计。 */
    public record CashBalance(
            BigDecimal cashReceived,
            BigDecimal totalApplied,
            BigDecimal difference) {
    }

    /** 一处对不上的差异：哪个科目、差多少。 */
    public record Discrepancy(String subject, BigDecimal difference, String detail) {
    }
}
