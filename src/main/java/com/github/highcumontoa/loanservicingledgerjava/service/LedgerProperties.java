package com.github.highcumontoa.loanservicingledgerjava.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 账本默认规则配置（ledger.defaults.*）。
 * 仅在“发放”时作为缺省值使用并快照到贷款；事后修改不影响既有贷款。
 */
@Component
@ConfigurationProperties(prefix = "ledger.defaults")
public class LedgerProperties {

    /** 默认宽限期天数（自然日）。 */
    private int graceDays = 3;

    /** 默认日罚息率（如 0.0005 = 万分之五/日）。 */
    private BigDecimal dailyPenaltyRate = new BigDecimal("0.0005");

    public int defaultGraceDays() {
        return graceDays;
    }

    public BigDecimal defaultDailyPenaltyRate() {
        return dailyPenaltyRate;
    }

    public void setGraceDays(int graceDays) {
        this.graceDays = graceDays;
    }

    public void setDailyPenaltyRate(BigDecimal dailyPenaltyRate) {
        this.dailyPenaltyRate = dailyPenaltyRate;
    }
}
