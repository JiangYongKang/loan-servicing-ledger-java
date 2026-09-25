package com.github.highcumontoa.loanservicingledgerjava.error;

/** 账本业务异常，携带可区分的原因码。 */
public class LedgerException extends RuntimeException {

    private final LedgerErrorCode code;

    public LedgerException(LedgerErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public LedgerErrorCode getCode() {
        return code;
    }
}
