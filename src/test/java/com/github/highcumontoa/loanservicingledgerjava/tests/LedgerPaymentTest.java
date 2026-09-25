package com.github.highcumontoa.loanservicingledgerjava.tests;

import com.github.highcumontoa.loanservicingledgerjava.domain.Allocation;
import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.domain.Money;
import com.github.highcumontoa.loanservicingledgerjava.domain.PaymentType;
import com.github.highcumontoa.loanservicingledgerjava.error.ErrorCode;
import com.github.highcumontoa.loanservicingledgerjava.error.LedgerException;
import com.github.highcumontoa.loanservicingledgerjava.service.AmortizationService;
import com.github.highcumontoa.loanservicingledgerjava.service.LedgerProperties;
import com.github.highcumontoa.loanservicingledgerjava.service.LedgerService;
import com.github.highcumontoa.loanservicingledgerjava.service.PaymentResult;
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
 * 还款主流程：按期还款、部分还款、资金分配顺序（罚息-利息-本金）、
 * 超额/非正金额/非法状态拒绝且错误码可区分、守恒。
 */
class LedgerPaymentTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerPaymentTest.class);

    private InMemoryLoanStore store;
    private LedgerService svc;

    @BeforeEach
    void setUp() {
        store = new InMemoryLoanStore();
        svc = new LedgerService(store, new AmortizationService(), new LedgerProperties());
    }

    private Loan issueLoan() {
        // 零利率简化，便于手工核对分配；宽限期3天，日罚息率0.0005
        return svc.disburse("L1", Money.of("1200.00"), BigDecimal.ZERO, 12,
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28),
                3, new BigDecimal("0.0005"));
    }

    @Test
    void normalPaymentAllocatedToOldestPrincipalAndConserves() {
        issueLoan();
        // 首期应还 100（零利率，每月 100，末期 100）
        PaymentResult r = svc.pay("L1", "P1", null, Money.of("100.00"),
                LocalDate.of(2026, 2, 28), PaymentType.NORMAL);
        Allocation.AllocationEntry e = r.allocation().entries().get(0);
        log.info("输入 payment=P1 amount=100.00 valueDate=2026-02-28");
        log.info("分配结果 period={} penalty={} interest={} principal={}",
                e.periodNo(), e.penaltyPaid(), e.interestPaid(), e.principalPaid());
        assertEquals(1, e.periodNo());
        assertEquals(Money.of("100.00"), e.principalPaid());
        assertEquals(Money.of("100.00"), r.allocation().totalPaid());

        Loan loan = store.get("L1");
        assertEquals(Money.of("1100.00"), loan.totalOutstanding());
        assertEquals(Money.of("100.00"), loan.totalPaid());
        log.info("判定依据: 已还=100.00 未偿=1100.00 本金合计守恒=1200.00");
    }

    @Test
    void partialPaymentAllowedAndNoNegativeBalance() {
        issueLoan();
        PaymentResult r = svc.pay("L1", "P1", null, Money.of("30.00"),
                LocalDate.of(2026, 2, 28), PaymentType.NORMAL);
        assertEquals(Money.of("30.00"), r.allocation().totalPaid());
        Loan loan = store.get("L1");
        assertEquals(Money.of("1170.00"), loan.totalOutstanding());
        assertEquals(Money.of("70.00"),
                loan.getInstallments().get(0).outstandingPrincipal());
        log.info("部分还款30: 首期剩余={} 全局未偿={}（允许部分、无负数）",
                loan.getInstallments().get(0).outstandingPrincipal(), loan.totalOutstanding());
    }

    @Test
    void overpaymentIsRejected() {
        issueLoan();
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.pay("L1", "P1", null, Money.of("999999.00"),
                        LocalDate.of(2026, 2, 28), PaymentType.NORMAL));
        assertEquals(ErrorCode.PAYMENT_EXCEEDS_DUE, ex.getCode());
        log.info("超额支付被拒绝 code={} msg={}", ex.getCode(), ex.getMessage());
        // 拒绝后账上不得有任何流水
        assertTrue(store.get("L1").getPayments().isEmpty());
    }

    @Test
    void nonPositiveAmountRejected() {
        issueLoan();
        LedgerException zero = assertThrows(LedgerException.class,
                () -> svc.pay("L1", "P1", null, Money.ZERO,
                        LocalDate.of(2026, 2, 28), PaymentType.NORMAL));
        assertEquals(ErrorCode.PAYMENT_AMOUNT_NON_POSITIVE, zero.getCode());
        log.info("零金额被拒绝 code={}", zero.getCode());
    }

    @Test
    void paymentBeforeOriginationRejected() {
        issueLoan();
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.pay("L1", "P1", null, Money.of("100.00"),
                        LocalDate.of(2026, 1, 1), PaymentType.NORMAL));
        assertEquals(ErrorCode.SETTLEMENT_BEFORE_ORIGINATION, ex.getCode());
        log.info("早于发放日被拒绝 code={}", ex.getCode());
    }

    @Test
    void waterfallIsPenaltyThenInterestThenPrincipalAcrossOverdue() {
        // 有利率贷款，先跨期产生罚息，再用一笔小钱验证顺序：罚息 > 利息 > 本金
        Loan loan = svc.disburse("L2", Money.of("120000.00"), new BigDecimal("0.12"), 12,
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28),
                3, new BigDecimal("0.0005"));
        // 结算到首期宽限期后 10 天，产生罚息
        svc.settle("L2", LocalDate.of(2026, 3, 13));
        Money penalty = loan.getInstallments().get(0).getPenaltyDue();
        log.info("首期计提罚息={}", penalty);
        assertTrue(penalty.isPositive());

        Money firstInterestDue = loan.getInstallments().get(0).getInterestDue();
        Money payAmount = penalty.plus(firstInterestDue); // 恰好只够罚息+利息
        PaymentResult r = svc.pay("L2", "P1", null, payAmount,
                LocalDate.of(2026, 3, 13), PaymentType.NORMAL);
        Allocation.AllocationEntry e = r.allocation().entries().get(0);
        log.info("输入金额={} 分配 penalty={} interest={} principal={}",
                payAmount, e.penaltyPaid(), e.interestPaid(), e.principalPaid());
        assertEquals(penalty, e.penaltyPaid());
        assertEquals(firstInterestDue, e.interestPaid());
        assertEquals(Money.ZERO, e.principalPaid(), "罚息利息未清前，不得冲本金");
    }
}
