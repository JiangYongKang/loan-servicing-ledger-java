package com.github.highcumontoa.loanservicingledgerjava.error;

/**
 * 可区分的失败原因。错误码稳定，对外（HTTP body 与异常）均以此为准。
 */
public enum ErrorCode {
    LOAN_NOT_FOUND,
    LOAN_ALREADY_EXISTS,
    INVALID_AMOUNT,
    INVALID_PARAMETER,
    PAYMENT_AMOUNT_NON_POSITIVE,
    PAYMENT_EXCEEDS_DUE,
    PAYOFF_AMOUNT_MISMATCH,
    LOAN_ALREADY_PAID_OFF,
    PAYMENT_DUPLICATED,
    PAYMENT_CONFLICT,
    ILLEGAL_STATE,
    SETTLEMENT_BEFORE_ORIGINATION,
    INTERNAL_CONSISTENCY
}
