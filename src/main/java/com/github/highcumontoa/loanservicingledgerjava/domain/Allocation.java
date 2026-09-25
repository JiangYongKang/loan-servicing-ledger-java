package com.github.highcumontoa.loanservicingledgerjava.domain;

import java.util.List;

/**
 * 一笔还款的资金分配结果（可解释）：每一期分别冲抵多少罚息/利息/本金。
 * 各明细之和必须等于入账金额；各期被冲抵项不得超过其应收余额。
 */
public record Allocation(Money totalPaid, List<AllocationEntry> entries) {

    /** 单期冲抵明细。 */
    public record AllocationEntry(
            int periodNo,
            Money penaltyPaid,
            Money interestPaid,
            Money principalPaid) {
    }
}
