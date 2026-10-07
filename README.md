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
未到期期次的利息与费用按**减免**逐期逐项记录（见下文"提前结清与减免记录"），
不再产生新的应还。

## 逾期与宽限规则

- 结算口径：以传入的结算日 `asOfDate` / `valueDate` 为准，不依赖系统时钟。
- **单笔规则可覆盖、放款即固化**：每笔贷款在放款时持有一份自己的逾期口径
  （`LoanRules`：宽限期天数 + 罚息日利率）。
  - 放款请求里给了 `gracePeriodDays` / `penaltyDailyRate` 就用本笔指定值（视图中
    `rules.customGracePeriod` / `customPenaltyRate` 为 `true`）；
  - 没给（null）则沿用**放款当时**的全局默认值，并把该值快照到贷款上。
  - 贷款一旦放出，后续所有逾期判定与罚息计提都按这份固化规则解释。之后再改全局默认
    （`ledger.grace-period-days` / `ledger.penalty-daily-rate`），对既有贷款没有任何影响：
    拿同一个结算日重新结算，结果与改之前一分不差（见 `PerLoanRulesFreezeTest`）。
  - **老贷款没单独指定规则时**：沿用并固化放款当时的全局默认（默认宽限 3 天、日利率
    万五），此后全局默认变化同样不影响它。
- 到期日 + 宽限期内未足额：**不判逾期、不计罚息**。
- 超过宽限期仍未足额：期次置为 `OVERDUE`，按日累计罚息：
  `罚息 = (未还本金 + 未还利息) × 该笔贷款固化日利率 × 天数`，
  累计区间 `(上次计提日, 结算日]`，每次计提即舍入到分。
- **同一期次不重复计罚**：每期记录 `penaltyAccruedThrough`，同一结算日重复结算、
  回退补跑更早日期、或乱序补跑都是幂等无操作，已出来的罚息与解释不会变样；
  之后再顺跑到更晚日期，只补新增区间，绝不从头重算。
- **已结清（SETTLED）贷款**：逾期推进为无操作，不再进入逾期、不产生新的应还，
  新的还款/结清请求以 `LOAN_NOT_ACTIVE` 拒绝。

## 提前结清与减免记录

- 结清时先按结算日幂等计提逾期/罚息；应结清额 =
  未偿本金 + 截至结算日已到期未付的（费用 + 罚息 + 利息）。
- 资金分配：先对已到期期次按 费用→罚息→利息→本金 冲抵，余款按期次冲抵剩余本金。
- **还没到期、本来就不该收的利息与费用按"减免"处理（本金不减免，全部冲抵）**。
  每一期、每一科目的减免都落一条 `WaiverEntry`：
  `{paymentId, period, valueDate, interestWaived, feeWaived, penaltyWaived}`，
  可在贷款视图的 `waivers` 中逐期、逐项查到底减了哪一期的多少利息/费用；
  期次视图上也有 `interestWaived` / `feeWaived` / `penaltyWaived`。
- 结清完成后：贷款置 `SETTLED`、`outstandingPrincipal=0`，
  **所有期次的未偿（费用/罚息/利息/本金）合计必须为 0**，守恒校验对此强断言；
  此后不会再冒出新的应还，也不会再被算成逾期。

## 对账（按结算日导出）

入口：`GET /loans/{loanId}/reconciliation?asOfDate=YYYY-MM-DD`
（可选 `cashReceived`：外部现金系统口径的实际入账合计）。
导出前会在锁内把逾期/罚息幂等推进到 `asOfDate`，同结算日反复导出结论稳定。

两个平衡面各用**独立来源**的数字交叉核对（不是从同一组字段派生，否则恒等是同义反复）：

1. **应收侧（逐科目 PRINCIPAL / INTEREST / FEE / PENALTY）**：
   `历期应收合计 == 已冲抵 + 未偿 + 减免`
   - 应收：本金/利息/费用取摊销计划上的固定数；罚息取截至结算日已计提入账的数
     （账本只对已逾期区间计提，没有"未入账的应收罚息"）。
   - 已冲抵：取自**资金分配流水** `PaymentAllocation`（资金账）。
   - 未偿、减免：取自期次上的余额/减免字段（期次账）。
2. **现金侧**：`实际入账现金合计(Payment.amount) == 已冲抵合计(PaymentAllocation 汇总)`，
   均只统计 `valueDate <= asOfDate` 的流水。不传 `cashReceived` 时用账本自身入账额；
   传入外部现金口径则与之比对。

报告 `ReconciliationReport` 给出：各科目的 `due/paid/outstanding/waived/difference`、
`cashReceived/cashApplied/cashDifference`、两面的平衡标志与总标志 `balanced`。
**对不上时 `discrepancies` 逐条指出 `side`（RECEIVABLE/CASH）、`component`（科目）、
左右两边金额与差额 `difference`**，而不是只丢一句"校验没过"
（见 `ReconciliationTest` 的现金不平与应收不平两个用例）。

> 口径提示：罚息是"推进到哪、计提到哪"。正常按时间顺序使用时，对某结算日的对账会先
> 把罚息幂等补提到该日；若先跑过更晚日期再回看更早日期，报告反映的是"当时账上已计提"
> 的状态（不会回滚已入账金额）。需要某个日期的口径时，直接对该日期导出即可。

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
  ③ 任何已冲抵额不超过应还额（无负数余额）；
  ④ 逐期逐科目 `应收 == 已冲抵 + 未偿 + 减免`；
  ⑤ 已结清贷款各期未偿合计为 0、本金余额为 0。
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
| POST | `/loans` | 放款并生成摊销计划（可带单笔 `gracePeriodDays`/`penaltyDailyRate`） |
| GET | `/loans/{loanId}` | 查询账户、固化规则、计划、流水、减免明细 |
| POST | `/loans/{loanId}/payments` | 还款 `{idempotencyKey, amount, valueDate}` |
| POST | `/loans/{loanId}/payoff` | 提前结清 `{idempotencyKey, valueDate}` |
| POST | `/loans/{loanId}/accrue` | 按结算日推进逾期/罚息 `{asOfDate}` |
| GET | `/loans/{loanId}/reconciliation?asOfDate=...` | 按结算日导出对账结果（可选 `cashReceived`） |

放款请求示例（只覆盖宽限期、罚息率沿用默认）：

```json
{"loanId":"L1","principal":12000,"annualRate":0.12,"termMonths":12,
 "disbursementDate":"2026-01-01","monthlyFee":10,
 "gracePeriodDays":5}
```

## 本地验证

```bash
mvn test               # 全部单元测试（含单笔规则固化、结清减免、对账、并发、非法操作）
mvn test -Dtest=PerLoanRulesFreezeTest,PayoffWaiverTest,ReconciliationTest  # 只跑本轮新增
mvn spring-boot:run    # 本地启动，默认 8080 端口
```

关键新增用例：

- `PerLoanRulesFreezeTest`：改完全局默认后同一结算日复算不变；单笔覆盖规则不受全局影响；
  同一结算日重复/乱序结算不重复计罚。
- `PayoffWaiverTest`：提前结清后各期未偿合计归零，减免逐期逐项可查。
- `ReconciliationTest`：应收侧/现金侧平衡与不平都能区分，并定位到科目与差额；
  结清后对账未偿为 0、罚息恒 0。

测试日志会打印每条用例的输入、资金分配结果与判断依据
（`[输入]` / `[资金分配]` / `[减免明细]` / `[应收侧]` / `[现金侧]` / `[判定依据]`）。
连跑两遍结果一致。
