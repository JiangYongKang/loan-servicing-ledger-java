package com.github.highcumontoa.loanservicingledgerjava.tests;

import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.domain.LoanStatus;
import com.github.highcumontoa.loanservicingledgerjava.domain.Money;
import com.github.highcumontoa.loanservicingledgerjava.domain.PayoffQuote;
import com.github.highcumontoa.loanservicingledgerjava.domain.PaymentType;
import com.github.highcumontoa.loanservicingledgerjava.error.ErrorCode;
import com.github.highcumontoa.loanservicingledgerjava.error.LedgerException;
import com.github.highcumontoa.loanservicingledgerjava.service.AmortizationService;
import com.github.highcumontoa.loanservicingledgerjava.service.LedgerProperties;
import com.github.highcumontoa.loanservicingledgerjava.service.LedgerService;
import com.github.highcumontoa.loanservicingledgerjava.store.InMemoryLoanStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提前结清：报价可复现、未到期利息免除、金额必须与报价一致、结清后不可再操作。
 */
class LedgerPayoffTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerPayoffTest.class);

    private InMemoryLoanStore store;
    private LedgerService svc;

    @BeforeEach
    void setUp() {
        store = new InMemoryLoanStore();
        svc = new LedgerService(store, new AmortizationService(), new LedgerProperties());
    }

    @Test
    void earlyPayoffWavesFutureInterestAndClosesLoan() {
        svc.disburse("L1", Money.of("120000.00"), new BigDecimal("0.12"), 12,
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28),
                3, new BigDecimal("0.0005"));
        LocalDate settle = LocalDate.of(2026, 2, 28); // 首期到期日，无逾期

        PayoffQuote q1 = svc.quotePayoff("L1", settle);
        PayoffQuote q2 = svc.quotePayoff("L1", settle);
        log.info("提前结清报价: penalty={} interest={} principal={} total={}",
                q1.outstandingPenalty(), q1.outstandingInterest(),
                q1.outstandingPrincipal(), q1.totalPayoff());
        assertEquals(q1.totalPayoff(), q2.totalPayoff(), "报价必须可复现");
        // 首期（已到期）利息仍需支付，第2..12期利息被免除
        assertTrue(q1.outstandingInterest().isPositive());
        assertEquals(Money.of("120000.00"), q1.outstandingPrincipal());

        svc.pay("L1", "P1", "idem-payoff-1", q1.totalPayoff(), settle, PaymentType.EARLY_PAYOFF);

        Loan loan = store.get("L1");
        assertEquals(LoanStatus.PAID_OFF, loan.getStatus());
        assertEquals(Money.ZERO, loan.totalOutstanding());
        assertTrue(loan.totalInterestWaived().isPositive(), "未到期利息应留痕为减免");
        log.info("提前结清完成: 已还={} 减免利息={} 未偿=0", loan.totalPaid(), loan.totalInterestWaived());

        // 已结清不能再次提前结清
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.quotePayoff("L1", settle));
        assertEquals(ErrorCode.LOAN_ALREADY_PAID_OFF, ex.getCode());
    }

    @Test
    void payoffAmountMismatchIsRejected() {
        svc.disburse("L1", Money.of("1200.00"), BigDecimal.ZERO, 12,
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28),
                3, new BigDecimal("0.0005"));
        PayoffQuote q = svc.quotePayoff("L1", LocalDate.of(2026, 2, 28));
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.pay("L1", "P1", null, q.totalPayoff().plus(Money.of("1.00")),
                        LocalDate.of(2026, 2, 28), PaymentType.EARLY_PAYOFF));
        assertEquals(ErrorCode.PAYOFF_AMOUNT_MISMATCH, ex.getCode());
        log.info("报价金额不一致被拒绝 code={} quote={}", ex.getCode(), q.totalPayoff());
        assertTrue(store.get("L1").getPayments().isEmpty(), "拒绝不得留下流水");
    }
}
