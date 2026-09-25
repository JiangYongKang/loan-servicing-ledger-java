package com.github.highcumontoa.loanservicingledgerjava.domain;

/**
 * 贷款生命周期状态。
 * ACTIVE：未结清（可能含逾期期次）；PAID_OFF：已结清（不可再还款、不再计息、不再逾期）。
 */
public enum LoanStatus {
    ACTIVE,
    PAID_OFF
}
