package com.github.highcumontoa.loanservicingledgerjava.domain;

/**
 * 期次状态：PENDING 未到期/PARTIAL 部分还款/PAID 当期已结清。
 * 逾期不单独设状态：逾期是某结算时点基于到期日+宽限期的派生判定。
 */
public enum InstallmentStatus {
    PENDING,
    PARTIAL,
    PAID
}
