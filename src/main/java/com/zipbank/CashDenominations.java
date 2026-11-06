package com.zipbank;

import java.util.LinkedHashMap;
import java.util.Map;

final class CashDenominations {
    static final long[] VALUES = {10_000, 2_000, 1_000, 500, 100, 50, 25, 10, 1};

    private CashDenominations() { }

    static Map<Long, Long> change(long amount) {
        if (amount < 0 || amount > Money.MAX_TRANSACTION) {
            throw new IllegalArgumentException("Invalid cash amount.");
        }
        Map<Long, Long> result = new LinkedHashMap<>();
        for (long denomination : VALUES) {
            long count = amount / denomination;
            if (count != 0) {
                result.put(denomination, count);
                amount %= denomination;
            }
        }
        return result;
    }
}