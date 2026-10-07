package com.github.highcumontoa.loanservicingledgerjava.service;

import com.github.highcumontoa.loanservicingledgerjava.domain.Installment;
import com.github.highcumontoa.loanservicingledgerjava.domain.Loan;
import com.github.highcumontoa.loanservicingledgerjava.domain.Payment;
import com.github.highcumontoa.loanservicingledgerjava.domain.PaymentAllocation;
import com.github.highcumontoa.loanservicingledgerjava.domain.reconciliation.ComponentBalance;
import com.github.highcumontoa.loanservicingledgerjava.domain.reconciliation.Discrepancy;
import com.github.highcumontoa.loanservicingledgerjava.domain.reconciliation.ReconciliationReport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 对账器：按结算日导出单笔贷款的对账结果。
 *
 * <p>两面各用<b>独立来源</b>的数字交叉核对，而不是从同一组字段派生（否则恒等是同义反复）：
 * <ul>
 *   <li>应收侧：历期应收（计划/已计提罚息，来自期次）==
 *       已冲抵（来自资金分配流水 PaymentAllocation）+ 未偿（来自期次余额）
 *       + 减免（来自期次减免字段）。逐科目 PRINCIPAL/INTEREST/FEE/PENALTY 各自平衡。</li>
 *   <li>现金侧：截至结算日（valueDate &le; asOfDate）实际入账现金（Payment.amount）
 *       == 这些流水已冲抵合计（PaymentAllocation 汇总）。账本自身恒平；
 *       externalCashReceived 非 null 时用外部现金口径对比并指出差额。</li>
 * </ul>
 */
@Component
public class Reconciler {

    private static final String SIDE_RECEIVABLE = "RECEIVABLE";
    private static final String SIDE_CASH = "CASH";

    public ReconciliationReport build(Loan loan, LocalDate asOfDate,
                                      BigDecimal externalCashReceived) {
        BigDecimal dueP = z(), dueI = z(), dueF = z(), duePen = z();
        BigDecimal outP = z(), outI = z(), outF = z(), outPen = z();
        BigDecimal wvI = z(), wvF = z(), wvPen = z();

        for (Installment inst : loan.getSchedule()) {
            dueP = dueP.add(inst.getPrincipalDue());
            dueI = dueI.add(inst.getInterestDue());
            dueF = dueF.add(inst.getFeeDue());
            duePen = duePen.add(inst.getPenaltyAccrued());

            outP = outP.add(inst.remainingPrincipal());
            outI = outI.add(inst.remainingInterest());
            outF = outF.add(inst.remainingFee());
            outPen = outPen.add(inst.remainingPenalty());

            wvI = wvI.add(inst.getInterestWaived());
            wvF = wvF.add(inst.getFeeWaived());
            wvPen = wvPen.add(inst.getPenaltyWaived());
        }

        // 已冲抵与现金都取自独立的资金分配流水，只统计 valueDate <= asOfDate 的入账
        BigDecimal paidP = z(), paidI = z(), paidF = z(), paidPen = z();
        BigDecimal cashReceived = z();
        BigDecimal cashApplied = z();
        for (Payment payment : loan.getPayments()) {
            if (!payment.valueDate().isAfter(asOfDate)) {
                cashReceived = cashReceived.add(payment.amount());
                for (PaymentAllocation a : payment.allocations()) {
                    cashApplied = cashApplied.add(a.total());
                    paidF = paidF.add(a.feeApplied());
                    paidPen = paidPen.add(a.penaltyApplied());
                    paidI = paidI.add(a.interestApplied());
                    paidP = paidP.add(a.principalApplied());
                }
            }
        }
        if (externalCashReceived != null) {
            cashReceived = externalCashReceived.setScale(2, java.math.RoundingMode.HALF_UP);
        }
        BigDecimal cashDifference = cashReceived.subtract(cashApplied);

        List<ComponentBalance> components = List.of(
                component("PRINCIPAL", dueP, paidP, outP, z()),
                component("INTEREST", dueI, paidI, outI, wvI),
                component("FEE", dueF, paidF, outF, wvF),
                component("PENALTY", duePen, paidPen, outPen, wvPen));

        BigDecimal totalDue = dueP.add(dueI).add(dueF).add(duePen);
        BigDecimal totalPaid = paidP.add(paidI).add(paidF).add(paidPen);
        BigDecimal totalOutstanding = outP.add(outI).add(outF).add(outPen);
        BigDecimal totalWaived = wvI.add(wvF).add(wvPen);

        List<Discrepancy> discrepancies = new ArrayList<>();
        boolean receivablesBalanced = true;
        for (ComponentBalance c : components) {
            if (!c.balanced()) {
                receivablesBalanced = false;
                discrepancies.add(new Discrepancy(SIDE_RECEIVABLE, c.component(),
                        c.due(), "应收",
                        c.paid().add(c.outstanding()).add(c.waived()),
                        "已冲抵+未偿+减免", c.difference()));
            }
        }
        boolean cashBalanced = cashDifference.signum() == 0;
        if (!cashBalanced) {
            discrepancies.add(new Discrepancy(SIDE_CASH, "CASH",
                    cashReceived, "实际入账现金",
                    cashApplied, "已冲抵合计", cashDifference));
        }

        return new ReconciliationReport(loan.getLoanId(), asOfDate, loan.getStatus().name(),
                components, totalDue, totalPaid, totalOutstanding, totalWaived,
                cashReceived, cashApplied, cashDifference,
                receivablesBalanced, cashBalanced,
                receivablesBalanced && cashBalanced, List.copyOf(discrepancies));
    }

    private ComponentBalance component(String name, BigDecimal due, BigDecimal paid,
                                       BigDecimal outstanding, BigDecimal waived) {
        BigDecimal difference = due.subtract(paid).subtract(outstanding).subtract(waived);
        return new ComponentBalance(name, due, paid, outstanding, waived, difference);
    }

    private static BigDecimal z() {
        return BigDecimal.ZERO.setScale(2);
    }
}
