# 本地贷款账本与还款处理（Loan Servicing Ledger）

一个基于 Spring Boot 的**本地内存贷款账本**，用确定性的摊销、计息、罚息与资金分配规则，
模拟贷款账户、摊销计划与还款流水，使 **正常还款 / 部分还款 / 提前结清 / 逾期处理**
在任何入口（REST、结算、还款）下都得到**一致且可解释**的结论。

所有金额使用 `BigDecimal`（2 位小数，`HALF_UP`），不使用 `double`；账户任何余额不允许为负。

---

## 1. 摊销与计息模型

### 1.1 摊销计划（等额本息，可复现）

发放时立即生成完整摊销计划，相同输入必然得到相同计划：

- 月利率 `r = 年利率 / 12`（内部 10 位精度）；
- 每期应还（本息合计）
  `PMT = P · r · (1+r)^n / ((1+r)^n − 1)`，2 位 `HALF_UP`；
- 每期利息 `= 期初剩余本金 × r`，每期本金 `= PMT − 利息`；
- **末期吸收全部尾差**：末期本金 = 剩余全部本金，保证 `Σ本金 = 发放本金`，不出现负数；
- 年利率为 0 时：每期本金 `= P/n`（末期同样吸收尾差），利息恒为 0；
- 到期日由 `firstDueDate` 按月顺延。

### 1.2 利息

- 计划利息在发放时即固定（规则快照），不会因逾期而重复计收；
- **同一期次绝不重复计息**；
- 提前结清时，**未到期期次的计划利息被免除**（在期次上以 `interestWaived` 留痕），
  已到期未付利息仍需支付。

### 1.3 逾期判定与罚息（基于显式结算时间）

所有判定都以请求中显式传入的 `valueDate / settlementDate` 为准，**不依赖系统时钟**：

- 期次 `i` 逾期当且仅当：`结算日 > 到期日 + graceDays` 且该期未结清；
- `到期日 + graceDays` 当天仍在宽限期内，**不算逾期、不计罚息**；
- 罚息起算日 = `到期日 + graceDays + 1`；
- **罚息基数 = 计提时点该期“未偿本金 + 未偿利息”**；
- `罚息 = 基数 × 日罚息率 × 天数`（天数为闭区间天数，2 位 `HALF_UP`）；
- 罚息按 `PenaltyCharge` 流水**增量计提**，区间
  `[penaltyAccruedThrough+1 .. 结算日]`，**同一日期区间绝不重复计提**；
- 部分还款会把计提水位推进到还款日：还款前对“当时仍欠基数”计罚，
  还款日后按新基数继续，**不会对已偿还部分计罚**；
- 已结清的期次不再计提；**已结清的贷款不再逾期、不再产生任何新应还**。

### 1.4 规则快照（解释稳定可复现）

`年利率 / 宽限期天数 / 日罚息率` 在**发放时固化到贷款**上。之后即使修改全局默认配置，
既有贷款的摊销、利息、罚息与逾期解释**完全不变**；新规则只影响之后新发放的贷款。

---

## 2. 资金分配顺序（固定、可解释）

还款采用瀑布式分配，顺序确定，结果随响应/日志逐期返回：

1. **按期次先后**（第 1 期 → 第 n 期）；
2. 每期内部固定顺序：**罚息 → 利息 → 本金**；
3. 任意一项的冲抵额都不超过其应收，余额永远不为负；
4. 不允许静默忽略：
   - **部分还款**：允许，按上述顺序吃掉可用金额，剩余继续欠；
   - **超额支付（NORMAL）**：拒绝，`PAYMENT_EXCEEDS_DUE`（不隐式入账溢缴款）；
   - **非正金额**：拒绝，`PAYMENT_AMOUNT_NON_POSITIVE`；
   - **当前状态不允许的操作**（如已结清再还）：拒绝，`LOAN_ALREADY_PAID_OFF`。

提前结清（`EARLY_PAYOFF`）必须先取报价，并**恰好按报价金额**提交，否则
`PAYOFF_AMOUNT_MISMATCH`。报价口径：

> 截至结算日已计未付罚息 ＋ 已到期未付利息 ＋ 全部剩余本金；**未到期利息免除**。

---

## 3. 重复提交语义

以 `idempotencyKey` 作为去重键；未提供时退化为 `paymentId`。

- 同一键**首次**请求成功：入账，返回 `idempotentReplay=false`；
- 同一键**再次**请求：
  - `loanId / paymentId / 金额 / 类型` 与首次**完全一致** → 幂等重放，
    返回首次流水与首次资金分配，`idempotentReplay=true`，**不二次入账**；
  - 任一内容**不一致** → 拒绝 `PAYMENT_CONFLICT`；
- `paymentId` 被另一个幂等键占用 → 拒绝 `PAYMENT_CONFLICT`（无法安全合并）；
- 极端并发下同键抢注 → 拒绝 `PAYMENT_DUPLICATED`。

---

## 4. 并发一致性保证

- **每笔贷款一把可重入锁**：贷款的“读 → 结算/计提 → 资金分配 → 写回”在同一临界区完成，
  并发还款被**串行化**，最终状态与逐笔串行完全一致；不同贷款互不阻塞；
- **每个还款键一把锁**：还款流程固定按 **键锁 → 贷款锁** 顺序加锁（全局一致，无死锁）；
- 临界区内顺序为：**查重（键 + paymentId）→ 业务校验 → 只读规划 → 幂等索引登记
  → 罚息计提 → 应用分配 → 守恒校验**。业务校验失败发生在任何账户变更与索引登记之前，
  因此拒绝不会留下流水、不会产生罚息、不会改动余额；
- 幂等键与 paymentId 用 `ConcurrentHashMap.putIfAbsent` 风格双映射原子登记，
  同一笔还款全局只能入账一次。

### 守恒不变量（每次入账后强制校验，失败抛 `INTERNAL_CONSISTENCY`）

1. 现金守恒：`还款流水金额之和 = 各期已还(本+息+罚息)之和`；
2. 应收守恒：`历期应收(本+息+罚息) = 已还 + 当前未偿 + 已减免利息`；
3. 任一期、任一科目未偿额 ≥ 0，不允许负数余额。

---

## 5. HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/loans` | 发放并生成摊销计划 |
| GET  | `/api/loans/{id}` | 贷款、摊销计划、当前未偿 |
| POST | `/api/loans/{id}/settle?date=yyyy-MM-dd` | 按结算日推进逾期/罚息，返回快照与判定依据 |
| GET  | `/api/loans/{id}/payoff?date=yyyy-MM-dd` | 提前结清报价（含口径说明） |
| POST | `/api/loans/{id}/payments` | 还款（NORMAL / EARLY_PAYOFF，幂等） |
| GET  | `/api/loans/{id}/payments` | 还款流水 |

错误响应统一为 `{"code":"<错误码>","message":"..."}`，HTTP 状态：
404（不存在）、409（冲突/重复/已结清）、422（金额/参数/状态非法）、500（内部守恒错误）。
调用方应依据稳定的 `code` 字符串分支，而非 `message` 文本。

错误码：`LOAN_NOT_FOUND, LOAN_ALREADY_EXISTS, INVALID_AMOUNT, INVALID_PARAMETER,
PAYMENT_AMOUNT_NON_POSITIVE, PAYMENT_EXCEEDS_DUE, PAYOFF_AMOUNT_MISMATCH,
LOAN_ALREADY_PAID_OFF, PAYMENT_DUPLICATED, PAYMENT_CONFLICT,
SETTLEMENT_BEFORE_ORIGINATION, INTERNAL_CONSISTENCY`。

### 示例

```bash
# 发放：1200 元、0 利率、12 期（每期 100），宽限 3 天，日罚息率 0.0005
curl -s -X POST localhost:8080/api/loans -H 'Content-Type: application/json' -d '{
  "loanId":"L1","principal":"1200.00","annualRate":0,"termMonths":12,
  "originationDate":"2026-01-31","firstDueDate":"2026-02-28",
  "graceDays":3,"dailyPenaltyRate":0.0005}'

# 正常还款 100
curl -s -X POST localhost:8080/api/loans/L1/payments -H 'Content-Type: application/json' -d '{
  "paymentId":"P1","idempotencyKey":"k1","amount":"100.00",
  "valueDate":"2026-02-28","type":"NORMAL"}'

# 逾期结算（宽限期后）
curl -s -X POST 'localhost:8080/api/loans/L1/settle?date=2026-03-10'

# 提前结清报价 + 按报价结清
curl -s 'localhost:8080/api/loans/L1/payoff?date=2026-03-10'
curl -s -X POST localhost:8080/api/loans/L1/payments -H 'Content-Type: application/json' -d '{
  "paymentId":"PF","idempotencyKey":"kf","amount":"<报价totalPayoff>",
  "valueDate":"2026-03-10","type":"EARLY_PAYOFF"}'
```

---

## 6. 可配置项

`src/main/resources/application.properties`：

```properties
# 仅作为“发放”时的缺省值，发放后快照到贷款；修改不影响既有贷款
ledger.defaults.grace-days=3
ledger.defaults.daily-penalty-rate=0.0005
server.port=0   # 测试随机端口；本地运行可改成固定端口，如 8080
```

发放接口也支持对单笔贷款显式传入 `graceDays`、`dailyPenaltyRate`（贷款级覆盖）。

---

## 7. 本地验证

```bash
# 编译
mvn -q compile

# 全量测试（26 个：摊销、还款/分配、逾期/罚息/快照、提前结清、幂等、并发、HTTP）
mvn test

# 启动服务（默认随机端口；把 server.port 固定为 8080 后可用上面的 curl）
mvn spring-boot:run
```

测试中通过 SLF4J 打印了**输入参数、逐期资金分配结果、罚息区间/基数/金额与判定依据**，
可在 surefire 输出或 `target/surefire-reports` 中查看。

### 测试覆盖对照

- 摊销可复现、本金守恒、零利率、尾差吸收：`AmortizationServiceTest`
- 正常/部分还款、罚息→利息→本金顺序、超额/非正金额/非法状态拒绝：`LedgerPaymentTest`
- 宽限期边界、跨期逾期、日罚息、重复结算不重复计罚、部分还款降基数、
  已结清不再逾期、规则快照稳定性：`LedgerOverdueTest`
- 提前结清报价/免除/金额不符拒绝/结清后拒绝：`LedgerPayoffTest`
- 同键重放、同键冲突、paymentId 冲突、并发同笔唯一入账、
  并发多笔 == 串行、并发双笔全额只入一笔：`LedgerConcurrencyTest`
- “直接逾期日还款”与“先结算再还款”入口一致：`LedgerEntryConsistencyTest`
- HTTP 状态码与错误码归类、幂等标志：`LoanControllerIntegrationTest`

> 说明：本实现为**单机内存账本**，重启数据不保留；其目标是给出确定性、可解释、
> 可串行化并发的领域模型与可验证结论，而非持久化生产系统。
