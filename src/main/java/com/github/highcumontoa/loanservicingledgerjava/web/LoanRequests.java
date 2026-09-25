package com.github.highcumontoa.loanservicingledgerjava.web;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * HTTP 请求体（record，入参校验在控制器/服务层显式完成）。
 */
public final class LoanRequests {

    private LoanRequests() {}

    public record DisburseRequest(
            String loanId,
            String principal,
            BigDecimal annualRate,
            Integer termMonths,
            LocalDate originationDate,
            LocalDate firstDueDate,
            Integer graceDays,
            BigDecimal dailyPenaltyRate) {
    }

    public record PaymentRequest(
            String paymentId,
            String idempotencyKey,
            String amount,
            LocalDate valueDate,
            String type) {
    }
}
