package com.github.highcumontoa.loanservicingledgerjava.domain;

import java.math.BigDecimal;

/** 一笔还款在某一期上的资金分配结果（冲抵顺序：费用 -> 罚息 -> 利息 -> 本金）。 */
public record PaymentAllocation(
        int period,
        BigDecimal feeApplied,
        BigDecimal penaltyApplied,
        BigDecimal interestApplied,
        BigDecimal principalApplied) {

    public BigDecimal total() {
        return feeApplied.add(penaltyApplied).add(interestApplied).add(principalApplied);
    }
}
