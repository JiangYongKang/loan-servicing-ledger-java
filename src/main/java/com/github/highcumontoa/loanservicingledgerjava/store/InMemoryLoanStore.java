package com.github.highcumontoa.loanservicingledgerjava.store;

import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.domain.Payment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 本地内存账本仓库。
 *
 * <p>一致性保证：
 * <ul>
 *   <li>每笔贷款一把可重入锁：贷款的全部“读-结算-分配-写回”在同一临界区完成，
 *       并发还款被串行化，等价于逐笔串行；</li>
 *   <li>每个还款幂等键一把锁：还款流程固定按“键锁 → 贷款锁”顺序加锁（全局一致、无死锁），
 *       在同一临界区内完成 查重/校验/资金分配/索引登记；若业务校验失败，索引与账户状态
 *       都不会落盘，因此不会出现“钱已动但键冲突”的脏状态；</li>
 *   <li>幂等键与 paymentId 双映射用一把短临界区锁原子提交，
 *       同一笔还款全局只能入账一次；paymentId 跨键复用被明确拒绝。</li>
 * </ul>
 */
@Component
public class InMemoryLoanStore {

    private final ConcurrentHashMap<String, Loan> loans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> loanLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> keyLocks = new ConcurrentHashMap<>();

    /** 幂等键 -> 已入账流水 */
    private final ConcurrentHashMap<String, Payment> paymentByKey = new ConcurrentHashMap<>();
    /** paymentId -> 幂等键，保证 paymentId 也不重复 */
    private final ConcurrentHashMap<String, String> keyByPaymentId = new ConcurrentHashMap<>();
    private final Object indexLock = new Object();

    public Loan putIfAbsent(Loan loan) {
        Loan existing = loans.putIfAbsent(loan.getLoanId(), loan);
        loanLocks.computeIfAbsent(loan.getLoanId(), k -> new ReentrantLock());
        return existing;
    }

    public Loan get(String loanId) {
        return loans.get(loanId);
    }

    public List<Loan> all() {
        return new ArrayList<>(loans.values());
    }

    /** 在指定贷款的独占锁内执行动作。 */
    public <T> T withLoanLock(String loanId, Supplier<T> action) {
        ReentrantLock lock = loanLocks.computeIfAbsent(loanId, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在“还款键锁 + 贷款锁”双锁内执行还款动作（固定加锁顺序：先键后贷）。
     * 动作返回 null 表示业务上需要走“已存在流水（重放/冲突）”分支时由调用方另行处理；
     * 动作抛异常时不会有任何索引/状态残留（登记在动作内最后一步完成）。
     */
    public <T> T withPaymentLock(String canonicalKey, String loanId, Supplier<T> action) {
        ReentrantLock keyLock = keyLocks.computeIfAbsent(canonicalKey, k -> new ReentrantLock());
        ReentrantLock loanLock = loanLocks.computeIfAbsent(loanId, k -> new ReentrantLock());
        keyLock.lock();
        try {
            loanLock.lock();
            try {
                return action.get();
            } finally {
                loanLock.unlock();
            }
        } finally {
            keyLock.unlock();
        }
    }

    public Payment findPaymentByKey(String canonicalKey) {
        return paymentByKey.get(canonicalKey);
    }

    /** 按 paymentId 查找已入账流水（用于跨键 paymentId 复用检测）。 */
    public Payment findPaymentByPaymentId(String paymentId) {
        String ownerKey = keyByPaymentId.get(paymentId);
        return ownerKey == null ? null : paymentByKey.get(ownerKey);
    }

    /** 还款索引登记结果。 */
    public enum CommitResult {
        OK,
        DUPLICATE_KEY,
        PAYMENT_ID_TAKEN
    }

    /**
     * 原子登记一笔新还款（双映射：幂等键、paymentId）。必须在键锁内调用。
     */
    public CommitResult commitPayment(String canonicalKey, Payment payment) {
        synchronized (indexLock) {
            if (paymentByKey.containsKey(canonicalKey)) {
                return CommitResult.DUPLICATE_KEY;
            }
            String ownerKey = keyByPaymentId.get(payment.getPaymentId());
            if (ownerKey != null && !ownerKey.equals(canonicalKey)) {
                return CommitResult.PAYMENT_ID_TAKEN;
            }
            paymentByKey.put(canonicalKey, payment);
            keyByPaymentId.put(payment.getPaymentId(), canonicalKey);
            return CommitResult.OK;
        }
    }
}
