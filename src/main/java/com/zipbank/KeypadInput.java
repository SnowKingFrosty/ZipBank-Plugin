package com.zipbank;

final class KeypadInput {
    private final boolean pin;
    private String value = "";

    KeypadInput(boolean pin) {
        this.pin = pin;
    }

    String value() {
        return value;
    }

    void digit(int digit) {
        if (digit < 0 || digit > 9) {
            throw new IllegalArgumentException("Invalid digit");
        }
        if (pin) {
            if (value.length() < 4) {
                value += digit;
            }
        } else {
            int decimal = value.indexOf('.');
            if ((decimal < 0 && value.length() < 7) || (decimal >= 0 && value.length() - decimal <= 2)) {
                value += digit;
            }
        }
    }

    void decimal() {
        if (!pin && !value.contains(".")) {
            value = value.isEmpty() ? "0." : value + ".";
        }
    }

    void backspace() {
        if (!value.isEmpty()) {
            value = value.substring(0, value.length() - 1);
        }
    }

    void clear() {
        value = "";
    }
}