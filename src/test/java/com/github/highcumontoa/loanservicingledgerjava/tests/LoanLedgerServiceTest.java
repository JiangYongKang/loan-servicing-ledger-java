package com.github.highcumontoa.loanservicingledgerjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.highcumontoa.loanservicingledgerjava.config.LedgerProperties;
import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import com.github.highcumontoa.loanservicingledgerjava.domain.InstallmentStatus;
import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.domain.LoanStatus;
import com.github.highcumontoa.loanservicingledgerjava.domain.Payment;
import com.github.highcumontoa.loanservicingledgerjava.domain.PaymentAllocation;
import com.github.highcumontoa.loanservicingledgerjava.error.LedgerErrorCode;
import com.github.highcumontoa.loanservicingledgerjava.error.LedgerException;
import com.github.highcumontoa.loanservicingledgerjava.service.AmortizationCalculator;
import com.github.highcumontoa.loanservicingledgerjava.service.LoanLedgerService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 摊销计划、正常/部分还款与资金分配顺序测试。 */
class LoanLedgerServiceTest {

    private static final Logger log = LoggerFactory.getLogger(LoanLedgerServiceTest.class);
    private static final LocalDate DISBURSE_DATE = LocalDate.of(2026, 1, 1);

    private LoanLedgerService service;

    @BeforeEach
    void setUp() {
        LedgerProperties props = new LedgerProperties(); // 默认: 宽限3天, 罚息万五/日, 费用0
        service = new LoanLedgerService(new AmortizationCalculator(), props);
    }

    private Loan disburse12k() {
        // 本金 12000, 年利率 12%, 12 期, 每期费用 10
        Loan loan = service.disburse("L1", new BigDecimal("12000"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, new BigDecimal("10"));
        log.info("[输入] 放款 principal=12000, annualRate=0.12, term=12, fee=10, date={}", DISBURSE_DATE);
        for (Installment i : loan.getSchedule()) {
            log.info("[摊销计划] 期次={} 到期日={} 本金={} 利息={} 费用={}",
                    i.getPeriod(), i.getDueDate(), i.getPrincipalDue(), i.getInterestDue(), i.getFeeDue());
        }
        return loan;
    }

    @Test
    void scheduleIsReproducibleAndPrincipalConserved() {
        Loan loan = disburse12k();
        Loan again = new LoanLedgerService(new AmortizationCalculator(), new LedgerProperties())
                .disburse("L1", new BigDecimal("12000"), new BigDecimal("0.12"),
                        12, DISBURSE_DATE, new BigDecimal("10"));

        List<Installment> s1 = loan.getSchedule();
        List<Installment> s2 = again.getSchedule();
        assertEquals(12, s1.size());
        BigDecimal principalSum = BigDecimal.ZERO;
        for (int i = 0; i < 12; i++) {
            // 可复现：同样输入得到同样计划
            assertEquals(s1.get(i).getPrincipalDue(), s2.get(i).getPrincipalDue());
            assertEquals(s1.get(i).getInterestDue(), s2.get(i).getInterestDue());
            assertEquals(DISBURSE_DATE.plusMonths(i + 1), s1.get(i).getDueDate());
            principalSum = principalSum.add(s1.get(i).getPrincipalDue());
        }
        // 本金守恒：各期本金合计 == 放款本金
        assertEquals(new BigDecimal("12000.00"), principalSum);
        // 等额本息：月利率 1%，首期利息 = 12000 * 1% = 120
        assertEquals(new BigDecimal("120.00"), s1.get(0).getInterestDue());
        log.info("[判定依据] 月利率=0.12/12=0.01, 首期利息=12000*0.01=120.00, 各期本金合计={}", principalSum);
    }

    @Test
    void fullPaymentAllocatesFeeThenInterestThenPrincipal() {
        Loan loan = disburse12k();
        Installment first = loan.getSchedule().get(0);
        BigDecimal due = first.getFeeDue().add(first.getInterestDue()).add(first.getPrincipalDue());

        Payment p = service.repay("L1", "K1", due, LocalDate.of(2026, 1, 15));
        log.info("[输入] 还款 amount={} valueDate=2026-01-15", due);
        for (PaymentAllocation a : p.allocations()) {
            log.info("[资金分配] 期次={} 费用={} 罚息={} 利息={} 本金={}",
                    a.period(), a.feeApplied(), a.penaltyApplied(), a.interestApplied(), a.principalApplied());
        }
        assertEquals(1, p.allocations().size());
        PaymentAllocation a = p.allocations().get(0);
        assertEquals(new BigDecimal("10.00"), a.feeApplied());
        assertEquals(new BigDecimal("120.00"), a.interestApplied());
        assertEquals(first.getPrincipalDue(), a.principalApplied());
        assertEquals(InstallmentStatus.PAID, first.getStatus());
        assertEquals(new BigDecimal("12000.00").subtract(first.getPrincipalDue()),
                service.getLoan("L1").getOutstandingPrincipal());
        log.info("[判定依据] 冲抵顺序 费用->罚息->利息->本金; 本期全额冲抵后状态=PAID");
    }

    @Test
    void partialPaymentFollowsAllocationOrder() {
        Loan loan = disburse12k();
        Installment first = loan.getSchedule().get(0);

        // 只还 15：先冲费用 10，再冲利息 5，本金不动
        Payment p = service.repay("L1", "K1", new BigDecimal("15"), LocalDate.of(2026, 1, 15));
        PaymentAllocation a = p.allocations().get(0);
        log.info("[输入] 部分还款 amount=15; [资金分配] 费用={} 利息={} 本金={}",
                a.feeApplied(), a.interestApplied(), a.principalApplied());
        assertEquals(new BigDecimal("10.00"), a.feeApplied());
        assertEquals(new BigDecimal("5.00"), a.interestApplied());
        assertEquals(new BigDecimal("0.00"), a.principalApplied());
        assertEquals(InstallmentStatus.PARTIALLY_PAID, first.getStatus());
        // 本金余额不变
        assertEquals(new BigDecimal("12000.00"), service.getLoan("L1").getOutstandingPrincipal());
        log.info("[判定依据] 15 先冲费用10, 余5冲利息, 本金未冲抵 -> PARTIALLY_PAID, 本金余额不变");
    }

    @Test
    void rejectsInvalidAmountsAndOverpaymentAndUnknownLoan() {
        disburse12k();
        LedgerException zero = assertThrows(LedgerException.class,
                () -> service.repay("L1", "K1", BigDecimal.ZERO, LocalDate.of(2026, 1, 15)));
        assertEquals(LedgerErrorCode.INVALID_AMOUNT, zero.getCode());

        LedgerException negative = assertThrows(LedgerException.class,
                () -> service.repay("L1", "K1", new BigDecimal("-5"), LocalDate.of(2026, 1, 15)));
        assertEquals(LedgerErrorCode.INVALID_AMOUNT, negative.getCode());

        LedgerException notFound = assertThrows(LedgerException.class,
                () -> service.repay("NOPE", "K1", new BigDecimal("10"), LocalDate.of(2026, 1, 15)));
        assertEquals(LedgerErrorCode.LOAN_NOT_FOUND, notFound.getCode());

        BigDecimal total = service.getLoan("L1").getSchedule().stream()
                .map(Installment::totalRemaining).reduce(BigDecimal.ZERO, BigDecimal::add);
        LedgerException over = assertThrows(LedgerException.class,
                () -> service.repay("L1", "K1", total.add(new BigDecimal("0.01")), LocalDate.of(2026, 1, 15)));
        assertEquals(LedgerErrorCode.OVERPAYMENT, over.getCode());
        log.info("[判定依据] 0/负数->INVALID_AMOUNT, 未知贷款->LOAN_NOT_FOUND, 超过未偿总额 {} -> OVERPAYMENT", total);
        // 拒绝后账本未被污染
        assertTrue(service.getLoan("L1").getPayments().isEmpty());
    }
}
