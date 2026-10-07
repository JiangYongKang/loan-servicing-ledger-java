package com.github.highcumontoa.loanservicingledgerjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.highcumontoa.loanservicingledgerjava.config.LedgerProperties;
import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import com.github.highcumontoa.loanservicingledgerjava.domain.InstallmentStatus;
import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.service.AmortizationCalculator;
import com.github.highcumontoa.loanservicingledgerjava.service.LoanLedgerService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单笔贷款逾期口径在放款时固化：
 * 改完全局默认后同一结算日复算结果不变；同一结算日重复/乱序结算不重复计罚。
 */
class PerLoanRulesFreezeTest {

    private static final Logger log = LoggerFactory.getLogger(PerLoanRulesFreezeTest.class);
    private static final LocalDate DISBURSE_DATE = LocalDate.of(2026, 1, 1);

    private LoanLedgerService serviceWithDefaults() {
        return new LoanLedgerService(new AmortizationCalculator(), new LedgerProperties());
    }

    @Test
    void customRulesAreFrozenAtDisbursementAndIgnoredByGlobalChange() {
        // 全局默认: 宽限3天, 日利率0.0005；本笔单独指定: 宽限1天, 日利率0.001
        LoanLedgerService svc = serviceWithDefaults();
        svc.disburse("CUSTOM", new BigDecimal("1200"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, BigDecimal.ZERO, 1, new BigDecimal("0.001"));
        log.info("[输入] 放款 CUSTOM: 单笔覆盖 宽限=1天 罚息日利率=0.001 (全局=3天/0.0005)");

        // 放款后再把全局默认改成完全不同的一套
        svc = mutateGlobals(svc, 10, new BigDecimal("0.005"));

        Installment first = svc.getLoan("CUSTOM").getSchedule().get(0);
        // 到期 2026-02-01, 宽限1天 -> graceEnd=02-02; 02-04 对应区间(02-02,02-04]=2天
        svc.accrueOverdue("CUSTOM", LocalDate.of(2026, 2, 4));
        BigDecimal base = first.getPrincipalDue().add(first.getInterestDue());
        BigDecimal expected = base.multiply(new BigDecimal("0.001"))
                .multiply(BigDecimal.valueOf(2)).setScale(2, RoundingMode.HALF_UP);
        log.info("[判定依据] 按固化规则 宽限1天: 区间(02-02,02-04]逾期2天, 基数={} * 0.001 * 2 = {}",
                base, expected);
        assertEquals(InstallmentStatus.OVERDUE, first.getStatus());
        assertEquals(expected, first.getPenaltyAccrued());
        assertEquals(1, svc.getLoan("CUSTOM").getRules().getGracePeriodDays());
        assertEquals(new BigDecimal("0.00100000"),
                svc.getLoan("CUSTOM").getRules().getPenaltyDailyRate());

        // 全局新口径(宽限10天)若生效, 02-04 根本不该逾期；这里必须仍逾期 -> 证明用的是固化值
        svc.accrueOverdue("CUSTOM", LocalDate.of(2026, 2, 4));
        assertEquals(InstallmentStatus.OVERDUE, first.getStatus());
    }

    @Test
    void globalDefaultSnapshotAtDisbursementSurvivesLaterGlobalChange() {
        // 老贷款没单独指定规则：沿用放款当时的全局默认（3天/0.0005）并固化
        LoanLedgerService svc = serviceWithDefaults();
        svc.disburse("OLD", new BigDecimal("1200"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, BigDecimal.ZERO);
        log.info("[输入] 放款 OLD(未指定规则): 固化当时全局默认 宽限=3天 日利率=0.0005");

        // 先按 02-06 结算一次（旧口径下逾期2天）
        svc.accrueOverdue("OLD", LocalDate.of(2026, 2, 6));
        BigDecimal beforeChange = svc.getLoan("OLD").getSchedule().get(0).getPenaltyAccrued();

        // 放款与首次结算之后，才修改全局默认
        svc = mutateGlobals(svc, 20, new BigDecimal("0.009"));

        // 拿同一个结算日重新结算：结果必须一分不差
        svc.accrueOverdue("OLD", LocalDate.of(2026, 2, 6));
        BigDecimal afterChange = svc.getLoan("OLD").getSchedule().get(0).getPenaltyAccrued();
        log.info("[判定依据] 全局改为 宽限20天/0.009 后同一结算日02-06复算: 改前={} 改后={}",
                beforeChange, afterChange);
        assertEquals(beforeChange, afterChange);
        assertEquals(3, svc.getLoan("OLD").getRules().getGracePeriodDays());

        // 用手算的旧口径值锁定: (02-04, 02-06] = 2 天
        Installment first = svc.getLoan("OLD").getSchedule().get(0);
        BigDecimal base = first.getPrincipalDue().add(first.getInterestDue());
        BigDecimal expected = base.multiply(new BigDecimal("0.0005"))
                .multiply(BigDecimal.valueOf(2)).setScale(2, RoundingMode.HALF_UP);
        assertEquals(expected, afterChange);
    }

    @Test
    void repeatedAndOutOfOrderSettlementNeverDoubleAccrues() {
        LoanLedgerService svc = serviceWithDefaults();
        svc.disburse("OLD", new BigDecimal("1200"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, BigDecimal.ZERO);

        LocalDate d = LocalDate.of(2026, 2, 10);
        svc.accrueOverdue("OLD", d);
        BigDecimal once = svc.getLoan("OLD").getSchedule().get(0).getPenaltyAccrued();

        // 同一结算日重复结算多次
        svc.accrueOverdue("OLD", d);
        svc.accrueOverdue("OLD", d);
        // 补跑更早日期、再乱序补跑，都不改变既有计提
        svc.accrueOverdue("OLD", LocalDate.of(2026, 2, 5));
        svc.accrueOverdue("OLD", LocalDate.of(2026, 2, 8));
        svc.accrueOverdue("OLD", LocalDate.of(2026, 2, 6));
        BigDecimal afterChaos = svc.getLoan("OLD").getSchedule().get(0).getPenaltyAccrued();
        log.info("[输入] 02-10结算后重复2次+乱序补跑02-05/08/06; [结果] 一次计提={} 乱序后={}",
                once, afterChaos);
        assertEquals(once, afterChaos);
        assertEquals(d, svc.getLoan("OLD").getSchedule().get(0).getPenaltyAccruedThrough());

        // 再顺跑到 02-12：只补 (02-10,02-12] 2 天，不重头计
        svc.accrueOverdue("OLD", LocalDate.of(2026, 2, 12));
        Installment first = svc.getLoan("OLD").getSchedule().get(0);
        BigDecimal base = first.getPrincipalDue().add(first.getInterestDue());
        BigDecimal twoMore = base.multiply(new BigDecimal("0.0005"))
                .multiply(BigDecimal.valueOf(2)).setScale(2, RoundingMode.HALF_UP);
        log.info("[判定依据] 续跑到02-12只补2天={}, 合计={}", twoMore, first.getPenaltyAccrued());
        assertEquals(once.add(twoMore), first.getPenaltyAccrued());
    }

    /** 通过反射替换服务内 LedgerProperties（模拟运维修改全局默认），返回同一服务。 */
    private LoanLedgerService mutateGlobals(LoanLedgerService svc, int grace, BigDecimal rate) {
        try {
            java.lang.reflect.Field f = LoanLedgerService.class.getDeclaredField("properties");
            f.setAccessible(true);
            LedgerProperties p = (LedgerProperties) f.get(svc);
            p.setGracePeriodDays(grace);
            p.setPenaltyDailyRate(rate);
            log.info("[输入] 全局默认被修改为 宽限={}天 罚息日利率={}", grace, rate);
            return svc;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
