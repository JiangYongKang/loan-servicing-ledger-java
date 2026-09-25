package com.github.highcumontoa.loanservicingledgerjava.domain;

import com.github.highcumontoa.loanservicingledgerjava.error.ErrorCode;
import com.github.highcumontoa.loanservicingledgerjava.error.LedgerException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 金额值对象。账本内所有金额一律使用 BigDecimal，统一 2 位小数、HALF_UP，
 * 杜绝 double 浮点误差；金额本身允许表示负数（供中间运算），
 * 但账户余额字段由服务层强制非负，任何负数余额会抛出 INTERNAL_CONSISTENCY。
 */
public final class Money {

    public static final Money ZERO = new Money(BigDecimal.ZERO);
    private static final int SCALE = 2;

    private final BigDecimal value;

    private Money(BigDecimal value) {
        this.value = value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static Money of(String s) {
        if (s == null || s.isBlank()) {
            throw new LedgerException(ErrorCode.INVALID_AMOUNT, "金额不能为空");
        }
        try {
            return new Money(new BigDecimal(s.trim()));
        } catch (NumberFormatException e) {
            throw new LedgerException(ErrorCode.INVALID_AMOUNT, "非法金额格式: " + s);
        }
    }

    public static Money of(BigDecimal v) {
        if (v == null) {
            throw new LedgerException(ErrorCode.INVALID_AMOUNT, "金额不能为空");
        }
        return new Money(v);
    }

    public static Money yuan(long yuan) {
        return new Money(BigDecimal.valueOf(yuan));
    }

    public Money plus(Money o) {
        return new Money(value.add(o.value));
    }

    public Money minus(Money o) {
        return new Money(value.subtract(o.value));
    }

    /**
     * 乘以一个高精度系数（如日利率），结果按 2 位 HALF_UP 归一。
     * 入参系数不做缩放，由调用方保证精度（日罚息率保留 6 位以上）。
     */
    public Money multiplyScale4(BigDecimal factor) {
        return new Money(value.multiply(factor).setScale(SCALE, RoundingMode.HALF_UP));
    }

    public boolean isPositive() {
        return value.signum() > 0;
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    public boolean gt(Money o) {
        return value.compareTo(o.value) > 0;
    }

    public boolean gte(Money o) {
        return value.compareTo(o.value) >= 0;
    }

    public boolean lt(Money o) {
        return value.compareTo(o.value) < 0;
    }

    public boolean lte(Money o) {
        return value.compareTo(o.value) <= 0;
    }

    public BigDecimal value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money money)) {
            return false;
        }
        return value.compareTo(money.value) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
