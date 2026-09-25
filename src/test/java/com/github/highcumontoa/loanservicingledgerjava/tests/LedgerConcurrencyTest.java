package com.github.highcumontoa.loanservicingledgerjava.tests;

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
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复提交与并发：
 * 同键重放不重复入账；同键不同内容冲突拒绝；
 * 并发提交同一笔只有一笔入账；并发多笔最终状态与串行一致且守恒。
 */
class LedgerConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerConcurrencyTest.class);

    private LedgerService newService(InMemoryLoanStore store) {
        return new LedgerService(store, new AmortizationService(), new LedgerProperties());
    }

    private void issue(LedgerService svc, String id) {
        svc.disburse(id, Money.of("1200.00"), BigDecimal.ZERO, 12,
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28),
                3, new BigDecimal("0.0005"));
    }

    @Test
    void duplicateSameKeyIsReplayedOnce() {
        InMemoryLoanStore store = new InMemoryLoanStore();
        LedgerService svc = newService(store);
        issue(svc, "L1");
        PaymentResult first = svc.pay("L1", "P1", "key-A", Money.of("100.00"),
                LocalDate.of(2026, 2, 28), PaymentType.NORMAL);
        PaymentResult replay = svc.pay("L1", "P1", "key-A", Money.of("100.00"),
                LocalDate.of(2026, 2, 28), PaymentType.NORMAL);
        assertEquals(false, first.idempotentReplay());
        assertTrue(replay.idempotentReplay());
        assertEquals(1, store.get("L1").getPayments().size(), "同一笔只能入账一次");
        assertEquals(Money.of("1100.00"), store.get("L1").totalOutstanding());
        log.info("重复提交: 仅一条流水，重放标志={} 未偿={}", replay.idempotentReplay(),
                store.get("L1").totalOutstanding());
    }

    @Test
    void sameKeyDifferentContentConflicts() {
        InMemoryLoanStore store = new InMemoryLoanStore();
        LedgerService svc = newService(store);
        issue(svc, "L1");
        svc.pay("L1", "P1", "key-A", Money.of("100.00"),
                LocalDate.of(2026, 2, 28), PaymentType.NORMAL);
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.pay("L1", "P1", "key-A", Money.of("120.00"),
                        LocalDate.of(2026, 2, 28), PaymentType.NORMAL));
        assertEquals(ErrorCode.PAYMENT_CONFLICT, ex.getCode());
        log.info("同键不同内容冲突拒绝 code={}", ex.getCode());
    }

    @Test
    void paymentIdReuseWithDifferentKeyConflicts() {
        InMemoryLoanStore store = new InMemoryLoanStore();
        LedgerService svc = newService(store);
        issue(svc, "L1");
        svc.pay("L1", "P1", "key-A", Money.of("100.00"),
                LocalDate.of(2026, 2, 28), PaymentType.NORMAL);
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.pay("L1", "P1", "key-B", Money.of("100.00"),
                        LocalDate.of(2026, 2, 28), PaymentType.NORMAL));
        assertEquals(ErrorCode.PAYMENT_CONFLICT, ex.getCode());
        log.info("paymentId 复用冲突拒绝 code={}", ex.getCode());
    }

    @Test
    void concurrentSamePaymentOnlyOneAccepted() throws Exception {
        InMemoryLoanStore store = new InMemoryLoanStore();
        LedgerService svc = newService(store);
        issue(svc, "L1");
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    PaymentResult r = svc.pay("L1", "P1", "key-A", Money.of("100.00"),
                            LocalDate.of(2026, 2, 28), PaymentType.NORMAL);
                    if (r.idempotentReplay()) {
                        replayed.incrementAndGet();
                    } else {
                        accepted.incrementAndGet();
                    }
                } catch (LedgerException e) {
                    if (e.getCode() == ErrorCode.PAYMENT_CONFLICT
                            || e.getCode() == ErrorCode.PAYMENT_DUPLICATED) {
                        conflict.incrementAndGet();
                    } else {
                        throw new RuntimeException(e);
                    }
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        log.info("并发同笔: accepted={} replay={} conflict={}", accepted.get(), replayed.get(), conflict.get());
        assertEquals(1, accepted.get(), "恰好一笔入账");
        assertEquals(threads - 1, replayed.get() + conflict.get());
        assertEquals(1, store.get("L1").getPayments().size());
        assertEquals(Money.of("1100.00"), store.get("L1").totalOutstanding());
    }

    @Test
    void concurrentDistinctPaymentsMatchSerialResult() throws Exception {
        // 10 笔不同还款，每笔 100，并发提交；最终状态必须等于串行（还掉10期，未偿200）
        InMemoryLoanStore concurrentStore = new InMemoryLoanStore();
        LedgerService concurrent = newService(concurrentStore);
        issue(concurrent, "L1");

        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        for (int i = 1; i <= n; i++) {
            final int idx = i;
            Callable<Void> task = () -> {
                PaymentResult r = concurrent.pay("L1", "P" + idx, "key-" + idx,
                        Money.of("100.00"), LocalDate.of(2026, 2, 28), PaymentType.NORMAL);
                if (!r.idempotentReplay()) {
                    accepted.incrementAndGet();
                }
                return null;
            };
            futures.add(pool.submit(task));
        }
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 串行参照
        InMemoryLoanStore serialStore = new InMemoryLoanStore();
        LedgerService serial = newService(serialStore);
        issue(serial, "L1");
        for (int i = 1; i <= n; i++) {
            serial.pay("L1", "P" + i, "key-" + i, Money.of("100.00"),
                    LocalDate.of(2026, 2, 28), PaymentType.NORMAL);
        }

        Loan cl = concurrentStore.get("L1");
        Loan sl = serialStore.get("L1");
        log.info("并发 vs 串行: 并发未偿={} 串行未偿={} 并发流水数={} 串行流水数={}",
                cl.totalOutstanding(), sl.totalOutstanding(),
                cl.getPayments().size(), sl.getPayments().size());
        assertEquals(n, accepted.get());
        assertEquals(sl.totalOutstanding(), cl.totalOutstanding());
        assertEquals(Money.of("200.00"), cl.totalOutstanding());
        assertEquals(sl.getPayments().size(), cl.getPayments().size());
        // 守恒：已还 + 未偿 = 本金（零利率、无逾期）
        assertEquals(Money.of("1200.00"), cl.totalPaid().plus(cl.totalOutstanding()));
    }

    @Test
    void concurrentOverpaymentOnlySucceedsUpToDue() throws Exception {
        // 两笔各 1200（全额）并发：只能有一笔成功，另一笔必然超额拒绝，不能两笔都入账
        InMemoryLoanStore store = new InMemoryLoanStore();
        LedgerService svc = newService(store);
        issue(svc, "L1");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    svc.pay("L1", "FULL" + idx, "full-key-" + idx, Money.of("1200.00"),
                            LocalDate.of(2026, 2, 28), PaymentType.NORMAL);
                    ok.incrementAndGet();
                } catch (LedgerException e) {
                    if (e.getCode() == ErrorCode.PAYMENT_EXCEEDS_DUE
                            || e.getCode() == ErrorCode.LOAN_ALREADY_PAID_OFF) {
                        rejected.incrementAndGet();
                    } else {
                        throw new RuntimeException(e);
                    }
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        log.info("并发两笔全额: ok={} rejected={} 未偿={} 已还={}", ok.get(), rejected.get(),
                store.get("L1").totalOutstanding(), store.get("L1").totalPaid());
        assertEquals(1, ok.get());
        assertEquals(1, rejected.get());
        assertEquals(Money.ZERO, store.get("L1").totalOutstanding());
        assertEquals(Money.of("1200.00"), store.get("L1").totalPaid());
    }
}
