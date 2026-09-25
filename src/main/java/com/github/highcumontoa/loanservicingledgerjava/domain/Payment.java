package com.github.highcumontoa.loanservicingledgerjava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 一笔已入账的还款流水（幂等键唯一）。 */
public record Payment(
        String paymentId,
        String idempotencyKey,
        String loanId,
        BigDecimal amount,
        LocalDate valueDate,
        List<PaymentAllocation> allocations) {

    public BigDecimal allocatedTotal() {
        return allocations.stream()
                .map(PaymentAllocation::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
