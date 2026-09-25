package com.github.highcumontoa.loanservicingledgerjava.tests;

import com.github.highcumontoa.loanservicingledgerjava.domain.Allocation;
import com.github.highcumontoa.loanservicingledgerjava.domain.Money;
import com.github.highcumontoa.loanservicingledgerjava.domain.PaymentType;
import com.github.highcumontoa.loanservicingledgerjava.service.AmortizationService;
import com.github.highcumontoa.loanservicingledgerjava.service.LedgerProperties;
import com.github.highcumontoa.loanservicingledgerjava.service.LedgerService;
import com.github.highcumontoa.loanservicingledgerjava.service.PaymentResult;
import com.github.highcumontoa.loanservicingledgerjava.store.InMemoryLoanStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 各入口结论一致性：
 * 1) 不先 settle 直接在逾期日还款，与“先 settle 再还款”结果必须一致；
 * 2) 还款结算日逾期时自动计提罚息并优先冲抵。
 */
class LedgerEntryConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerEntryConsistencyTest.class);

    @Test
    void overduePaymentWithoutPriorSettleMatchesSettleThenPay() {
        // 账户A：直接逾期日还款
        InMemoryLoanStore a = new InMemoryLoanStore();
        LedgerService sa = new LedgerService(a, new AmortizationService(), new LedgerProperties());
        sa.disburse("L1", Money.of("1200.00"), BigDecimal.ZERO, 12,
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28),
                3, new BigDecimal("0.0005"));
        LocalDate valueDate = LocalDate.of(2026, 3, 10); // 罚息7天=0.35
        // 应还 = 100 + 0.35 = 100.35
        PaymentResult ra = sa.pay("L1", "P1", null, Money.of("100.35"),
                valueDate, PaymentType.NORMAL);

        // 账户B：先 settle 再同日还款
        InMemoryLoanStore b = new InMemoryLoanStore();
        LedgerService sb = new LedgerService(b, new AmortizationService(), new LedgerProperties());
        sb.disburse("L1", Money.of("1200.00"), BigDecimal.ZERO, 12,
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28),
                3, new BigDecimal("0.0005"));
        sb.settle("L1", valueDate);
        PaymentResult rb = sb.pay("L1", "P1", null, Money.of("100.35"),
                valueDate, PaymentType.NORMAL);

        Allocation.AllocationEntry ea = ra.allocation().entries().get(0);
        Allocation.AllocationEntry eb = rb.allocation().entries().get(0);
        log.info("入口A(直接还款): penalty={} principal={}; 入口B(先结算): penalty={} principal={}",
                ea.penaltyPaid(), ea.principalPaid(), eb.penaltyPaid(), eb.principalPaid());
        assertEquals(eb, ea, "两种入口的资金分配必须一致");
        assertEquals(Money.of("0.35"), ea.penaltyPaid());
        assertEquals(Money.of("100.00"), ea.principalPaid());
        assertEquals(a.get("L1").totalOutstanding(), b.get("L1").totalOutstanding());
        assertEquals(Money.of("1100.00"), a.get("L1").totalOutstanding(),
                "罚息被支付后，剩余仅为本金 1100");
        log.info("入口一致性通过，未偿={}", a.get("L1").totalOutstanding());
    }

    @Test
    void smallOverduePaymentCoversPenaltyFirst() {
        InMemoryLoanStore store = new InMemoryLoanStore();
        LedgerService svc = new LedgerService(store, new AmortizationService(), new LedgerProperties());
        svc.disburse("L1", Money.of("1200.00"), BigDecimal.ZERO, 12,
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28),
                3, new BigDecimal("0.0005"));
        // 只还 0.20，小于罚息 0.35：全部冲罚息，本息不动
        PaymentResult r = svc.pay("L1", "P1", null, Money.of("0.20"),
                LocalDate.of(2026, 3, 10), PaymentType.NORMAL);
        Allocation.AllocationEntry e = r.allocation().entries().get(0);
        log.info("小额还款0.20在逾期场景: penalty={} interest={} principal={}",
                e.penaltyPaid(), e.interestPaid(), e.principalPaid());
        assertEquals(Money.of("0.20"), e.penaltyPaid());
        assertEquals(Money.ZERO, e.interestPaid());
        assertEquals(Money.ZERO, e.principalPaid());
        // 未偿 = 1200本金 + (0.35-0.20)罚息
        assertEquals(Money.of("1200.15"), store.get("L1").totalOutstanding());
    }
}
