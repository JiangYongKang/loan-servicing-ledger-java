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
    void disburseWithCustomRulesAndReconcileViaApi() throws Exception {
        // 放款时单笔指定宽限期/罚息日利率；留空字段则沿用全局默认
        String body = """
                {"loanId":"API-L2","principal":1200,"annualRate":0.12,
                 "termMonths":12,"disbursementDate":"2026-01-01","monthlyFee":0,
                 "gracePeriodDays":1,"penaltyDailyRate":0.001}
                """;
        log.info("[输入] POST /loans 单笔规则 宽限=1 罚息日利率=0.001");
        mvc.perform(post("/loans").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules.gracePeriodDays").value(1))
                .andExpect(jsonPath("$.rules.penaltyDailyRate").value(0.001))
                .andExpect(jsonPath("$.rules.customGracePeriod").value(true))
                .andExpect(jsonPath("$.rules.customPenaltyRate").value(true))
                .andExpect(jsonPath("$.waivers.length()").value(0));

        // 对账入口：平衡时 balanced=true、无 discrepancies
        mvc.perform(get("/loans/API-L2/reconciliation").param("asOfDate", "2026-01-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.receivablesBalanced").value(true))
                .andExpect(jsonPath("$.cashBalanced").value(true))
                .andExpect(jsonPath("$.discrepancies.length()").value(0))
                .andExpect(jsonPath("$.components.length()").value(4));

        // 传入外部现金口径 cashReceived=0（实际有入账0，也是0；这里改为对已还款贷款做不平验证）
        // 先还款，再用偏小的外部现金触发现金侧不平
        mvc.perform(post("/loans/API-L2/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"API-RK1\",\"amount\":100,\"valueDate\":\"2026-01-15\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/loans/API-L2/reconciliation")
                        .param("asOfDate", "2026-02-10").param("cashReceived", "90.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(false))
                .andExpect(jsonPath("$.cashBalanced").value(false))
                .andExpect(jsonPath("$.discrepancies[0].side").value("CASH"))
                .andExpect(jsonPath("$.discrepancies[0].difference").value(-10.00));
        log.info("[判定依据] 单笔规则随放款固化返回; 对账平/不平可区分, 现金差额被定位到 side=CASH, 差-10.00");
    }
}
