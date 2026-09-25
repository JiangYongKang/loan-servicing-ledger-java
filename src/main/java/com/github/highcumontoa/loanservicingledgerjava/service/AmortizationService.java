package com.github.highcumontoa.loanservicingledgerjava.service;

import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import com.github.highcumontoa.loanservicingledgerjava.domain.Money;
import com.github.highcumontoa.loanservicingledgerjava.error.ErrorCode;
import com.github.highcumontoa.loanservicingledgerjava.error.LedgerException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 摊销计划生成：等额本息。
 *
 * <p>口径（固定、可复现）：
 * <ol>
 *   <li>月利率 r = 年利率 / 12（内部高精度 10 位）；</li>
 *   <li>每期应还 PMT = P·r·(1+r)^n / ((1+r)^n − 1)，2 位 HALF_UP；r=0 时 PMT=P/n；</li>
 *   <li>每期利息 = 期初剩余本金 × r（2 位 HALF_UP），本金 = PMT − 利息；</li>
 *   <li>末期本金 = 剩余全部本金（吸收尾差），保证 Σ本金 = 发放本金、不出现负数；</li>
 *   <li>到期日 = firstDueDate 按月顺延。</li>
 * </ol>
 * 相同输入必然得到相同计划。
 */
@Service
public class AmortizationService {

    private static final int CALC_SCALE = 10;

    public List<Installment> generateSchedule(Money principal, BigDecimal annualRate,
                                              int termMonths, LocalDate firstDueDate) {
        if (principal == null || !principal.isPositive()) {
            throw new LedgerException(ErrorCode.INVALID_AMOUNT, "发放本金必须为正数");
        }
        if (termMonths < 1) {
            throw new LedgerException(ErrorCode.INVALID_PARAMETER, "期数必须 >= 1");
        }
        if (annualRate == null || annualRate.signum() < 0) {
            throw new LedgerException(ErrorCode.INVALID_PARAMETER, "年利率不能为空且不能为负");
        }
        if (firstDueDate == null) {
            throw new LedgerException(ErrorCode.INVALID_PARAMETER, "首期到期日不能为空");
        }

        BigDecimal r = annualRate.divide(BigDecimal.valueOf(12), CALC_SCALE, RoundingMode.HALF_UP);
        List<Installment> schedule = new ArrayList<>(termMonths);

        Money balance = principal;
        if (r.signum() == 0) {
            Money flat = principal.multiplyScale4(BigDecimal.ONE.divide(
                    BigDecimal.valueOf(termMonths), CALC_SCALE, RoundingMode.HALF_UP));
            for (int i = 1; i <= termMonths; i++) {
                Money p = (i == termMonths) ? balance : flat;
                schedule.add(new Installment(i, firstDueDate.plusMonths(i - 1L), p, Money.ZERO));
                balance = balance.minus(p);
            }
            return schedule;
        }

        // PMT = P * r * (1+r)^n / ((1+r)^n - 1)
        BigDecimal pow = BigDecimal.ONE.add(r).pow(termMonths);
        BigDecimal pmt = principal.value()
                .multiply(r)
                .multiply(pow)
                .divide(pow.subtract(BigDecimal.ONE), 2, RoundingMode.HALF_UP);

        for (int i = 1; i <= termMonths; i++) {
            Money interest = Money.of(balance.value()
                    .multiply(r).setScale(2, RoundingMode.HALF_UP));
            Money p;
            if (i == termMonths) {
                // 末期吸收全部尾差，本金之和严格等于发放本金
                p = balance;
            } else {
                p = Money.of(pmt.subtract(interest.value()));
                if (!p.isPositive()) {
                    throw new LedgerException(ErrorCode.INVALID_PARAMETER,
                            "摊销计划出现非正本金，请检查利率与期数");
                }
                if (p.gt(balance)) {
                    throw new LedgerException(ErrorCode.INTERNAL_CONSISTENCY, "摊销本金超过剩余本金");
                }
            }
            schedule.add(new Installment(i, firstDueDate.plusMonths(i - 1L), p, interest));
            balance = balance.minus(p);
        }

        if (!balance.isZero()) {
            throw new LedgerException(ErrorCode.INTERNAL_CONSISTENCY,
                    "摊销计划本金不平衡，剩余: " + balance);
        }
        return schedule;
    }
}
