package com.github.highcumontoa.loanservicingledgerjava.api;

import com.github.highcumontoa.loanservicingledgerjava.error.LedgerException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 把 LedgerException 映射为带原因码的 4xx 响应。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<Map<String, String>> handle(LedgerException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case LOAN_NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status)
                .body(Map.of("code", ex.getCode().name(), "message", ex.getMessage()));
    }
}
