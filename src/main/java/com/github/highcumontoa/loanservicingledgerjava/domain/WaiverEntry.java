package com.github.highcumontoa.loanservicingledgerjava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 一条逐期、逐项的减免记录（提前结清时产生）。
 * 记录哪笔结清流水(paymentId)减免了哪一期(period)的多少利息/费用/罚息。
 */
public record WaiverEntry(
        String paymentId,
        int period,
        LocalDate valueDate,
        BigDecimal interestWaived,
        BigDecimal feeWaived,
        BigDecimal penaltyWaived) {

    public BigDecimal total() {
        return interestWaived.add(feeWaived).add(penaltyWaived);
    }
}
