package com.github.highcumontoa.loanservicingledgerjava.web;

import com.github.highcumontoa.loanservicingledgerjava.domain.Money;
import com.github.highcumontoa.loanservicingledgerjava.domain.Payment;
import com.github.highcumontoa.loanservicingledgerjava.domain.PaymentType;
import com.github.highcumontoa.loanservicingledgerjava.error.ErrorCode;
import com.github.highcumontoa.loanservicingledgerjava.error.LedgerException;
import com.github.highcumontoa.loanservicingledgerjava.service.LedgerService;
import com.github.highcumontoa.loanservicingledgerjava.service.PaymentResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 账本 REST 入口：
 * <pre>
 * POST /api/loans                            发放（生成可复现摊销计划）
 * GET  /api/loans/{id}                       贷款、计划与当前未偿
 * POST /api/loans/{id}/settle?date=yyyy-MM-dd   按结算日推进逾期/罚息并返回快照
 * GET  /api/loans/{id}/payoff?date=...       提前结清报价
 * POST /api/loans/{id}/payments              还款（按期/部分/提前结清，幂等）
 * GET  /api/loans/{id}/payments              还款流水
 * </pre>
 */
@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private final LedgerService ledgerService;

    public LoanController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @PostMapping
    public LoanResponses.LoanView disburse(@RequestBody LoanRequests.DisburseRequest req) {
        if (req == null) {
            throw new LedgerException(ErrorCode.INVALID_PARAMETER, "请求体不能为空");
        }
        return LoanResponses.loanView(ledgerService.disburse(
                req.loanId(), Money.of(req.principal()), req.annualRate(),
                requirePositive(req.termMonths(), "termMonths"),
                req.originationDate(), req.firstDueDate(),
                req.graceDays(), req.dailyPenaltyRate()));
    }

    @GetMapping("/{id}")
    public LoanResponses.LoanView get(@PathVariable String id) {
        return LoanResponses.loanView(ledgerService.getLoan(id));
    }

    @PostMapping("/{id}/settle")
    public LoanResponses.SnapshotView settle(@PathVariable String id,
                                             @RequestParam("date") String date) {
        return LoanResponses.snapshotView(ledgerService.settle(id, parseDate(date)));
    }

    @GetMapping("/{id}/payoff")
    public LoanResponses.PayoffView payoff(@PathVariable String id,
                                           @RequestParam("date") String date) {
        return LoanResponses.payoffView(ledgerService.quotePayoff(id, parseDate(date)));
    }

    @PostMapping("/{id}/payments")
    public LoanResponses.PaymentView pay(@PathVariable String id,
                                         @RequestBody LoanRequests.PaymentRequest req) {
        if (req == null) {
            throw new LedgerException(ErrorCode.INVALID_PARAMETER, "请求体不能为空");
        }
        PaymentType type = parseType(req.type());
        PaymentResult result = ledgerService.pay(id, req.paymentId(), req.idempotencyKey(),
                Money.of(req.amount()), req.valueDate(), type);
        return LoanResponses.paymentView(result);
    }

    @GetMapping("/{id}/payments")
    public List<PaymentSummary> payments(@PathVariable String id) {
        return ledgerService.payments(id).stream()
                .map(p -> new PaymentSummary(p.getPaymentId(), p.getIdempotencyKey(),
                        p.getAmount().toString(), p.getValueDate().toString(),
                        p.getType().name(), p.getStatus().name()))
                .toList();
    }

    public record PaymentSummary(String paymentId, String idempotencyKey, String amount,
                                 String valueDate, String type, String status) {
    }

    private static PaymentType parseType(String type) {
        if (type == null || type.isBlank()) {
            return PaymentType.NORMAL;
        }
        try {
            return PaymentType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new LedgerException(ErrorCode.INVALID_PARAMETER,
                    "非法还款类型: " + type + "（允许 NORMAL / EARLY_PAYOFF）");
        }
    }

    private static LocalDate parseDate(String date) {
        try {
            return LocalDate.parse(date);
        } catch (Exception e) {
            throw new LedgerException(ErrorCode.INVALID_PARAMETER, "非法日期(yyyy-MM-dd): " + date);
        }
    }

    private static int requirePositive(Integer v, String name) {
        if (v == null || v < 1) {
            throw new LedgerException(ErrorCode.INVALID_PARAMETER, name + " 必须为正整数");
        }
        return v;
    }
}
