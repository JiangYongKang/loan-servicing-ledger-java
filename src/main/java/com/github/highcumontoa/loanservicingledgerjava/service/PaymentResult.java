package com.github.highcumontoa.loanservicingledgerjava.service;

import com.github.highcumontoa.loanservicingledgerjava.domain.Allocation;
import com.github.highcumontoa.loanservicingledgerjava.domain.Payment;

/**
 * 还款结果：流水 + 资金分配明细。
 */
public record PaymentResult(Payment payment, Allocation allocation, boolean idempotentReplay) {
}
