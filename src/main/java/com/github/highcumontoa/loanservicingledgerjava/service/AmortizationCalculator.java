package com.github.highcumontoa.loanservicingledgerjava.service;

import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 等额本息摊销计划计算器：同样的输入永远得到同样的计划（可复现）。
 *
 * <p>模型：月利率 r = 年利率 / 12；月供 A = P·r·(1+r)^n / ((1+r)^n − 1)，
 * 每期利息 = 剩余本金 × r，本金 = A − 利息；最后一期本金取剩余本金，
 * 吸收累计舍入差。所有金额 scale=2，HALF_UP。
 */
@Component
public class AmortizationCalculator {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal TWELVE = new BigDecimal("12");

    public List<Installment> generateSchedule(BigDecimal principal, BigDecimal annualRate,
                                              int termMonths, LocalDate disbursementDate,
                                              BigDecimal monthlyFee) {
        BigDecimal p = principal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal r = annualRate.divide(TWELVE, MC);
        BigDecimal fee = monthlyFee.setScale(2, RoundingMode.HALF_UP);

        BigDecimal monthlyPayment = annuityPayment(p, r, termMonths);

        List<Installment> schedule = new ArrayList<>(termMonths);
        BigDecimal remaining = p;
        for (int i = 1; i <= termMonths; i++) {
            BigDecimal interest = remaining.multiply(r).setScale(2, RoundingMode.HALF_UP);
            BigDecimal principalPart;
            if (i == termMonths) {
                // 最后一期吸收全部舍入差，保证本金合计 == 放款本金
                principalPart = remaining;
            } else {
                principalPart = monthlyPayment.subtract(interest).setScale(2, RoundingMode.HALF_UP);
            }
            remaining = remaining.subtract(principalPart);
            schedule.add(new Installment(i, disbursementDate.plusMonths(i),
                    principalPart, interest, fee));
        }
        return schedule;
    }

    private BigDecimal annuityPayment(BigDecimal p, BigDecimal r, int n) {
        if (r.signum() == 0) {
            return p.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
        }
        BigDecimal factor = BigDecimal.ONE.add(r).pow(n, MC);
        // A = P·r·(1+r)^n / ((1+r)^n − 1)
        return p.multiply(r).multiply(factor)
                .divide(factor.subtract(BigDecimal.ONE), 2, RoundingMode.HALF_UP);
    }
}
