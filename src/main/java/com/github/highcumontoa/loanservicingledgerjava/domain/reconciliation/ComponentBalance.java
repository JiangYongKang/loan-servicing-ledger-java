package com.github.highcumontoa.loanservicingledgerjava.domain.reconciliation;

import java.math.BigDecimal;

/**
 * 单个科目（PRINCIPAL/INTEREST/FEE/PENALTY）的应收侧对账平衡：
 * due(应收) == paid(已冲抵) + outstanding(未偿) + waived(减免)。
 */
public record ComponentBalance(
        String component,
        BigDecimal due,
        BigDecimal paid,
        BigDecimal outstanding,
        BigDecimal waived,
        BigDecimal difference) {

    /** 是否平衡（差额为 0）。 */
    public boolean balanced() {
        return difference.signum() == 0;
    }
}
