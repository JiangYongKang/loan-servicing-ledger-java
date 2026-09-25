package com.github.highcumontoa.loanservicingledgerjava.error;

/**
 * 账本业务异常：携带稳定错误码，便于调用方区分失败原因。
 */
public class LedgerException extends RuntimeException {

    private final ErrorCode code;

    public LedgerException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
