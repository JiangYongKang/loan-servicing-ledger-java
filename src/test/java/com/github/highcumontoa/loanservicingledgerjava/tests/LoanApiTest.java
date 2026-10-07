package com.github.highcumontoa.loanservicingledgerjava.tests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** REST 入口层测试：验证 API 与服务层结论一致、错误原因码可区分。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoanApiTest {

    private static final Logger log = LoggerFactory.getLogger(LoanApiTest.class);

    @Autowired
    private MockMvc mvc;

    @Test
    @Order(1)
    void disburseViaApi() throws Exception {
        String body = """
                {"loanId":"API-L1","principal":12000,"annualRate":0.12,
                 "termMonths":12,"disbursementDate":"2026-01-01","monthlyFee":10}
                """;
        log.info("[输入] POST /loans {}", body.replaceAll("\\s+", ""));
        mvc.perform(post("/loans").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.outstandingPrincipal").value(12000.00))
                .andExpect(jsonPath("$.schedule.length()").value(12));
    }

    @Test
    @Order(2)
    void repayViaApiAndReplay() throws Exception {
        String body = """
                {"idempotencyKey":"API-K1","amount":500,"valueDate":"2026-01-15"}
                """;
        mvc.perform(post("/loans/API-L1/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value("PAY-API-L1-1"))
                .andExpect(jsonPath("$.amount").value(500.00));
        // 重复提交: 返回同一笔流水, 不重复入账
        mvc.perform(post("/loans/API-L1/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value("PAY-API-L1-1"));
        mvc.perform(get("/loans/API-L1"))
                .andExpect(jsonPath("$.payments.length()").value(1));
        log.info("[判定依据] API 层幂等与服务层一致: 重复提交返回首次流水");
    }

    @Test
    @Order(3)
    void errorCodesAreDistinguishableViaApi() throws Exception {
        // 非法金额
        mvc.perform(post("/loans/API-L1/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"API-K2\",\"amount\":-1,\"valueDate\":\"2026-01-15\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        // 未知贷款
        mvc.perform(post("/loans/NOPE/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"API-K3\",\"amount\":1,\"valueDate\":\"2026-01-15\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_NOT_FOUND"));
        // 同键不同额 -> 冲突
        mvc.perform(post("/loans/API-L1/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"API-K1\",\"amount\":999,\"valueDate\":\"2026-01-15\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONCURRENT_CONFLICT"));
        log.info("[判定依据] INVALID_AMOUNT / LOAN_NOT_FOUND / CONCURRENT_CONFLICT 在 API 层可区分");
    }

    @Test
    @Order(4)
    void reconcileViaApi() throws Exception {
        mvc.perform(get("/loans/API-L1/reconciliation").param("asOf", "2026-01-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.subjects.length()").value(4))
                .andExpect(jsonPath("$.cash.cashReceived").value(500.00))
                .andExpect(jsonPath("$.cash.totalApplied").value(500.00))
                .andExpect(jsonPath("$.discrepancies.length()").value(0));
        log.info("[判定依据] 对账入口: 应收=已还+未偿+减免 且 现金=已冲抵 -> balanced=true");
    }

    @Test
    @Order(5)
    void disburseWithPerLoanRulesViaApi() throws Exception {
        String body = """
                {"loanId":"API-L2","principal":1000,"annualRate":0.12,
                 "termMonths":6,"disbursementDate":"2026-01-01",
                 "gracePeriodDays":10,"penaltyDailyRate":0.001}
                """;
        log.info("[输入] POST /loans {}", body.replaceAll("\\s+", ""));
        mvc.perform(post("/loans").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gracePeriodDays").value(10))
                .andExpect(jsonPath("$.penaltyDailyRate").value(0.001));
        log.info("[判定依据] 放款时单笔指定宽限/罚息利率, 视图中固化可见");
    }
}
