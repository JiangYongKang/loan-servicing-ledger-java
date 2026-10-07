package com.github.highcumontoa.loanservicingledgerjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.highcumontoa.loanservicingledgerjava.config.LedgerProperties;
import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.domain.LoanStatus;
import com.github.highcumontoa.loanservicingledgerjava.domain.Payment;
import com.github.highcumontoa.loanservicingledgerjava.service.AmortizationCalculator;
import com.github.highcumontoa.loanservicingledgerjava.service.LoanLedgerService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 提前结清：未到期利息/费用逐期逐项记为减免，结清后各期未偿合计归零。 */
class PayoffWaiverTest {

    private static final Logger log = LoggerFactory.getLogger(PayoffWaiverTest.class);
    private static final LocalDate DISBURSE_DATE = LocalDate.of(2026, 1, 1);

    private LoanLedgerService service;

    @BeforeEach
    void setUp() {
        service = new LoanLedgerService(new AmortizationCalculator(), new LedgerProperties());
        service.disburse("L1", new BigDecimal("12000"), new BigDecimal("0.12"),
                12, DISBURSE_DATE, new BigDecimal("10"));
    }

    @Test
    void payoffRecordsWaiversPerInstallmentAndZeroesOutstanding() {
        // 还掉第 1 期后在 2026-01-20 结清: 第 2..12 期未到期, 其利息/费用应记为减免
        Loan loan = service.getLoan("L1");
        Installment first = loan.getSchedule().get(0);
        service.repay("L1", "K1", first.totalRemaining(), LocalDate.of(2026, 1, 15));

        Payment payoff = service.payoff("L1", "K-PAYOFF", LocalDate.of(2026, 1, 20));
        log.info("[输入] 提前结清 valueDate=2026-01-20, 结清金额={}", payoff.amount());

        Loan settled = service.getLoan("L1");
        assertEquals(LoanStatus.SETTLED, settled.getStatus());

        // 逐期查减免明细: 第1期已付清无减免; 第2..12期利息与费用全额减免
        BigDecimal totalWaivedInterest = BigDecimal.ZERO;
        BigDecimal totalWaivedFee = BigDecimal.ZERO;
        for (Installment inst : settled.getSchedule()) {
            log.info("[减免明细] 期次={} 到期日={} 利息减免={} 费用减免={}",
                    inst.getPeriod(), inst.getDueDate(), inst.getInterestWaived(), inst.getFeeWaived());
            totalWaivedInterest = totalWaivedInterest.add(inst.getInterestWaived());
            totalWaivedFee = totalWaivedFee.add(inst.getFeeWaived());
            if (inst.getDueDate().isAfter(LocalDate.of(2026, 1, 20))) {
                // 减免额 = 结清时该期未付的利息/费用(第1期已付清->0, 其余期全额)
                assertEquals(inst.getInterestDue().subtract(inst.getInterestPaid()),
                        inst.getInterestWaived());
                assertEquals(inst.getFeeDue().subtract(inst.getFeePaid()), inst.getFeeWaived());
            } else {
                assertEquals(new BigDecimal("0.00"), inst.getInterestWaived());
                assertEquals(new BigDecimal("0.00"), inst.getFeeWaived());
            }
        }
        log.info("[判定依据] 未到期11期: 利息减免合计={}, 费用减免合计={}(11期*10)",
                totalWaivedInterest, totalWaivedFee);
        assertEquals(new BigDecimal("110.00"), totalWaivedFee);
        assertEquals(0, totalWaivedInterest.signum() > 0 ? 0 : 1, "未到期利息应有减免");

        // 结清后所有期次未偿加起来必须是 0
        BigDecimal totalOutstanding = settled.getSchedule().stream()
                .map(Installment::totalRemaining).reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("[判定依据] 结清后各期未偿合计={}", totalOutstanding);
        assertEquals(new BigDecimal("0.00"), totalOutstanding);
        assertEquals(new BigDecimal("0.00"), settled.getOutstandingPrincipal());

        // 结清后推进逾期: 不再产生新的应还、不再逾期
        service.accrueOverdue("L1", LocalDate.of(2027, 6, 1));
        for (Installment inst : service.getLoan("L1").getSchedule()) {
            assertEquals(new BigDecimal("0.00"), inst.totalRemaining());
            assertEquals(new BigDecimal("0.00"), inst.getPenaltyAccrued());
        }
        log.info("[判定依据] 结清后逾期推进为无操作, 未偿合计保持 0, 无新罚息");
    }

    @Test
    void payoffWaivesOnlyUndueInstallments() {
        // 逾期到 2026-03-10 结清: 第1、2期已到期(费用/利息/罚息照收), 第3期起未到期记减免
        Payment payoff = service.payoff("L1", "K-PAYOFF", LocalDate.of(2026, 3, 10));
        Loan loan = service.getLoan("L1");
        log.info("[输入] 逾期后结清 valueDate=2026-03-10, 结清金额={}", payoff.amount());

        for (Installment inst : loan.getSchedule()) {
            if (inst.getDueDate().isAfter(LocalDate.of(2026, 3, 10))) {
                assertEquals(inst.getInterestDue(), inst.getInterestWaived());
                assertEquals(inst.getFeeDue(), inst.getFeeWaived());
            } else {
                // 已到期期次: 利息/费用/罚息全部实收, 无减免
                assertEquals(new BigDecimal("0.00"), inst.getInterestWaived());
                assertEquals(new BigDecimal("0.00"), inst.getFeeWaived());
                assertEquals(new BigDecimal("0.00"), inst.remainingInterest());
                assertEquals(new BigDecimal("0.00"), inst.remainingFee());
                assertEquals(new BigDecimal("0.00"), inst.remainingPenalty());
            }
        }
        BigDecimal totalOutstanding = loan.getSchedule().stream()
                .map(Installment::totalRemaining).reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("[判定依据] 已到期期次无减免且全额实收; 未到期期次全额减免; 未偿合计={}", totalOutstanding);
        assertEquals(new BigDecimal("0.00"), totalOutstanding);
    }
}
