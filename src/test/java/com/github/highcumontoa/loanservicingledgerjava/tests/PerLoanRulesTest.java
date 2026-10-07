package com.github.highcumontoa.loanservicingledgerjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.highcumontoa.loanservicingledgerjava.config.LedgerProperties;
import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import com.github.highcumontoa.loanservicingledgerjava.domain.InstallmentStatus;
import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.service.AmortizationCalculator;
import com.github.highcumontoa.loanservicingledgerjava.service.LoanLedgerService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 单笔贷款逾期口径：放款时定制/固化，全局默认变更不影响已放贷款。 */
class PerLoanRulesTest {

    private static final Logger log = LoggerFactory.getLogger(PerLoanRulesTest.class);
    private static final LocalDate DISBURSE_DATE = LocalDate.of(2026, 1, 1);

    private LedgerProperties props;
    private LoanLedgerService service;

    @BeforeEach
    void setUp() {
        props = new LedgerProperties(); // 全局默认: 宽限3天, 罚息0.0005/日
        service = new LoanLedgerService(new AmortizationCalculator(), props);
    }

    private Installment first(String loanId) {
        return service.getLoan(loanId).getSchedule().get(0);
    }

    @Test
    void perLoanOverrideIsUsedInsteadOfGlobalDefault() {
        // L-CUSTOM 单独指定: 宽限10天, 罚息0.001/日; L-DEFAULT 不指定, 沿用全局默认
        service.disburse("L-CUSTOM", new BigDecimal("1200"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, BigDecimal.ZERO, 10, new BigDecimal("0.001"));
        service.disburse("L-DEFAULT", new BigDecimal("1200"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, BigDecimal.ZERO);

        Loan custom = service.getLoan("L-CUSTOM");
        Loan dflt = service.getLoan("L-DEFAULT");
        log.info("[输入] L-CUSTOM 放款指定 grace=10, rate=0.001; L-DEFAULT 不指定");
        log.info("[固化规则] L-CUSTOM grace={} rate={}; L-DEFAULT grace={} rate={}",
                custom.getOverdueRules().gracePeriodDays(), custom.getOverdueRules().penaltyDailyRate(),
                dflt.getOverdueRules().gracePeriodDays(), dflt.getOverdueRules().penaltyDailyRate());
        assertEquals(10, custom.getOverdueRules().gracePeriodDays());
        assertEquals(new BigDecimal("0.001"), custom.getOverdueRules().penaltyDailyRate());
        assertEquals(3, dflt.getOverdueRules().gracePeriodDays());
        assertEquals(new BigDecimal("0.0005"), dflt.getOverdueRules().penaltyDailyRate());

        // 首期到期 2026-02-01: L-DEFAULT 宽限到 02-04, L-CUSTOM 宽限到 02-11
        service.accrueOverdue("L-CUSTOM", LocalDate.of(2026, 2, 6));
        service.accrueOverdue("L-DEFAULT", LocalDate.of(2026, 2, 6));
        log.info("[状态] asOf=2026-02-06: L-CUSTOM={} 罚息={}; L-DEFAULT={} 罚息={}",
                first("L-CUSTOM").getStatus(), first("L-CUSTOM").getPenaltyAccrued(),
                first("L-DEFAULT").getStatus(), first("L-DEFAULT").getPenaltyAccrued());
        assertEquals(InstallmentStatus.PENDING, first("L-CUSTOM").getStatus()); // 仍在定制宽限期内
        assertEquals(new BigDecimal("0.00"), first("L-CUSTOM").getPenaltyAccrued());
        assertEquals(InstallmentStatus.OVERDUE, first("L-DEFAULT").getStatus());

        // L-CUSTOM 到 02-13 逾期 2 天, 按 0.001/日 计罚
        service.accrueOverdue("L-CUSTOM", LocalDate.of(2026, 2, 13));
        BigDecimal base = first("L-CUSTOM").getPrincipalDue().add(first("L-CUSTOM").getInterestDue());
        BigDecimal expected = base.multiply(new BigDecimal("0.001"))
                .multiply(BigDecimal.valueOf(2)).setScale(2, java.math.RoundingMode.HALF_UP);
        log.info("[判定依据] L-CUSTOM 宽限10天到02-11, 02-13逾期2天, 罚息={}*0.001*2={}",
                base, expected);
        assertEquals(expected, first("L-CUSTOM").getPenaltyAccrued());
    }

    @Test
    void changingGlobalDefaultAfterDisburseDoesNotChangeResult() {
        // 放款时不指定 -> 快照放款当时的全局默认
        service.disburse("L1", new BigDecimal("1200"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, BigDecimal.ZERO);
        LocalDate asOf = LocalDate.of(2026, 2, 10);

        service.accrueOverdue("L1", asOf);
        BigDecimal penaltyBefore = first("L1").getPenaltyAccrued();
        InstallmentStatus statusBefore = first("L1").getStatus();
        log.info("[输入] 放款后按 asOf={} 结算: 状态={} 罚息={}", asOf, statusBefore, penaltyBefore);

        // 改掉全局默认, 用另一个贷款验证新默认确实生效
        props.setGracePeriodDays(30);
        props.setPenaltyDailyRate(new BigDecimal("0.005"));
        service.disburse("L2", new BigDecimal("1200"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, BigDecimal.ZERO);
        assertEquals(30, service.getLoan("L2").getOverdueRules().gracePeriodDays());

        // 同一结算日重新结算 L1: 结果必须和改之前一分不差
        service.accrueOverdue("L1", asOf);
        log.info("[判定依据] 全局默认改为 grace=30/rate=0.005 后, L1 同结算日重算: 罚息 {} -> {}",
                penaltyBefore, first("L1").getPenaltyAccrued());
        assertEquals(penaltyBefore, first("L1").getPenaltyAccrued());
        assertEquals(statusBefore, first("L1").getStatus());
        // 补跑更早/更晚日期也不因规则变更而改口径: 02-20 仍按旧规则(宽限3天,万五)累计
        service.accrueOverdue("L1", LocalDate.of(2026, 2, 20));
        BigDecimal base = first("L1").getPrincipalDue().add(first("L1").getInterestDue());
        BigDecimal tenMoreDays = base.multiply(new BigDecimal("0.0005"))
                .multiply(BigDecimal.valueOf(10)).setScale(2, java.math.RoundingMode.HALF_UP);
        assertEquals(penaltyBefore.add(tenMoreDays), first("L1").getPenaltyAccrued());
        log.info("[判定依据] 补跑02-20: 仍按固化规则万五/日补提10天={}, 合计={}",
                tenMoreDays, first("L1").getPenaltyAccrued());
    }

    @Test
    void repeatedAndOutOfOrderAccrualDoesNotDoubleCount() {
        service.disburse("L1", new BigDecimal("1200"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, BigDecimal.ZERO, 5, new BigDecimal("0.0008"));

        service.accrueOverdue("L1", LocalDate.of(2026, 2, 10));
        BigDecimal afterFirst = first("L1").getPenaltyAccrued();
        // 同一结算日重复结算 + 补跑更早日期: 不重复计提
        service.accrueOverdue("L1", LocalDate.of(2026, 2, 10));
        service.accrueOverdue("L1", LocalDate.of(2026, 2, 8));
        service.accrueOverdue("L1", LocalDate.of(2026, 2, 10));
        assertEquals(afterFirst, first("L1").getPenaltyAccrued());

        // 补跑更晚日期: 只增量计提差额天数
        service.accrueOverdue("L1", LocalDate.of(2026, 2, 15));
        BigDecimal base = first("L1").getPrincipalDue().add(first("L1").getInterestDue());
        BigDecimal fiveDays = base.multiply(new BigDecimal("0.0008"))
                .multiply(BigDecimal.valueOf(5)).setScale(2, java.math.RoundingMode.HALF_UP);
        log.info("[判定依据] 重复/回退结算幂等; 02-10->02-15 只补5天={}, 合计={}",
                fiveDays, first("L1").getPenaltyAccrued());
        assertEquals(afterFirst.add(fiveDays), first("L1").getPenaltyAccrued());
    }
}
