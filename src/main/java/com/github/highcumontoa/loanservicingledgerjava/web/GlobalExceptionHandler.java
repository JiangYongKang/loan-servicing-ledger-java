package com.github.highcumontoa.loanservicingledgerjava.web;

import com.github.highcumontoa.loanservicingledgerjava.error.ErrorCode;
import com.github.highcumontoa.loanservicingledgerjava.error.LedgerException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 异常到 HTTP 的映射：业务错误码 -&gt; 可区分的 4xx 响应体 {code,message}。
 * 错误码字符串稳定，调用方应据此而非 message 文本做分支。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<LoanResponses.ErrorBody> handle(LedgerException ex) {
        return ResponseEntity.status(statusOf(ex.getCode()))
                .body(new LoanResponses.ErrorBody(ex.getCode().name(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<LoanResponses.ErrorBody> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new LoanResponses.ErrorBody(ErrorCode.INVALID_PARAMETER.name(), "请求体无法解析"));
    }

    private HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case LOAN_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case LOAN_ALREADY_EXISTS, PAYMENT_DUPLICATED -> HttpStatus.CONFLICT;
            case PAYMENT_CONFLICT -> HttpStatus.CONFLICT;
            case LOAN_ALREADY_PAID_OFF -> HttpStatus.CONFLICT;
            case PAYMENT_AMOUNT_NON_POSITIVE, INVALID_AMOUNT, INVALID_PARAMETER,
                 PAYMENT_EXCEEDS_DUE, PAYOFF_AMOUNT_MISMATCH,
                 SETTLEMENT_BEFORE_ORIGINATION, ILLEGAL_STATE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INTERNAL_CONSISTENCY -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
