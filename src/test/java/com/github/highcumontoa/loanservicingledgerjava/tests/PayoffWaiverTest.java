package com.github.highcumontoa.loanservicingledgerjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.highcumontoa.loanservicingledgerjava.config.LedgerProperties;
import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import com.github.highcumontoa.loanservicingledgerjava.domain.LoanStatus;
import com.github.highcumontoa.loanservicingledgerjava.domain.Payment;
import com.github.highcumontoa.loanservicingledgerjava.domain.WaiverEntry;
import com.github.highcumontoa.loanservicingledgerjava.service.AmortizationCalculator;
import com.github.highcumontoa.loanservicingledgerjava.service.LoanLedgerService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 提前结清减免明细与"结得干净"的测试。 */
class PayoffWaiverTest {

    private static final Logger log = LoggerFactory.getLogger(PayoffWaiverTest.class);
    private static final LocalDate DISBURSE_DATE = LocalDate.of(2026, 1, 1);

    private LoanLedgerService newService() {
        return new LoanLedgerService(new AmortizationCalculator(), new LedgerProperties());
    }

    @Test
    void payoffRecordsPerPeriodPerItemWaiversAndZeroesAllOutstanding() {
        LoanLedgerService svc = newService();
        // 本金12000 年息12% 12期 每期费用10；先正常还第1期
        svc.disburse("L1", new BigDecimal("12000"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, new BigDecimal("10"));
        Installment first = svc.getLoan("L1").getSchedule().get(0);
        svc.repay("L1", "K1", first.totalRemaining(), LocalDate.of(2026, 1, 15));

        // 第2期处于宽限期内(到期02-01 + 3 = 02-04), 01-25 结清, 后续期次均未到期
        Payment payoff = svc.payoff("L1", "K-PAYOFF", LocalDate.of(2026, 1, 25));
        log.info("[输入] 结清 valueDate=2026-01-25, 结清金额={}", payoff.amount());

        var loan = svc.getLoan("L1");
        assertEquals(LoanStatus.SETTLED, loan.getStatus());
        // 所有期次未偿合计 = 0（费用/罚息/利息/本金逐项）
        BigDecimal allRemaining = BigDecimal.ZERO;
        for (Installment i : loan.getSchedule()) {
            allRemaining = allRemaining.add(i.totalRemaining());
            assertEquals(new BigDecimal("0.00"), i.remainingFee(), "period " + i.getPeriod() + " fee");
            assertEquals(new BigDecimal("0.00"), i.remainingInterest(), "period " + i.getPeriod() + " interest");
            assertEquals(new BigDecimal("0.00"), i.remainingPenalty(), "period " + i.getPeriod() + " penalty");
            assertEquals(new BigDecimal("0.00"), i.remainingPrincipal(), "period " + i.getPeriod() + " principal");
        }
        assertEquals(new BigDecimal("0.00"), allRemaining);
        assertEquals(new BigDecimal("0.00"), loan.getOutstandingPrincipal());

        // 减免明细逐期逐项可查：第2..12期每期利息 + 每期费用10 被减免
        Map<Integer, WaiverEntry> byPeriod = loan.getWaivers().stream()
                .collect(Collectors.toMap(WaiverEntry::period, Function.identity()));
        BigDecimal waivedInterestSum = BigDecimal.ZERO;
        BigDecimal waivedFeeSum = BigDecimal.ZERO;
        for (int period = 2; period <= 12; period++) {
            WaiverEntry w = byPeriod.get(period);
            Installment inst = loan.getSchedule().get(period - 1);
            assertEquals(inst.getInterestDue(), w.interestWaived(), "period " + period + " waived interest");
            assertEquals(new BigDecimal("10.00"), w.feeWaived(), "period " + period + " waived fee");
            assertEquals(new BigDecimal("0.00"), w.penaltyWaived());
            assertEquals(payoff.paymentId(), w.paymentId());
            waivedInterestSum = waivedInterestSum.add(w.interestWaived());
            waivedFeeSum = waivedFeeSum.add(w.feeWaived());
            log.info("[减免明细] 期次={} 利息减免={} 费用减免={} 罚息减免={}",
                    period, w.interestWaived(), w.feeWaived(), w.penaltyWaived());
        }
        // 第1期已正常还清，无减免记录
        assertTrue(byPeriod.get(1) == null);
        log.info("[判定依据] 11期未到期逐期减免 利息合计={} 费用合计={}; 本金不减免全部冲抵",
                waivedInterestSum, waivedFeeSum);
    }

    @Test
    void payoffAfterOverdueWaivesNothingOnPaidDueChargesAndZeroesRest() {
        LoanLedgerService svc = newService();
        svc.disburse("L2", new BigDecimal("1200"), new BigDecimal("0.12"),
                6, DISBURSE_DATE, new BigDecimal("5"));
        // 逾期到 02-10：第1期已到期、产生罚息
        Payment payoff = svc.payoff("L2", "K-PAYOFF", LocalDate.of(2026, 2, 10));
        var loan = svc.getLoan("L2");
        Installment i1 = loan.getSchedule().get(0);
        log.info("[输入] 逾期后结清 valueDate=2026-02-10; 期1罚息计提={} 减免={}",
                i1.getPenaltyAccrued(), i1.getPenaltyWaived());

        // 已到期罚息由结清款冲抵，不做减免
        assertEquals(new BigDecimal("0.00"), i1.getPenaltyWaived());
        assertEquals(i1.getPenaltyAccrued(), i1.getPenaltyPaid());
        // 全部期次未偿归零
        for (Installment i : loan.getSchedule()) {
            assertEquals(new BigDecimal("0.00"), i.totalRemaining());
        }
        // 减免明细仍可查（第2..6期未到期利息/费用）
        long futureWaivers = loan.getWaivers().stream()
                .filter(w -> w.period() >= 2 && w.interestWaived().signum() > 0)
                .count();
        assertEquals(5, futureWaivers);
        log.info("[资金分配] 结清金额={}; [判定依据] 到期罚息现金冲抵不减免, 未到期5期利息费用减免",
                payoff.amount());
    }
}
