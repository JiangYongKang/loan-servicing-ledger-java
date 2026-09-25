package com.github.highcumontoa.loanservicingledgerjava.domain;

/** 贷款账户状态。 */
public enum LoanStatus {
    /** 已发放，正常还款中。 */
    ACTIVE,
    /** 已全部结清（含提前结清），不再产生新的应还。 */
    SETTLED
}
