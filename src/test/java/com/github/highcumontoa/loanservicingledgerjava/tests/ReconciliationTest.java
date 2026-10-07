package com.github.highcumontoa.loanservicingledgerjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.highcumontoa.loanservicingledgerjava.config.LedgerProperties;
import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.domain.Payment;
import com.github.highcumontoa.loanservicingledgerjava.domain.ReconciliationReport;
import com.github.highcumontoa.loanservicingledgerjava.service.AmortizationCalculator;
import com.github.highcumontoa.loanservicingledgerjava.service.LoanLedgerService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 对账入口：平衡式一(应收=已还+未偿+减免) 与平衡式二(现金=已冲抵) 的平/不平判定。 */
class ReconciliationTest {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationTest.class);
    private static final LocalDate DISBURSE_DATE = LocalDate.of(2026, 1, 1);

    private LoanLedgerService service;

    @BeforeEach
    void setUp() {
        service = new LoanLedgerService(new AmortizationCalculator(), new LedgerProperties());
        service.disburse("L1", new BigDecimal("12000"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, new BigDecimal("10"));
    }

    private static void logReport(ReconciliationReport r) {
        log.info("[对账结果] loan={} asOf={} balanced={}", r.loanId(), r.asOfDate(), r.balanced());
        r.subjects().forEach(s -> log.info(
                "  [科目] {} 应收={} 已还={} 未偿={} 减免={} 差额={}",
                s.subject(), s.billed(), s.paid(), s.outstanding(), s.waived(), s.difference()));
        log.info("  [现金] 入账={} 已冲抵={} 差额={}",
                r.cash().cashReceived(), r.cash().totalApplied(), r.cash().difference());
        r.discrepancies().forEach(d -> log.info("  [差异] {} 差额={} 明细={}",
                d.subject(), d.difference(), d.detail()));
    }

    @Test
    void balancedAfterNormalRepaymentAndOverdueAccrual() {
        // 还一部分, 再逾期一段, 任意时点导出对账都应是平的
        Loan loan = service.getLoan("L1");
        service.repay("L1", "K1", new BigDecimal("500"), LocalDate.of(2026, 1, 15));
        service.accrueOverdue("L1", LocalDate.of(2026, 3, 10));
        log.info("[输入] 还款500@01-15, 逾期推进到03-10, 对账 asOf=2026-03-10");

        ReconciliationReport r = service.reconcile("L1", LocalDate.of(2026, 3, 10));
        logReport(r);
        assertTrue(r.balanced());
        assertTrue(r.discrepancies().isEmpty());
        for (ReconciliationReport.SubjectBalance s : r.subjects()) {
            assertEquals(0, s.difference().signum(), s.subject() + " 应平");
            // 平衡式一: 应收 = 已还 + 未偿 + 减免
            assertEquals(s.billed(), s.paid().add(s.outstanding()).add(s.waived()));
        }
        // 平衡式二: 现金 = 已冲抵
        assertEquals(r.cash().cashReceived(), r.cash().totalApplied());
        assertEquals(new BigDecimal("500.00"), r.cash().cashReceived());
        log.info("[判定依据] 四科目 应收=已还+未偿+减免 均成立; 现金500=已冲抵500");
    }

    @Test
    void balancedAfterPayoffWithWaiversVisible() {
        service.payoff("L1", "K-PAYOFF", LocalDate.of(2026, 2, 20));
        ReconciliationReport r = service.reconcile("L1", LocalDate.of(2026, 2, 20));
        log.info("[输入] 提前结清@02-20 后对账 asOf=2026-02-20");
        logReport(r);

        assertTrue(r.balanced());
        ReconciliationReport.SubjectBalance interest = r.subjects().stream()
                .filter(s -> s.subject().equals("INTEREST")).findFirst().orElseThrow();
        ReconciliationReport.SubjectBalance fee = r.subjects().stream()
                .filter(s -> s.subject().equals("FEE")).findFirst().orElseThrow();
        // 减免进入平衡式: 未到期利息/费用体现在 waived 列
        assertTrue(interest.waived().signum() > 0);
        assertTrue(fee.waived().signum() > 0);
        // 结清后未偿合计为 0
        BigDecimal totalOutstanding = r.subjects().stream()
                .map(ReconciliationReport.SubjectBalance::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, totalOutstanding.signum());
        log.info("[判定依据] 结清后利息减免={} 费用减免={} 进入平衡式; 未偿合计=0",
                interest.waived(), fee.waived());
    }

    @Test
    void unbalancedReportPointsOutSubjectAndAmount() {
        service.repay("L1", "K1", new BigDecimal("500"), LocalDate.of(2026, 1, 15));

        // 模拟账本损坏1: 出现一笔无资金分配的入账流水 -> 现金侧不平
        Loan loan = service.getLoan("L1");
        loan.getPayments().add(new Payment("PAY-GHOST", "K-GHOST", "L1",
                new BigDecimal("77.00"), LocalDate.of(2026, 1, 16), List.of()));
        ReconciliationReport r1 = service.reconcile("L1", LocalDate.of(2026, 1, 20));
        log.info("[输入] 注入幽灵流水77.00(无分配) 后对账");
        logReport(r1);
        assertFalse(r1.balanced());
        assertEquals(1, r1.discrepancies().size());
        ReconciliationReport.Discrepancy cash = r1.discrepancies().get(0);
        assertEquals("CASH", cash.subject());
        assertEquals(new BigDecimal("77.00"), cash.difference());
        log.info("[判定依据] 现金科目不平: 入账比已冲抵多 77.00, 报告指明 CASH 差 77.00");

        // 模拟账本损坏2: 账户级本金余额与计划明细脱节 -> 本金科目不平
        loan.getPayments().removeIf(p -> p.paymentId().equals("PAY-GHOST"));
        loan.setOutstandingPrincipal(loan.getOutstandingPrincipal().add(new BigDecimal("5.00")));
        ReconciliationReport r2 = service.reconcile("L1", LocalDate.of(2026, 1, 20));
        log.info("[输入] 账户本金余额被人为调增5.00 后对账");
        logReport(r2);
        assertFalse(r2.balanced());
        ReconciliationReport.Discrepancy principal = r2.discrepancies().stream()
                .filter(d -> d.subject().equals("PRINCIPAL")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("-5.00"), principal.difference());
        log.info("[判定依据] 本金科目不平: 应收-已还-减免 比账户余额少 5.00, 报告指明 PRINCIPAL 差 -5.00");
    }

    @Test
    void reconcileIsIdempotentAndDoesNotDoubleAccrue() {
        service.accrueOverdue("L1", LocalDate.of(2026, 3, 10));
        BigDecimal penaltyBefore = service.getLoan("L1").getSchedule().stream()
                .map(Installment::getPenaltyAccrued).reduce(BigDecimal.ZERO, BigDecimal::add);

        ReconciliationReport r1 = service.reconcile("L1", LocalDate.of(2026, 3, 10));
        ReconciliationReport r2 = service.reconcile("L1", LocalDate.of(2026, 3, 10));
        BigDecimal penaltyAfter = service.getLoan("L1").getSchedule().stream()
                .map(Installment::getPenaltyAccrued).reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("[判定依据] 对账不重复计罚: 罚息 {} -> {}; 两次对账结论一致 balanced={}",
                penaltyBefore, penaltyAfter, r1.balanced() && r2.balanced());
        assertEquals(penaltyBefore, penaltyAfter);
        assertTrue(r1.balanced());
        assertTrue(r2.balanced());
    }
}
