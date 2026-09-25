package com.github.highcumontoa.loanservicingledgerjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.highcumontoa.loanservicingledgerjava.config.LedgerProperties;
import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import com.github.highcumontoa.loanservicingledgerjava.domain.InstallmentStatus;
import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.domain.LoanStatus;
import com.github.highcumontoa.loanservicingledgerjava.error.LedgerErrorCode;
import com.github.highcumontoa.loanservicingledgerjava.error.LedgerException;
import com.github.highcumontoa.loanservicingledgerjava.service.AmortizationCalculator;
import com.github.highcumontoa.loanservicingledgerjava.service.LoanLedgerService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 逾期判定、宽限期与罚息累计的状态流转测试。 */
class OverduePenaltyTest {

    private static final Logger log = LoggerFactory.getLogger(OverduePenaltyTest.class);
    private static final LocalDate DISBURSE_DATE = LocalDate.of(2026, 1, 1);

    private LoanLedgerService service;

    @BeforeEach
    void setUp() {
        service = new LoanLedgerService(new AmortizationCalculator(), new LedgerProperties());
        // 本金 1200, 年利率 12% (月息1%), 12期, 无费用 -> 月供约 106.62, 首期利息 12.00
        service.disburse("L1", new BigDecimal("1200"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, BigDecimal.ZERO);
    }

    private Installment first() {
        return service.getLoan("L1").getSchedule().get(0);
    }

    @Test
    void withinGracePeriodNoPenaltyNoOverdue() {
        // 首期到期 2026-02-01, 宽限 3 天 -> 2026-02-04 及以前不判逾期
        service.accrueOverdue("L1", LocalDate.of(2026, 2, 4));
        log.info("[输入] asOf=2026-02-04 (宽限期内); [状态] {}", first().getStatus());
        assertEquals(InstallmentStatus.PENDING, first().getStatus());
        assertEquals(new BigDecimal("0.00"), first().getPenaltyAccrued());
        log.info("[判定依据] 到期日2026-02-01 + 宽限3天 = 2026-02-04, asOf 未超过 -> 不逾期不计罚");
    }

    @Test
    void overdueAfterGraceAndPenaltyAccruesDaily() {
        // 2026-02-06: 超过宽限期 2 天; 基数 = 未还本金+未还利息 = 首期应还合计
        service.accrueOverdue("L1", LocalDate.of(2026, 2, 6));
        BigDecimal base = first().getPrincipalDue().add(first().getInterestDue());
        BigDecimal expected = base.multiply(new BigDecimal("0.0005"))
                .multiply(BigDecimal.valueOf(2)).setScale(2, java.math.RoundingMode.HALF_UP);
        log.info("[输入] asOf=2026-02-06; 基数={} 天数=2 日利率=0.0005", base);
        log.info("[罚息累计] accrued={}, 期望={}", first().getPenaltyAccrued(), expected);
        assertEquals(InstallmentStatus.OVERDUE, first().getStatus());
        assertEquals(expected, first().getPenaltyAccrued());
    }

    @Test
    void repeatedAccrualDoesNotDoubleCount() {
        service.accrueOverdue("L1", LocalDate.of(2026, 2, 6));
        BigDecimal afterFirst = first().getPenaltyAccrued();
        // 同一结算日重复推进 -> 幂等, 不重复计罚
        service.accrueOverdue("L1", LocalDate.of(2026, 2, 6));
        service.accrueOverdue("L1", LocalDate.of(2026, 2, 5)); // 回退日期也不重复
        assertEquals(afterFirst, first().getPenaltyAccrued());

        // 推进到 02-08: 只补 02-06 -> 02-08 的 2 天; 每次计提独立舍入(模型约定)
        service.accrueOverdue("L1", LocalDate.of(2026, 2, 8));
        BigDecimal base = first().getPrincipalDue().add(first().getInterestDue());
        BigDecimal twoDays = base.multiply(new BigDecimal("0.0005"))
                .multiply(BigDecimal.valueOf(2)).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal expected = afterFirst.add(twoDays);
        log.info("[判定依据] 分段计提: (02-04,02-06] 2天={} + (02-06,02-08] 2天={}, 每次计提即舍入, 合计={}",
                afterFirst, twoDays, expected);
        assertEquals(expected, first().getPenaltyAccrued());
    }

    @Test
    void crossPeriodOverdueAndPenaltyPriorityInAllocation() {
        // 跨两期逾期: 结算日 2026-03-10, 两期均逾期
        service.accrueOverdue("L1", LocalDate.of(2026, 3, 10));
        Loan loan = service.getLoan("L1");
        Installment i1 = loan.getSchedule().get(0);
        Installment i2 = loan.getSchedule().get(1);
        log.info("[跨期逾期] 期1 状态={} 罚息={}; 期2 状态={} 罚息={}",
                i1.getStatus(), i1.getPenaltyAccrued(), i2.getStatus(), i2.getPenaltyAccrued());
        assertEquals(InstallmentStatus.OVERDUE, i1.getStatus());
        assertEquals(InstallmentStatus.OVERDUE, i2.getStatus());
        assertTrue2(i1.getPenaltyAccrued().signum() > 0);
        assertTrue2(i2.getPenaltyAccrued().signum() > 0);

        // 还款先冲第1期(费用->罚息->利息->本金), 再冲第2期
        BigDecimal amount = i1.totalRemaining().add(i2.remainingPenalty());
        var p = service.repay("L1", "K1", amount, LocalDate.of(2026, 3, 10));
        for (var a : p.allocations()) {
            log.info("[资金分配] 期次={} 费用={} 罚息={} 利息={} 本金={}",
                    a.period(), a.feeApplied(), a.penaltyApplied(), a.interestApplied(), a.principalApplied());
        }
        assertEquals(InstallmentStatus.PAID, i1.getStatus());
        // 第2期罚息被冲抵, 利息本金未动
        assertEquals(new BigDecimal("0.00"), i2.remainingPenalty());
        assertTrue2(i2.remainingInterest().signum() > 0);
        log.info("[判定依据] 期次升序冲抵; 期1全额结清后余款冲期2罚息");
    }

    @Test
    void settledLoanNeverBecomesOverdueAgain() {
        // 全额还款结清
        BigDecimal total = service.getLoan("L1").getSchedule().stream()
                .map(Installment::totalRemaining).reduce(BigDecimal.ZERO, BigDecimal::add);
        service.repay("L1", "K1", total, LocalDate.of(2026, 1, 15));
        assertEquals(LoanStatus.SETTLED, service.getLoan("L1").getStatus());

        // 结清后推进逾期: 无操作, 不产生新应还
        service.accrueOverdue("L1", LocalDate.of(2027, 1, 1));
        Loan loan = service.getLoan("L1");
        for (Installment i : loan.getSchedule()) {
            assertEquals(InstallmentStatus.PAID, i.getStatus());
            assertEquals(new BigDecimal("0.00"), i.getPenaltyAccrued());
        }
        // 结清后再还款 -> LOAN_NOT_ACTIVE
        LedgerException ex = assertThrows(LedgerException.class,
                () -> service.repay("L1", "K2", new BigDecimal("1"), LocalDate.of(2027, 1, 2)));
        assertEquals(LedgerErrorCode.LOAN_NOT_ACTIVE, ex.getCode());
        log.info("[判定依据] 已结清贷款不进入逾期、不产生罚息、拒绝新的还款(LOAN_NOT_ACTIVE)");
    }

    private static void assertTrue2(boolean b) {
        org.junit.jupiter.api.Assertions.assertTrue(b);
    }
}
