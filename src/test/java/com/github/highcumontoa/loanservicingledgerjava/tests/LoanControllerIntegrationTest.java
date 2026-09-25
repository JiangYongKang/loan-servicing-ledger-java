package com.github.highcumontoa.loanservicingledgerjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端：HTTP 入口、错误码归类与可区分性、幂等重放标志。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoanControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void fullFlowOverHttpWithDistinctErrorCodes() throws Exception {
        // 发放
        mvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content("""
                {"loanId":"W1","principal":"1200.00","annualRate":0,"termMonths":12,
                 "originationDate":"2026-01-31","firstDueDate":"2026-02-28",
                 "graceDays":3,"dailyPenaltyRate":0.0005}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.installments.length()").value(12));

        // 重复发放 -> 409 LOAN_ALREADY_EXISTS
        mvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content("""
                {"loanId":"W1","principal":"1200.00","annualRate":0,"termMonths":12,
                 "originationDate":"2026-01-31","firstDueDate":"2026-02-28"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_ALREADY_EXISTS"));

        // 非正金额 -> 422
        mvc.perform(post("/api/loans/W1/payments").contentType(MediaType.APPLICATION_JSON).content("""
                {"paymentId":"P0","idempotencyKey":"k0","amount":"0","valueDate":"2026-02-28"}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_NON_POSITIVE"));

        // 正常还款 100
        mvc.perform(post("/api/loans/W1/payments").contentType(MediaType.APPLICATION_JSON).content("""
                {"paymentId":"P1","idempotencyKey":"k1","amount":"100.00","valueDate":"2026-02-28"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.idempotentReplay").value(false))
                .andExpect(jsonPath("$.allocation[0].principalPaid").value("100.00"));

        // 幂等重放
        mvc.perform(post("/api/loans/W1/payments").contentType(MediaType.APPLICATION_JSON).content("""
                {"paymentId":"P1","idempotencyKey":"k1","amount":"100.00","valueDate":"2026-02-28"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        // 同键不同金额 -> 409 PAYMENT_CONFLICT
        mvc.perform(post("/api/loans/W1/payments").contentType(MediaType.APPLICATION_JSON).content("""
                {"paymentId":"P1","idempotencyKey":"k1","amount":"101.00","valueDate":"2026-02-28"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_CONFLICT"));

        // 超额 -> 422 PAYMENT_EXCEEDS_DUE
        mvc.perform(post("/api/loans/W1/payments").contentType(MediaType.APPLICATION_JSON).content("""
                {"paymentId":"P2","idempotencyKey":"k2","amount":"999999","valueDate":"2026-02-28"}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAYMENT_EXCEEDS_DUE"));

        // 不存在贷款 -> 404
        mvc.perform(get("/api/loans/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_NOT_FOUND"));

        // 结算：逾期快照
        mvc.perform(post("/api/loans/W1/settle?date=2026-03-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installments[0].overdue").value(false)) // 首期已清
                .andExpect(jsonPath("$.installments[1].overdue").value(false)); // 二期未到期
    }
}
