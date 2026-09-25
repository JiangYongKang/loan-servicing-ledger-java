package com.github.highcumontoa.loanservicingledgerjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.highcumontoa.loanservicingledgerjava.config.LedgerProperties;
import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import com.github.highcumontoa.loanservicingledgerjava.domain.InstallmentStatus;
import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.domain.LoanStatus;
import com.github.highcumontoa.loanservicingledgerjava.domain.Payment;
import com.github.highcumontoa.loanservicingledgerjava.error.LedgerErrorCode;
import com.github.highcumontoa.loanservicingledgerjava.error.LedgerException;
import com.github.highcumontoa.loanservicingledgerjava.service.AmortizationCalculator;
import com.github.highcumontoa.loanservicingledgerjava.service.LoanLedgerService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 提前结清测试。 */
class EarlyPayoffTest {

    private static final Logger log = LoggerFactory.getLogger(EarlyPayoffTest.class);
    private static final LocalDate DISBURSE_DATE = LocalDate.of(2026, 1, 1);

    private LoanLedgerService service;

    @BeforeEach
    void setUp() {
        service = new LoanLedgerService(new AmortizationCalculator(), new LedgerProperties());
        service.disburse("L1", new BigDecimal("12000"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, new BigDecimal("10"));
    }

    @Test
    void payoffSettlesLoanAndWaivesFutureInterestAndFees() {
        // 先正常还第 1 期
        Loan loan = service.getLoan("L1");
        Installment first = loan.getSchedule().get(0);
        BigDecimal firstDue = first.totalRemaining();
        service.repay("L1", "K1", firstDue, LocalDate.of(2026, 1, 15));

        // 2026-01-20 提前结清: 应付 = 未偿本金 + 已到期未付费用/利息(无, 第1期已付, 第2期未到期)
        BigDecimal principalBefore = service.getLoan("L1").getOutstandingPrincipal();
        Payment payoff = service.payoff("L1", "K-PAYOFF", LocalDate.of(2026, 1, 20));
        log.info("[输入] 提前结清 valueDate=2026-01-20; 结清前本金余额={}", principalBefore);
        log.info("[资金分配] 结清金额={}, 分配明细:", payoff.amount());
        payoff.allocations().forEach(a -> log.info("  期次={} 费用={} 罚息={} 利息={} 本金={}",
                a.period(), a.feeApplied(), a.penaltyApplied(), a.interestApplied(), a.principalApplied()));

        // 结清金额 = 剩余本金(第1期已付, 无已到期未付费用/利息)
        assertEquals(principalBefore, payoff.amount());
        Loan settled = service.getLoan("L1");
        assertEquals(LoanStatus.SETTLED, settled.getStatus());
        assertEquals(new BigDecimal("0.00"), settled.getOutstandingPrincipal());
        for (Installment i : settled.getSchedule()) {
            assertEquals(InstallmentStatus.PAID, i.getStatus());
        }
        // 未到期期次的利息与费用被豁免: 未付也不再计入应还
        BigDecimal waivedInterest = settled.getSchedule().stream()
                .map(i -> i.getInterestDue().subtract(i.getInterestPaid()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("[判定依据] 结清额=未偿本金+已到期未付费用利息; 豁免未到期利息合计={}", waivedInterest);
        assertTrue2(waivedInterest.signum() > 0);
    }

    @Test
    void payoffIncludesOverduePenaltyUpToValueDate() {
        // 逾期到 2026-02-10 后结清: 结清额含罚息
        Payment payoff = service.payoff("L1", "K-PAYOFF", LocalDate.of(2026, 2, 10));
        Loan loan = service.getLoan("L1");
        Installment first = loan.getSchedule().get(0);
        log.info("[输入] 逾期后提前结清 valueDate=2026-02-10; 期1罚息={}", first.getPenaltyAccrued());
        assertTrue2(first.getPenaltyAccrued().signum() > 0);
        assertEquals(new BigDecimal("0.00"), first.remainingPenalty());
        assertEquals(LoanStatus.SETTLED, loan.getStatus());
        log.info("[判定依据] 结清前先按结算日计提罚息, 结清额=本金+已到期费用/利息/罚息, 总额={}", payoff.amount());
    }

    @Test
    void payoffIsIdempotentAndSettledLoanRejectsFurtherOps() {
        Payment p1 = service.payoff("L1", "K-PAYOFF", LocalDate.of(2026, 1, 20));
        Payment p2 = service.payoff("L1", "K-PAYOFF", LocalDate.of(2026, 1, 20));
        assertEquals(p1.paymentId(), p2.paymentId());
        assertEquals(1, service.getLoan("L1").getPayments().size());

        // 结清后拒绝新的还款与结清
        LedgerException repayEx = assertThrows(LedgerException.class,
                () -> service.repay("L1", "K2", new BigDecimal("100"), LocalDate.of(2026, 1, 21)));
        assertEquals(LedgerErrorCode.LOAN_NOT_ACTIVE, repayEx.getCode());
        LedgerException payoffEx = assertThrows(LedgerException.class,
                () -> service.payoff("L1", "K-PAYOFF-2", LocalDate.of(2026, 1, 21)));
        assertEquals(LedgerErrorCode.LOAN_NOT_ACTIVE, payoffEx.getCode());

        // 结清后逾期推进为无操作
        service.accrueOverdue("L1", LocalDate.of(2027, 6, 1));
        for (Installment i : service.getLoan("L1").getSchedule()) {
            assertEquals(new BigDecimal("0.00"), i.getPenaltyAccrued());
        }
        log.info("[判定依据] 结清幂等; 结清后还款/再结清->LOAN_NOT_ACTIVE; 逾期推进无操作");
    }

    private static void assertTrue2(boolean b) {
        org.junit.jupiter.api.Assertions.assertTrue(b);
    }
}
