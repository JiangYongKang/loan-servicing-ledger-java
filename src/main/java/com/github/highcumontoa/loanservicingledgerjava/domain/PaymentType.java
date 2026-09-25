package com.github.highcumontoa.loanservicingledgerjava.domain;

/**
 * 还款类型：NORMAL 按期/部分还款；EARLY_PAYOFF 提前结清（必须随逐期报价金额入账）。
 */
public enum PaymentType {
    NORMAL,
    EARLY_PAYOFF
}
