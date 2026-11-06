package com.zipbank;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class Money {
    static final long MAX_TRANSACTION = 999_999_999L;

    private Money() { }

    static long parse(String input) {
        if (!input.matches("[0-9]{1,7}(\\.[0-9]{1,2})?")) {
            throw new IllegalArgumentException("Enter a positive amount with at most two decimal places.");
        }
        long amount = new BigDecimal(input).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        if (amount <= 0 || amount > MAX_TRANSACTION) {
            throw new IllegalArgumentException("Amount must be between 0.01 and 9999999.99.");
        }
        return amount;
    }

    static String format(long amount) {
        return BigDecimal.valueOf(amount, 2).toPlainString();
    }
}