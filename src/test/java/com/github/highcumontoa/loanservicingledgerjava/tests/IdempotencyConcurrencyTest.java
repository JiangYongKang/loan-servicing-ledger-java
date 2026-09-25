package com.github.highcumontoa.loanservicingledgerjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.highcumontoa.loanservicingledgerjava.config.LedgerProperties;
import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.domain.Payment;
import com.github.highcumontoa.loanservicingledgerjava.error.LedgerErrorCode;
import com.github.highcumontoa.loanservicingledgerjava.error.LedgerException;
import com.github.highcumontoa.loanservicingledgerjava.service.AmortizationCalculator;
import com.github.highcumontoa.loanservicingledgerjava.service.LoanLedgerService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 重复提交与并发还款的一致性测试。 */
class IdempotencyConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyConcurrencyTest.class);
    private static final LocalDate DISBURSE_DATE = LocalDate.of(2026, 1, 1);

    private LoanLedgerService service;

    @BeforeEach
    void setUp() {
        service = new LoanLedgerService(new AmortizationCalculator(), new LedgerProperties());
        service.disburse("L1", new BigDecimal("12000"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, new BigDecimal("10"));
    }

    @Test
    void duplicateSubmissionWithSameKeyIsIdempotent() {
        Payment first = service.repay("L1", "K1", new BigDecimal("500"), LocalDate.of(2026, 1, 15));
        Payment replay = service.repay("L1", "K1", new BigDecimal("500"), LocalDate.of(2026, 1, 15));
        log.info("[输入] 同键同额重复提交 key=K1 amount=500; [结果] first={}, replay={}",
                first.paymentId(), replay.paymentId());
        assertSame(first, replay); // 返回首次入账结果
        assertEquals(1, service.getLoan("L1").getPayments().size()); // 只入账一次
        log.info("[判定依据] 幂等键唯一 -> 重复提交返回首次流水, 账本仅一条记录");
    }

    @Test
    void sameKeyWithDifferentAmountIsRejectedAsConflict() {
        service.repay("L1", "K1", new BigDecimal("500"), LocalDate.of(2026, 1, 15));
        LedgerException ex = assertThrows(LedgerException.class,
                () -> service.repay("L1", "K1", new BigDecimal("600"), LocalDate.of(2026, 1, 15)));
        log.info("[输入] 同键不同额 500 vs 600; [结果] code={} message={}", ex.getCode(), ex.getMessage());
        assertEquals(LedgerErrorCode.CONCURRENT_CONFLICT, ex.getCode());
        assertEquals(1, service.getLoan("L1").getPayments().size());
    }

    @Test
    void concurrentDuplicateSubmissionsSettleExactlyOnce() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Payment>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.repay("L1", "K-DUP", new BigDecimal("500"), LocalDate.of(2026, 1, 15));
            }));
        }
        start.countDown();
        String firstId = null;
        for (Future<Payment> f : futures) {
            Payment p = f.get();
            if (firstId == null) {
                firstId = p.paymentId();
            }
            assertEquals(firstId, p.paymentId()); // 所有线程拿到同一笔流水
        }
        pool.shutdown();
        Loan loan = service.getLoan("L1");
        log.info("[输入] {} 线程同键并发提交; [结果] 入账笔数={}", threads, loan.getPayments().size());
        assertEquals(1, loan.getPayments().size());
        assertEquals(new BigDecimal("500.00"), loan.getPayments().get(0).amount());
        log.info("[判定依据] 同一笔还款只入账一次, 与串行执行一次的结果一致");
    }

    @Test
    void concurrentDistinctPaymentsMatchSerialExecution() throws Exception {
        int threads = 20;
        BigDecimal each = new BigDecimal("100");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Payment>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                return service.repay("L1", "K-" + idx, each, LocalDate.of(2026, 1, 15));
            }));
        }
        start.countDown();
        for (Future<Payment> f : futures) {
            f.get();
        }
        pool.shutdown();

        Loan concurrent = service.getLoan("L1");

        // 参照组: 同样 20 笔逐笔串行
        LoanLedgerService serial = new LoanLedgerService(new AmortizationCalculator(), new LedgerProperties());
        serial.disburse("L1", new BigDecimal("12000"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, new BigDecimal("10"));
        for (int i = 0; i < threads; i++) {
            serial.repay("L1", "K-" + i, each, LocalDate.of(2026, 1, 15));
        }
        Loan expected = serial.getLoan("L1");

        log.info("[输入] {} 笔 x {} 并发还款; [并发终态] 本金余额={} 入账={}笔; [串行终态] 本金余额={}",
                threads, each, concurrent.getOutstandingPrincipal(),
                concurrent.getPayments().size(), expected.getOutstandingPrincipal());

        assertEquals(threads, concurrent.getPayments().size());
        assertEquals(expected.getOutstandingPrincipal(), concurrent.getOutstandingPrincipal());
        for (int i = 0; i < 12; i++) {
            Installment ci = concurrent.getSchedule().get(i);
            Installment si = expected.getSchedule().get(i);
            assertEquals(si.getPrincipalPaid(), ci.getPrincipalPaid(), "period " + (i + 1) + " principal");
            assertEquals(si.getInterestPaid(), ci.getInterestPaid(), "period " + (i + 1) + " interest");
            assertEquals(si.getFeePaid(), ci.getFeePaid(), "period " + (i + 1) + " fee");
        }
        // 守恒: 入账总额 == 各期已冲抵合计
        BigDecimal paidTotal = concurrent.getPayments().stream()
                .map(Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal appliedTotal = concurrent.getSchedule().stream()
                .map(inst -> inst.getFeePaid().add(inst.getPenaltyPaid())
                        .add(inst.getInterestPaid()).add(inst.getPrincipalPaid()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(paidTotal, appliedTotal);
        assertEquals(new BigDecimal("2000.00"), paidTotal);
        assertTrue(concurrent.getOutstandingPrincipal().signum() >= 0);
        log.info("[判定依据] 并发终态与串行终态逐期一致; 入账总额={} == 冲抵合计={} (守恒)",
                paidTotal, appliedTotal);
    }
}
