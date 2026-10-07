package com.github.highcumontoa.loanservicingledgerjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.highcumontoa.loanservicingledgerjava.config.LedgerProperties;
import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.domain.LoanStatus;
import com.github.highcumontoa.loanservicingledgerjava.domain.Payment;
import com.github.highcumontoa.loanservicingledgerjava.domain.PaymentAllocation;
import com.github.highcumontoa.loanservicingledgerjava.domain.reconciliation.ComponentBalance;
import com.github.highcumontoa.loanservicingledgerjava.domain.reconciliation.Discrepancy;
import com.github.highcumontoa.loanservicingledgerjava.domain.reconciliation.ReconciliationReport;
import com.github.highcumontoa.loanservicingledgerjava.service.AmortizationCalculator;
import com.github.highcumontoa.loanservicingledgerjava.service.LoanLedgerService;
import com.github.highcumontoa.loanservicingledgerjava.service.Reconciler;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 对账入口：平衡/不平衡可区分，且能指出科目与差额；同结算日复算稳定。 */
class ReconciliationTest {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationTest.class);
    private static final LocalDate DISBURSE_DATE = LocalDate.of(2026, 1, 1);

    private LoanLedgerService newService() {
        return new LoanLedgerService(new AmortizationCalculator(), new LedgerProperties(),
                new Reconciler());
    }

    @Test
    void activeLoanWithPartialRepayReconcilesBalanced() {
        LoanLedgerService svc = newService();
        svc.disburse("R1", new BigDecimal("12000"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, new BigDecimal("10"));
        svc.repay("R1", "K1", new BigDecimal("500"), LocalDate.of(2026, 1, 15));

        ReconciliationReport report = svc.reconcile("R1", LocalDate.of(2026, 3, 10));
        log.info("[输入] 对账 R1 asOf=2026-03-10");
        for (ComponentBalance c : report.components()) {
            log.info("[应收侧] 科目={} 应收={} = 已冲抵{} + 未偿{} + 减免{} (差{})",
                    c.component(), c.due(), c.paid(), c.outstanding(), c.waived(), c.difference());
        }
        log.info("[现金侧] 入账={} 冲抵={} 差={}",
                report.cashReceived(), report.cashApplied(), report.cashDifference());

        assertTrue(report.receivablesBalanced());
        assertTrue(report.cashBalanced());
        assertTrue(report.balanced());
        assertTrue(report.discrepancies().isEmpty());
        // 总平衡：应收 == 已还 + 未偿 + 减免
        assertEquals(report.totalDue(),
                report.totalPaid().add(report.totalOutstanding()).add(report.totalWaived()));
        assertEquals(new BigDecimal("500.00"), report.cashReceived());
        // PENALTY 科目在逾期后也应自平
        ComponentBalance penalty = report.components().stream()
                .filter(c -> c.component().equals("PENALTY")).findFirst().orElseThrow();
        assertEquals(penalty.due(),
                penalty.paid().add(penalty.outstanding()).add(penalty.waived()));
        log.info("[判定依据] 四科目各自 应收=已冲抵+未偿+减免; 现金 入账=冲抵 -> 平衡");
    }

    @Test
    void sameDateRepeatedReconciliationIsStable() {
        LoanLedgerService svc = newService();
        svc.disburse("R2", new BigDecimal("1200"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, BigDecimal.ZERO);
        LocalDate d = LocalDate.of(2026, 3, 1);
        ReconciliationReport a = svc.reconcile("R2", d);
        ReconciliationReport b = svc.reconcile("R2", d);
        ReconciliationReport c = svc.reconcile("R2", LocalDate.of(2026, 2, 20)); // 乱序补跑更早
        ReconciliationReport d2 = svc.reconcile("R2", d);
        log.info("[输入] 对账 R2: 03-01两次 + 02-20 + 03-01; PENALTY应收 a={} b={} c={} d={}",
                penaltyDue(a), penaltyDue(b), penaltyDue(c), penaltyDue(d2));
        assertEquals(penaltyDue(a), penaltyDue(b));
        assertEquals(penaltyDue(a), penaltyDue(d2)); // 重复/乱序不重复计提
        assertTrue(d2.balanced());
        log.info("[判定依据] 同结算日反复对账罚息与结论一分不差（幂等推进，不重复计提）");
    }

    @Test
    void externalCashMismatchIsReportedWithSideComponentAndDifference() {
        LoanLedgerService svc = newService();
        svc.disburse("R3", new BigDecimal("12000"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, new BigDecimal("10"));
        svc.repay("R3", "K1", new BigDecimal("500"), LocalDate.of(2026, 1, 15));

        // 外部现金系统只认到 480.00，比账本冲抵少 20.00
        ReconciliationReport report = svc.reconcile("R3", LocalDate.of(2026, 2, 10),
                new BigDecimal("480.00"));
        log.info("[输入] 对账 R3 外部现金=480.00; [现金侧] 入账={} 冲抵={}",
                report.cashReceived(), report.cashApplied());
        assertFalse(report.cashBalanced());
        assertFalse(report.balanced());
        assertTrue(report.receivablesBalanced(), "应收侧仍应平");
        assertEquals(1, report.discrepancies().size());
        Discrepancy disc = report.discrepancies().get(0);
        assertEquals("CASH", disc.side());
        assertEquals("CASH", disc.component());
        assertEquals(new BigDecimal("-20.00"), disc.difference());
        log.info("[判定依据] 不平被定位: side=CASH 科目=CASH 实际入账480.00 vs 冲抵500.00 差-20.00");
    }

    @Test
    void settledLoanReconcilesBalancedWithWaiversAndNeverAccruesAfter() {
        LoanLedgerService svc = newService();
        svc.disburse("R4", new BigDecimal("12000"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, new BigDecimal("10"));
        svc.payoff("R4", "K-PAYOFF", LocalDate.of(2026, 1, 20));

        ReconciliationReport report = svc.reconcile("R4", LocalDate.of(2028, 1, 1));
        log.info("[输入] 已结清贷款在2028-01-01对账; 未偿={} 减免={}",
                report.totalOutstanding(), report.totalWaived());
        assertEquals(LoanStatus.SETTLED.name(), report.loanStatus());
        assertEquals(new BigDecimal("0.00"), report.totalOutstanding());
        assertTrue(report.totalWaived().signum() > 0);
        assertTrue(report.balanced());
        // 结清很久以后：罚息科目仍为 0（不会再被判逾期/计提）
        ComponentBalance penalty = report.components().stream()
                .filter(c -> c.component().equals("PENALTY")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("0.00"), penalty.due());
        log.info("[判定依据] 结清后未偿=0、减免可查、罚息恒0、两面皆平");
    }

    @Test
    void receivablesSideDiscrepancyPinpointsComponentAndDifference() {
        // 构造应收侧不平：篡改资金分配流水(资金账)里的 feeApplied，使其比期次账少 0.07。
        // 这模拟"还款流水上记的冲抵"与"期次上的已还/未偿"对不上的真实情形。
        LoanLedgerService svc = newService();
        svc.disburse("R5", new BigDecimal("12000"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, new BigDecimal("10"));
        svc.repay("R5", "K1", new BigDecimal("100"), LocalDate.of(2026, 1, 15));

        Loan loan = svc.getLoan("R5");
        try {
            com.github.highcumontoa.loanservicingledgerjava.domain.PaymentAllocation original =
                    loan.getPayments().get(0).allocations().get(0);
            com.github.highcumontoa.loanservicingledgerjava.domain.PaymentAllocation tampered =
                    new com.github.highcumontoa.loanservicingledgerjava.domain.PaymentAllocation(
                            original.period(),
                            original.feeApplied().subtract(new BigDecimal("0.07")),
                            original.penaltyApplied(),
                            original.interestApplied(),
                            original.principalApplied());
            Field allocField = Payment.class.getDeclaredField("allocations");
            allocField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<PaymentAllocation> allocs =
                    (java.util.List<PaymentAllocation>) allocField.get(loan.getPayments().get(0));
            allocs.set(0, tampered); // 原地替换可变列表中的元素
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }

        // 直接用对账器读取被污染的账本（绕过服务层守恒断言）
        ReconciliationReport report = new Reconciler().build(loan, LocalDate.of(2026, 2, 10), null);
        log.info("[输入] 篡改期1 feePaid -0.07 后对账; FEE 应收={} 已冲抵={} 未偿={} 减免={}",
                component(report, "FEE").due(), component(report, "FEE").paid(),
                component(report, "FEE").outstanding(), component(report, "FEE").waived());
        assertFalse(report.receivablesBalanced());
        assertFalse(report.balanced());
        Discrepancy feeDisc = report.discrepancies().stream()
                .filter(x -> x.side().equals("RECEIVABLE")).findFirst().orElseThrow();
        assertEquals("FEE", feeDisc.component());
        assertEquals(new BigDecimal("0.07"), feeDisc.difference());
        log.info("[判定依据] 不平被定位: side=RECEIVABLE 科目=FEE 差0.07 ({} vs {})",
                feeDisc.leftLabel(), feeDisc.rightLabel());
    }

    private static ComponentBalance component(ReconciliationReport r, String name) {
        return r.components().stream().filter(c -> c.component().equals(name)).findFirst().orElseThrow();
    }

    private static BigDecimal penaltyDue(ReconciliationReport r) {
        return component(r, "PENALTY").due();
    }
}
