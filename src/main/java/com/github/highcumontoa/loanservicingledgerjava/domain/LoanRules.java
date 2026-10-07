package com.github.highcumontoa.loanservicingledgerjava.domain;

import java.math.BigDecimal;

/**
 * 单笔贷款的逾期口径：宽限期天数与罚息日利率。
 *
 * <p>放款时固化到贷款聚合上：单笔指定则用指定值，未指定则沿用当时的全局默认。
 * 此后该贷款所有的逾期判定与罚息计提都以这里固化的值为准，
 * 全局默认（LedgerProperties）后续变化不再影响该贷款。
 */
public final class LoanRules {

    private final int gracePeriodDays;
    private final BigDecimal penaltyDailyRate;
    /** 宽限期是否由本次放款单独指定（false = 沿用放款当时的全局默认）。 */
    private final boolean customGracePeriod;
    /** 罚息日利率是否由本次放款单独指定。 */
    private final boolean customPenaltyRate;

    public LoanRules(int gracePeriodDays, BigDecimal penaltyDailyRate,
                     boolean customGracePeriod, boolean customPenaltyRate) {
        this.gracePeriodDays = gracePeriodDays;
        this.penaltyDailyRate = penaltyDailyRate;
        this.customGracePeriod = customGracePeriod;
        this.customPenaltyRate = customPenaltyRate;
    }

    public int getGracePeriodDays() { return gracePeriodDays; }
    public BigDecimal getPenaltyDailyRate() { return penaltyDailyRate; }
    public boolean isCustomGracePeriod() { return customGracePeriod; }
    public boolean isCustomPenaltyRate() { return customPenaltyRate; }
}
