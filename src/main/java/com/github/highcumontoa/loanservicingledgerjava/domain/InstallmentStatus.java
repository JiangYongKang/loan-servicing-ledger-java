package com.github.highcumontoa.loanservicingledgerjava.domain;

/** 单期摊销计划的状态。 */
public enum InstallmentStatus {
    /** 未到结算日或未出账。 */
    PENDING,
    /** 已部分冲抵。 */
    PARTIALLY_PAID,
    /** 已全额冲抵。 */
    PAID,
    /** 超过结算日（含宽限期）仍未足额。 */
    OVERDUE
}
