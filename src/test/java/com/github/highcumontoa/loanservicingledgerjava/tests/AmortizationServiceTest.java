package com.github.highcumontoa.loanservicingledgerjava.tests;

import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import com.github.highcumontoa.loanservicingledgerjava.domain.Money;
import com.github.highcumontoa.loanservicingledgerjava.service.AmortizationService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 摊销计划：等额本息可复现、本金守恒、零利率、尾差由末期吸收。
 */
class AmortizationServiceTest {

    private static final Logger log = LoggerFactory.getLogger(AmortizationServiceTest.class);

    private final AmortizationService svc = new AmortizationService();

    @Test
    void equalInstallmentScheduleIsReproducibleAndBalanced() {
        Money principal = Money.of("120000.00");
        BigDecimal annual = new BigDecimal("0.12");
        LocalDate firstDue = LocalDate.of(2026, 2, 28);

        List<Installment> a = svc.generateSchedule(principal, annual, 12, firstDue);
        List<Installment> b = svc.generateSchedule(principal, annual, 12, firstDue);

        log.info("输入 principal={} annualRate={} term=12 firstDue={}", principal, annual, firstDue);
        Money pmt = null;
        Money sumP = Money.ZERO;
        Money sumI = Money.ZERO;
        for (Installment i : a) {
            log.info("期次{} due={} 本金={} 利息={}", i.getPeriodNo(), i.getDueDate(),
                    i.getPrincipalDue(), i.getInterestDue());
            assertEquals(firstDue.plusMonths(i.getPeriodNo() - 1L), i.getDueDate());
            assertTrue(i.getPrincipalDue().isPositive(), "每期本金必须为正");
            Money total = i.getPrincipalDue().plus(i.getInterestDue());
            if (i.getPeriodNo() < 12) {
                if (pmt == null) {
                    pmt = total;
                }
                assertEquals(pmt, total, "非末期等额本息金额应一致");
            }
            sumP = sumP.plus(i.getPrincipalDue());
            sumI = sumI.plus(i.getInterestDue());
        }
        assertEquals(principal, sumP, "Σ本金必须等于发放本金");
        // 可复现：同输入同输出
        for (int k = 0; k < a.size(); k++) {
            assertEquals(a.get(k).getPrincipalDue(), b.get(k).getPrincipalDue());
            assertEquals(a.get(k).getInterestDue(), b.get(k).getInterestDue());
        }
        log.info("判定依据: Σ本金={} 等于发放本金; 计划逐期可复现; 总利息={}", sumP, sumI);
    }

    @Test
    void zeroRateScheduleSplitsPrincipalEvenly() {
        List<Installment> s = svc.generateSchedule(Money.of("1000.00"), BigDecimal.ZERO, 4,
                LocalDate.of(2026, 3, 31));
        Money sumP = Money.ZERO;
        for (Installment i : s) {
            log.info("零利率期次{} 本金={} 利息={}", i.getPeriodNo(), i.getPrincipalDue(), i.getInterestDue());
            assertEquals(Money.ZERO, i.getInterestDue());
            sumP = sumP.plus(i.getPrincipalDue());
        }
        assertEquals(Money.of("1000.00"), sumP);
    }
}
