package com.github.highcumontoa.loanservicingledgerjava.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一笔还款流水（入账后不可变）。
 *
 * <p>重复提交语义：以 idempotencyKey（为空时退化为 paymentId）作为唯一去重键。
 * 同一键第一次请求成功入账；之后任何请求：
 * <ul>
 *   <li>若 loanId/金额/类型与首次完全一致 &rarr; 返回首次的入账结果（幂等重放）；</li>
 *   <li>若内容不一致 &rarr; 拒绝（PAYMENT_CONFLICT），不会二次入账。</li>
 * </ul>
 */
public class Payment {

    private String paymentId;
    private String loanId;
    private String idempotencyKey;
    private Money amount;
    private LocalDate valueDate;
    private PaymentType type;
    private PaymentStatus status;
    private LocalDateTime acceptedAt;
    /** 首次入账时的资金分配结果，幂等重放原样返回（内存账本中直接持有）。 */
    private Allocation allocation;

    public Payment() {
    }

    public Payment(String paymentId, String loanId, String idempotencyKey, Money amount,
                   LocalDate valueDate, PaymentType type, PaymentStatus status,
                   LocalDateTime acceptedAt) {
        this.paymentId = paymentId;
        this.loanId = loanId;
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.valueDate = valueDate;
        this.type = type;
        this.status = status;
        this.acceptedAt = acceptedAt;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getLoanId() {
        return loanId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Money getAmount() {
        return amount;
    }

    public LocalDate getValueDate() {
        return valueDate;
    }

    public PaymentType getType() {
        return type;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public LocalDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public Allocation getAllocation() {
        return allocation;
    }

    public void attachAllocation(Allocation a) {
        this.allocation = a;
    }
}
