package dataguard.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

/** Numeric parsing is shared so type, range, and cross-field rules use the same semantics. */
final class NumericValues {
    static final BigInteger MIN_QUANTITY = BigInteger.ONE;
    static final BigInteger MAX_QUANTITY = BigInteger.valueOf(100);
    static final BigDecimal MIN_PRICE = new BigDecimal("0.01");
    static final BigDecimal MAX_PRICE = new BigDecimal("10000");
    private static final Pattern INTEGER = Pattern.compile("[+-]?[0-9]+");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?[0-9]+(?:\\.[0-9]+)?");

    private NumericValues() { }

    static BigInteger integer(String value) {
        // An explicit bound also prevents pathological multi-megabyte numeric parsing.
        return value.length() <= 100 && INTEGER.matcher(value).matches()
                ? new BigInteger(value) : null;
    }

    static BigDecimal decimal(String value) {
        return value.length() <= 100 && DECIMAL.matcher(value).matches()
                ? new BigDecimal(value) : null;
    }

    static boolean validQuantity(BigInteger quantity) {
        return quantity != null && quantity.compareTo(MIN_QUANTITY) >= 0
                && quantity.compareTo(MAX_QUANTITY) <= 0;
    }

    static boolean validPrice(BigDecimal price) {
        return price != null && price.compareTo(MIN_PRICE) >= 0
                && price.compareTo(MAX_PRICE) <= 0;
    }
}
