package com.github.highcumontoa.loanservicingledgerjava.tests;

import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.domain.Money;
import com.github.highcumontoa.loanservicingledgerjava.domain.PaymentType;
import com.github.highcumontoa.loanservicingledgerjava.error.ErrorCode;
import com.github.highcumontoa.loanservicingledgerjava.error.LedgerException;
import com.github.highcumontoa.loanservicingledgerjava.service.AccountSnapshot;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逾期判定/宽限期/罚息：跨期逾期、宽限期内不罚息、同一区间不重复计提、
 * 已结清不再逾期/不再产生应还、规则快照不随全局配置变化。
 */
class LedgerOverdueTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerOverdueTest.class);

    private InMemoryLoanStore store;
    private LedgerService svc;
    private LedgerProperties props;

    @BeforeEach
    void setUp() {
        store = new InMemoryLoanStore();
        props = new LedgerProperties();
        svc = new LedgerService(store, new AmortizationService(), props);
    }

    private void issue() {
        svc.disburse("L1", Money.of("1200.00"), BigDecimal.ZERO, 12,
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28),
                3, new BigDecimal("0.0005"));
    }

    @Test
    void withinGraceNoOverdueNoPenalty() {
        issue();
        AccountSnapshot s = svc.settle("L1", LocalDate.of(2026, 3, 3)); // due 2/28 + 3 = 3/3
        assertFalse(s.installments().get(0).overdue());
        assertEquals(Money.ZERO, s.outstandingPenalty());
        log.info("结算日2026-03-03（宽限期最后一天）overdue=false penalty=0: {}", s.basis());
    }

    @Test
    void overdueStartsAfterGraceAndAccruesPerDay() {
        issue();
        AccountSnapshot s = svc.settle("L1", LocalDate.of(2026, 3, 10));
        AccountSnapshot.InstallmentView v = s.installments().get(0);
        assertTrue(v.overdue());
        // 罚息区间 3/4..3/10 = 7 天；基数 100；100*0.0005*7=0.35
        assertEquals(Money.of("0.35"), v.penaltyDue());
        log.info("跨期逾期: overdueDays={} penalty={} 依据={}", v.overdueDays(), v.penaltyDue(), s.basis());
    }

    @Test
    void repeatedSettlementDoesNotDoubleAccrue() {
        issue();
        svc.settle("L1", LocalDate.of(2026, 3, 10));
        svc.settle("L1", LocalDate.of(2026, 3, 10));
        svc.settle("L1", LocalDate.of(2026, 3, 12)); // 追加 2 天
        Money penalty = store.get("L1").getInstallments().get(0).getPenaltyDue();
        // 100*0.0005*9 = 0.45，不是重复计提
        assertEquals(Money.of("0.45"), penalty);
        log.info("多次结算同一区间不重复罚息，9天合计={}", penalty);
    }

    @Test
    void partialPaymentReducesPenaltyBaseGoingForward() {
        issue();
        // 先计 3 天罚息（3/4..3/6），再还 50 本金，之后基数变为 50
        svc.settle("L1", LocalDate.of(2026, 3, 6));
        Money before = store.get("L1").getInstallments().get(0).getPenaltyDue();
        assertEquals(Money.of("0.15"), before); // 100*0.0005*3
        svc.pay("L1", "P1", null, Money.of("50.15"),
                LocalDate.of(2026, 3, 6), PaymentType.NORMAL);
        svc.settle("L1", LocalDate.of(2026, 3, 8)); // 3/7..3/8 两天，基数50
        com.github.highcumontoa.loanservicingledgerjava.domain.Installment ins =
                store.get("L1").getInstallments().get(0);
        // 累计计提 0.15(已还)+0.05=0.20；当前未偿罚息 0.05，未对已还50计罚
        assertEquals(Money.of("0.20"), ins.getPenaltyDue());
        assertEquals(Money.of("0.05"), ins.outstandingPenalty());
        log.info("部分还款后基数下降：后续2天罚息=0.05，未对已还50计罚");
    }

    @Test
    void paidOffLoanNeverOverdueAgain() {
        issue();
        // 全部按正常通道还清
        for (int k = 0; k < 12; k++) {
            svc.pay("L1", "P" + (k + 1), null, Money.of("100.00"),
                    LocalDate.of(2026, 2, 28).plusMonths(k), PaymentType.NORMAL);
        }
        assertEquals(com.github.highcumontoa.loanservicingledgerjava.domain.LoanStatus.PAID_OFF,
                store.get("L1").getStatus());
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.pay("L1", "PX", null, Money.of("1.00"),
                        LocalDate.of(2027, 1, 1), PaymentType.NORMAL));
        assertEquals(ErrorCode.LOAN_ALREADY_PAID_OFF, ex.getCode());
        AccountSnapshot s = svc.settle("L1", LocalDate.of(2027, 1, 1));
        assertEquals(Money.ZERO, s.totalOutstanding());
        assertFalse(s.installments().stream().anyMatch(AccountSnapshot.InstallmentView::overdue));
        log.info("已结清贷款不再逾期、不再产生应还，再次还款 code={}", ex.getCode());
    }

    @Test
    void ruleSnapshotKeepsOldLoanInterpretationStable() {
        // 发放时使用贷款级快照；之后修改全局默认值，不影响既有贷款
        issue();
        props.setGraceDays(0);
        props.setDailyPenaltyRate(new BigDecimal("0.01"));
        AccountSnapshot s = svc.settle("L1", LocalDate.of(2026, 3, 10));
        // 仍按旧快照 grace=3, rate=0.0005 => 7 天 * 100 * 0.0005 = 0.35
        assertEquals(Money.of("0.35"), s.outstandingPenalty());
        log.info("全局规则变更后，既有贷款仍按发放快照解释: penalty={}", s.outstandingPenalty());
    }
}
