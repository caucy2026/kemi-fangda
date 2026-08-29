package com.kemi.mypad;

import java.math.BigInteger;
import java.util.Locale;

/** Pure calculator state used by the touch UI and host-side regression probe. */
final class ProgrammerCalculator {
    private int radix = 10;
    private int wordBits = 64;
    private long value;
    private long accumulator;
    private String pendingOperator = "";
    private boolean replaceInput = true;
    private boolean error;

    int radix() { return radix; }
    int wordBits() { return wordBits; }
    boolean hasError() { return error; }

    void setRadix(int newRadix) {
        if (newRadix == 2 || newRadix == 8 || newRadix == 10 || newRadix == 16) radix = newRadix;
    }

    void cycleWordSize() {
        wordBits = wordBits == 64 ? 32 : wordBits == 32 ? 16 : wordBits == 16 ? 8 : 64;
        value = mask(value);
        accumulator = mask(accumulator);
    }

    void press(String key) {
        if ("HEX_C".equals(key)) key = "C";
        else if ("C".equals(key)) { clearAll(); return; }
        if ("CE".equals(key)) { value = 0; error = false; replaceInput = true; return; }
        if ("⌫".equals(key)) { backspace(); return; }
        if ("NOT".equals(key)) { value = mask(~value); replaceInput = true; error = false; return; }
        if (isDigit(key)) { appendDigit(key); return; }

        if ("=".equals(key)) {
            if (!pendingOperator.isEmpty() && !replaceInput) applyPending();
            pendingOperator = "";
            replaceInput = true;
            return;
        }
        if (isOperator(key)) {
            if (!pendingOperator.isEmpty() && !replaceInput) applyPending();
            else if (pendingOperator.isEmpty()) accumulator = value;
            pendingOperator = key;
            replaceInput = true;
        }
    }

    private void clearAll() {
        value = 0; accumulator = 0; pendingOperator = ""; replaceInput = true; error = false;
    }

    private boolean isDigit(String key) {
        return key.length() == 1 && "0123456789ABCDEF".contains(key);
    }

    private boolean isOperator(String key) {
        return "+−×÷ModANDORXOR<<>>".contains(key);
    }

    private void appendDigit(String key) {
        int digit = Character.digit(key.charAt(0), 16);
        if (digit < 0 || digit >= radix) return;
        if (replaceInput || error) { value = 0; replaceInput = false; error = false; }
        BigInteger next = unsignedBigInteger(value).multiply(BigInteger.valueOf(radix)).add(BigInteger.valueOf(digit));
        value = mask(next.longValue());
    }

    private void backspace() {
        if (replaceInput || error) { value = 0; error = false; return; }
        value = mask(Long.divideUnsigned(value, radix));
    }

    private void applyPending() {
        long right = value;
        try {
            switch (pendingOperator) {
                case "+": accumulator += right; break;
                case "−": accumulator -= right; break;
                case "×": accumulator *= right; break;
                case "÷":
                    if (right == 0) throw new ArithmeticException();
                    accumulator = Long.divideUnsigned(accumulator, right); break;
                case "Mod":
                    if (right == 0) throw new ArithmeticException();
                    accumulator = Long.remainderUnsigned(accumulator, right); break;
                case "AND": accumulator &= right; break;
                case "OR": accumulator |= right; break;
                case "XOR": accumulator ^= right; break;
                case "<<": accumulator <<= (right & (wordBits - 1)); break;
                case ">>": accumulator >>>= (right & (wordBits - 1)); break;
                default: return;
            }
            value = mask(accumulator);
            error = false;
        } catch (ArithmeticException ignored) {
            value = 0; error = true;
        }
    }

    String display() { return error ? "错误" : formatted(radix); }
    String hex() { return formatted(16); }
    String oct() { return formatted(8); }
    String bin() { return formatted(2); }
    String dec() { return error ? "错误" : Long.toString(signedValue()); }

    private String formatted(int outputRadix) {
        if (error) return "错误";
        String raw = unsignedBigInteger(mask(value)).toString(outputRadix).toUpperCase(Locale.ROOT);
        if (outputRadix == 10) return Long.toString(signedValue());
        int group = outputRadix == 2 ? 4 : outputRadix == 16 ? 4 : 3;
        StringBuilder grouped = new StringBuilder(raw.length() + raw.length() / group);
        for (int i = 0; i < raw.length(); i++) {
            if (i > 0 && (raw.length() - i) % group == 0) grouped.append(' ');
            grouped.append(raw.charAt(i));
        }
        return grouped.toString();
    }

    private long signedValue() {
        long masked = mask(value);
        if (wordBits == 64) return masked;
        long sign = 1L << (wordBits - 1);
        return (masked & sign) == 0 ? masked : masked - (1L << wordBits);
    }

    private long mask(long input) {
        return wordBits == 64 ? input : input & ((1L << wordBits) - 1L);
    }

    private BigInteger unsignedBigInteger(long input) {
        if (input >= 0) return BigInteger.valueOf(input);
        return BigInteger.valueOf(input & Long.MAX_VALUE).setBit(63);
    }
}
