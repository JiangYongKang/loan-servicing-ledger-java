# 本地贷款账本与还款处理（loan-servicing-ledger-java）

在本地账本中模拟贷款账户、摊销计划与还款流水，使正常还款、提前结清与逾期处理
在服务层与 REST 层各入口下得到一致且可解释的结论。

## 摊销与计息模型

- **等额本息**：月利率 `r = 年利率 / 12`，月供
  `A = P·r·(1+r)^n / ((1+r)^n − 1)`（`r = 0` 时 `A = P/n`）。
- 每期利息 = 当期剩余本金 × r；本金 = A − 利息；**最后一期本金取剩余本金**，
  吸收累计舍入差，保证各期本金合计恒等于放款本金。
- 所有金额 `BigDecimal`，scale=2（分），`HALF_UP` 舍入；同样的放款参数永远生成
  同样的计划（**可复现**），规则调整不影响既有流水的解释。
- 首期到期日 = 放款日 + 1 个月，以此类推；每期可配置固定费用（服务费）。

## 资金分配顺序

还款入账前，先按结算日（`valueDate`）推进逾期判定与罚息累计，然后按**确定顺序**冲抵：

1. 期次升序（最早一期优先）；
2. 期次内：**费用 → 罚息 → 利息 → 本金**。

分配结果逐期记录在还款流水的 `allocations` 中，可解释、可审计。
**提前结清**的分配：先对已到期期次按上述顺序冲抵，余款全部冲抵各期剩余本金；
未到期期次的利息与费用记为**减免**（见下节），不再产生新的应还。

## 逾期与宽限规则

- 结算口径：以传入的结算日 `asOfDate` / `valueDate` 为准，不依赖系统时钟。
- 到期日 + 宽限期（`ledger.grace-period-days`，默认 3 天）内未足额：**不判逾期、不计罚息**。
- 超过宽限期仍未足额：期次置为 `OVERDUE`，按日累计罚息：
  `罚息 = (未还本金 + 未还利息) × 日利率(ledger.penalty-daily-rate，默认 0.0005) × 天数`，
  累计区间 `(上次计提日, 结算日]`，每次计提即舍入到分。
- **同一期次不重复计罚**：每期记录 `penaltyAccruedThrough`，重复或回退日期的推进是幂等无操作。
- **已结清（SETTLED）贷款**：逾期推进为无操作，不再进入逾期、不产生新的应还，
  新的还款/结清请求以 `LOAN_NOT_ACTIVE` 拒绝。

### 单笔贷款的规则覆盖与固化

- 放款时可对该笔贷款单独指定 `gracePeriodDays` / `penaltyDailyRate`（REST 请求体或
  服务层 `disburse(...)` 的可选参数），不填则取**放款当时**的全局默认。
- 无论取值来源，规则在放款时**固化**到贷款的 `overdueRules` 上：之后该贷款所有的
  逾期判定与罚息计提都只读这份固化规则，**不再读全局默认**。
- 因此放款后把全局默认改成另一套值，再拿同一个结算日重新结算这笔贷款，
  结果与改之前**一分不差**；同一结算日重复结算、补跑更早或更晚日期也不会重复计提
  （由 `penaltyAccruedThrough` 保证只增量计提）。
- **老贷款行为**：放款时未单独指定规则的贷款，按放款当时的全局默认快照执行，
  语义与升级前完全一致。

## 提前结清与减免记录

- 应结清额 = 未偿本金 + 截至结算日**已到期**未付的（费用+罚息+利息）。
- 未到期期次尚未收取的利息与费用按**减免**处理：逐期逐项记在该期的
  `interestWaived` / `feeWaived` 上，可逐期查到底减了哪一期的多少利息、多少费用。
- 记录减免后，该笔贷款**所有期次的未偿合计为 0**，账户置为 `SETTLED`，
  之后不会再产生新的应还，也不会再被判为逾期。
- 未偿口径：**未偿 = 应还 − 已还 − 减免**（罚息无减免，未偿 = 已计提 − 已还）。

## 对账入口

`GET /loans/{loanId}/reconciliation?asOf=YYYY-MM-DD`（服务层 `reconcile(loanId, asOfDate)`）
按结算日导出对账结果：先按 `asOf` 幂等推进逾期/罚息，再核对两条平衡式——

1. **科目平衡**：逐科目（PRINCIPAL / INTEREST / FEE / PENALTY）核对
   `历期应收合计 = 已还 + 未偿 + 减免`。
   其中本金科目的"未偿"取**账户级本金余额**，与计划明细（应收−已还）交叉核对。
2. **现金平衡**：`实际入账现金合计（各笔还款金额之和）= 已冲抵合计（各期已冲抵之和）`。

结果中 `balanced=true` 表示两条平衡式都平；任何一处不平，`balanced=false` 且
`discrepancies` 逐条指明**哪个科目、差了多少、怎么算的**（如
`CASH 差 77.00：入账现金(577.00) != 已冲抵合计(500.00)`）。对账本身不重复计罚，
同一结算日反复导出结论一致。

## 重复提交语义（幂等）

- 每笔还款/结清必须携带**幂等键**（`idempotencyKey`），同一贷款内唯一。
- 同键同额重复提交：返回**首次入账的流水**，账本只入账一次。
- 同键不同额：属于无法安全合并的冲突，以 `CONCURRENT_CONFLICT` 拒绝，账本不变。

## 并发一致性保证

- 每个贷款一把锁，所有写操作（还款、结清、逾期推进）在锁内**串行执行**，
  因此并发提交的终态与逐笔串行执行的终态一致（有测试对照验证）。
- **守恒校验**：每次变更后断言——
  ① 未偿本金 == 各期未还本金合计且非负；
  ② 所有还款入账总额 == 各期已冲抵（费用+罚息+利息+本金）合计；
  ③ 任何已冲抵额不超过应还额（无负数余额）。
  超额支付（`OVERPAYMENT`）、非正金额（`INVALID_AMOUNT`）、非法状态操作
  （`LOAN_NOT_ACTIVE`）在入账前被拒绝，账本不被污染。

## 错误原因码

| 代码 | 含义 | HTTP |
|---|---|---|
| `LOAN_NOT_FOUND` | 贷款不存在 | 404 |
| `INVALID_AMOUNT` | 金额为空/非正、结算日或幂等键缺失 | 422 |
| `INVALID_DISBURSEMENT` | 放款参数非法或贷款已存在 | 422 |
| `OVERPAYMENT` | 还款超过全部未偿余额 | 422 |
| `LOAN_NOT_ACTIVE` | 贷款已结清，不允许该操作 | 422 |
| `CONCURRENT_CONFLICT` | 同幂等键不同金额，无法安全合并 | 422 |

## 可配置项（application.properties）

```properties
ledger.grace-period-days=3        # 宽限期天数
ledger.penalty-daily-rate=0.0005  # 罚息日利率
ledger.default-monthly-fee=0      # 每期固定费用默认值
```

## REST 入口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/loans` | 放款并生成摊销计划；可选 `gracePeriodDays` / `penaltyDailyRate` 为该笔单独指定逾期口径 |
| GET | `/loans/{loanId}` | 查询账户、计划与流水（含固化规则与逐期减免明细） |
| POST | `/loans/{loanId}/payments` | 还款 `{idempotencyKey, amount, valueDate}` |
| POST | `/loans/{loanId}/payoff` | 提前结清 `{idempotencyKey, valueDate}` |
| POST | `/loans/{loanId}/accrue` | 按结算日推进逾期/罚息 `{asOfDate}` |
| GET | `/loans/{loanId}/reconciliation?asOf=YYYY-MM-DD` | 按结算日导出对账结果（平衡式、差异明细） |

## 本地验证

```bash
mvn test          # 全部单元测试（含并发、逾期、结清、减免、对账、非法操作）
mvn spring-boot:run   # 本地启动，默认 8080 端口
```

测试日志会打印输入、资金分配结果与判定依据（`[输入]` / `[资金分配]` / `[判定依据]`）。

重点用例与验证点：

- `PerLoanRulesTest`：单笔规则覆盖与固化——改全局默认后按同一结算日结算结果不变；
  同一结算日重复/乱序结算不重复计罚。
- `PayoffWaiverTest`：提前结清后逐期减免明细可查、各期未偿合计归零、不再产生新应还。
- `ReconciliationTest`：对账平（正常还款/结清后）与不平（注入损坏数据后指明科目与差额）
  两种结果可区分；对账本身幂等不重复计罚。
- `LoanApiTest`：REST 层放款（含单笔规则）、还款幂等、错误码、对账入口。

手工验证对账入口示例：

```bash
curl -X POST localhost:8080/loans -H 'Content-Type: application/json' -d \
  '{"loanId":"DEMO","principal":12000,"annualRate":0.12,"termMonths":12,
    "disbursementDate":"2026-01-01","monthlyFee":10,"gracePeriodDays":5,"penaltyDailyRate":0.0008}'
curl -X POST localhost:8080/loans/DEMO/payments -H 'Content-Type: application/json' -d \
  '{"idempotencyKey":"K1","amount":500,"valueDate":"2026-01-15"}'
curl 'localhost:8080/loans/DEMO/reconciliation?asOf=2026-03-10'
```
