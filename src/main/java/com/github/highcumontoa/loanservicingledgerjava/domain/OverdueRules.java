package com.github.highcumontoa.loanservicingledgerjava.domain;

import java.math.BigDecimal;

/**
 * 单笔贷款的逾期口径（宽限期天数 + 罚息日利率）。
 * 放款时确定并固化在贷款上，此后全局默认的调整不影响该贷款的解释。
 */
public record OverdueRules(int gracePeriodDays, BigDecimal penaltyDailyRate) {
}
