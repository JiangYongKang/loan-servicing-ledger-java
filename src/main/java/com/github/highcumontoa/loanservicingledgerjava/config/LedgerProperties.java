package com.github.highcumontoa.loanservicingledgerjava.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 可配置项：宽限期天数、罚息日利率、每期固定费用默认值。 */
@ConfigurationProperties(prefix = "ledger")
public class LedgerProperties {

    /** 宽限期天数：到期日后该天数内不计罚息、不判逾期。 */
    private int gracePeriodDays = 3;

    /** 罚息日利率（对逾期未还本金+利息计提），如 0.0005 表示万五/日。 */
    private BigDecimal penaltyDailyRate = new BigDecimal("0.0005");

    /** 每期固定费用（服务费），默认 0。 */
    private BigDecimal defaultMonthlyFee = BigDecimal.ZERO;

    public int getGracePeriodDays() { return gracePeriodDays; }
    public void setGracePeriodDays(int v) { this.gracePeriodDays = v; }
    public BigDecimal getPenaltyDailyRate() { return penaltyDailyRate; }
    public void setPenaltyDailyRate(BigDecimal v) { this.penaltyDailyRate = v; }
    public BigDecimal getDefaultMonthlyFee() { return defaultMonthlyFee; }
    public void setDefaultMonthlyFee(BigDecimal v) { this.defaultMonthlyFee = v; }
}
