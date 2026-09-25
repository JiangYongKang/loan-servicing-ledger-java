package com.github.highcumontoa.loanservicingledgerjava.error;

/** 可区分的失败原因码。 */
public enum LedgerErrorCode {
    LOAN_NOT_FOUND,
    INVALID_AMOUNT,
    LOAN_NOT_ACTIVE,
    OVERPAYMENT,
    DUPLICATE_PAYMENT,
    CONCURRENT_CONFLICT,
    INVALID_DISBURSEMENT
}
