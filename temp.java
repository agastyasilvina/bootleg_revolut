package com.example.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Converts numeric values to words in English and Indonesian.
 *
 * <p>Conventions:
 * <ul>
 *   <li>Plain decimals are read digit by digit after the separator, preserving leading
 *       zeros: {@code 3.05} &rarr; "three point zero five" / "tiga koma nol lima".
 *       This keeps 3.05 and 3.5 distinguishable and works for any number of places.</li>
 *   <li>Monetary amounts should use {@link #convertCurrency(BigDecimal, Language, String, String)},
 *       which reads the sub-unit as a whole number, e.g. for an Indonesian "terbilang" line:
 *       {@code 1500.75} &rarr; "seribu lima ratus rupiah tujuh puluh lima sen".</li>
 *   <li>Integer parts cover the full signed 64-bit range, including {@link Long#MIN_VALUE}
 *       (quintillion / kuintiliun scale). BigDecimals whose integer part exceeds that
 *       range are rejected with {@link IllegalArgumentException} rather than mangled.</li>
 *   <li>Formal Indonesian forms are used: "seribu", "seratus", "sepuluh", "sebelas",
 *       "dua belas", but "satu juta", "satu miliar", "satu triliun".</li>
 * </ul>
 *
 * <p>Stateless and thread-safe. Iterative (no recursion), one {@link StringBuilder} per
 * call, words joined with single spaces as they are appended - no regex cleanup pass.
 */
public final class NumberToWordsConverter {

    private NumberToWordsConverter() {
        // Utility class
    }

    public enum Language {
        ENGLISH,
        INDONESIAN
    }

    /** Scale magnitudes in descending order; SCALE_EN / SCALE_ID are index-aligned. */
    private static final long[] SCALES = {
            1_000_000_000_000_000_000L, // 10^18
            1_000_000_000_000_000L,     // 10^15
            1_000_000_000_000L,         // 10^12
            1_000_000_000L,             // 10^9
            1_000_000L,                 // 10^6
            1_000L                      // 10^3
    };

    private static final String[] SCALE_EN = {
            "quintillion", "quadrillion", "trillion", "billion", "million", "thousand"
    };

    private static final String[] SCALE_ID = {
            "kuintiliun", "kuadriliun", "triliun", "miliar", "juta", "ribu"
    };

    private static final String[] UNITS_EN = {
            "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen"
    };

    private static final String[] TENS_EN = {
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    };

    private static final String[] UNITS_ID = {
            "", "satu", "dua", "tiga", "empat", "lima", "enam", "tujuh", "delapan", "sembilan"
    };

    /**
     * Converts a long to words. Valid over the entire long range, including
     * {@link Long#MIN_VALUE}.
     */
    public static String convert(long number, Language language) {
        Objects.requireNonNull(language, "language");
        StringBuilder sb = new StringBuilder(64);
        appendLong(sb, number, language);
        return sb.toString();
    }

    /**
     * Converts a double to words. The value is interpreted via its shortest decimal
     * representation ({@link Double#toString(double)}), so {@code 3.05} reads as
     * "three point zero five" rather than its binary expansion. Doubles above 2^53
     * cannot represent every integer and monetary values may already have lost
     * precision before reaching this method - prefer {@link #convert(BigDecimal, Language)}
     * or {@link #convert(long, Language)} for exact values.
     *
     * @throws IllegalArgumentException if {@code number} is NaN or infinite
     */
    public static String convert(double number, Language language) {
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new IllegalArgumentException("Cannot convert NaN or infinite value: " + number);
        }
        return convert(new BigDecimal(Double.toString(number)), language);
    }

    /**
     * Converts a BigDecimal to words exactly - the value never round-trips through a
     * double. The fractional part is read digit by digit with leading zeros preserved;
     * trailing zeros are dropped ({@code 1500.00} &rarr; "one thousand five hundred",
     * {@code 3.050} &rarr; "three point zero five").
     *
     * @throws NullPointerException     if {@code number} or {@code language} is null
     * @throws IllegalArgumentException if the integer part does not fit in a long
     */
    public static String convert(BigDecimal number, Language language) {
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(language, "language");

        BigInteger intPart = number.toBigInteger(); // truncates toward zero
        long intLong = toLongExact(intPart, number);
        BigDecimal fraction = number.subtract(new BigDecimal(intPart)).abs().stripTrailingZeros();

        StringBuilder sb = new StringBuilder(64);
        if (number.signum() < 0 && intPart.signum() == 0) {
            appendWord(sb, minusWord(language)); // e.g. -0.5: the sign lives only in the fraction
        }
        appendLong(sb, intLong, language);
        if (fraction.signum() != 0) {
            appendWord(sb, language == Language.INDONESIAN ? "koma" : "point");
            String digits = fraction.movePointRight(fraction.scale()).toBigIntegerExact().toString();
            for (int i = digits.length(); i < fraction.scale(); i++) {
                appendWord(sb, zeroWord(language)); // leading zeros, e.g. the "0" in .05
            }
            for (int i = 0; i < digits.length(); i++) {
                appendWord(sb, digitWord(digits.charAt(i) - '0', language));
            }
        }
        return sb.toString();
    }

    /**
     * Converts a monetary amount to words with {@link RoundingMode#HALF_UP} rounding
     * to two decimal places. See
     * {@link #convertCurrency(BigDecimal, Language, String, String, RoundingMode)}.
     */
    public static String convertCurrency(BigDecimal amount, Language language,
                                         String mainUnit, String subUnit) {
        return convertCurrency(amount, language, mainUnit, subUnit, RoundingMode.HALF_UP);
    }

    /**
     * Converts a monetary amount to words, e.g. for an Indonesian "terbilang" line:
     * <pre>
     *   convertCurrency(new BigDecimal("1500.75"), Language.INDONESIAN, "rupiah", "sen")
     *   -&gt; "seribu lima ratus rupiah tujuh puluh lima sen"
     *
     *   convertCurrency(new BigDecimal("1500.75"), Language.ENGLISH, "dollars", "cents")
     *   -&gt; "one thousand five hundred dollars and seventy-five cents"
     * </pre>
     *
     * <p>The amount is first rounded to two decimal places using {@code roundingMode}.
     * Unlike {@link #convert(BigDecimal, Language)}, the sub-unit is read as a whole
     * number (75 &rarr; "seventy-five" / "tujuh puluh lima"). If the main-unit part is
     * zero and sub-units are present, only the sub-unit part is produced ("fifty cents").
     * Unit labels are appended verbatim - pass whatever form your document requires
     * (pluralization is the caller's concern).
     *
     * @throws IllegalArgumentException if the rounded main-unit part does not fit in a long
     */
    public static String convertCurrency(BigDecimal amount, Language language,
                                         String mainUnit, String subUnit,
                                         RoundingMode roundingMode) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(mainUnit, "mainUnit");
        Objects.requireNonNull(subUnit, "subUnit");
        Objects.requireNonNull(roundingMode, "roundingMode");

        BigDecimal scaled = amount.setScale(2, roundingMode);
        BigInteger unitsBig = scaled.toBigInteger();
        long units = toLongExact(unitsBig, amount);
        int subUnits = scaled.subtract(new BigDecimal(unitsBig)).abs()
                .movePointRight(2).intValueExact(); // 0..99

        StringBuilder sb = new StringBuilder(64);
        if (units != 0) {
            appendLong(sb, units, language);
            appendWord(sb, mainUnit);
        } else if (subUnits == 0) {
            appendLong(sb, 0, language);
            appendWord(sb, mainUnit); // "zero rupiah"
        } else if (scaled.signum() < 0) {
            appendWord(sb, minusWord(language)); // e.g. -0.50 -> "minus fifty cents"
        }

        if (subUnits > 0) {
            if (language == Language.ENGLISH && units != 0) {
                appendWord(sb, "and");
            }
            appendLong(sb, subUnits, language);
            appendWord(sb, subUnit);
        }
        return sb.toString();
    }

    // --- Core engine ---

    /**
     * Appends the words for any long. Internally works with a NEGATIVE magnitude
     * (m = -|number|) so that {@code Long.MIN_VALUE} never needs to be negated,
     * which would overflow. Java's integer division and remainder truncate toward
     * zero, so chunking behaves identically in negative space.
     */
    private static void appendLong(StringBuilder sb, long number, Language language) {
        if (number == 0) {
            appendWord(sb, zeroWord(language));
            return;
        }
        if (number < 0) {
            appendWord(sb, minusWord(language));
        }
        long m = number < 0 ? number : -number; // m <= 0
        for (int i = 0; i < SCALES.length; i++) {
            int chunk = (int) -(m / SCALES[i]); // 0..999 (0..9 at the 10^18 scale)
            if (chunk > 0) {
                if (language == Language.INDONESIAN && chunk == 1 && SCALES[i] == 1_000L) {
                    appendWord(sb, "seribu");
                } else {
                    appendBelowThousand(sb, chunk, language);
                    appendWord(sb, language == Language.INDONESIAN ? SCALE_ID[i] : SCALE_EN[i]);
                }
            }
            m %= SCALES[i]; // stays in (-SCALES[i], 0]
        }
        int rest = (int) -m; // 0..999
        if (rest > 0) {
            appendBelowThousand(sb, rest, language);
        }
    }

    /** Appends words for 1..999. */
    private static void appendBelowThousand(StringBuilder sb, int n, Language language) {
        if (language == Language.INDONESIAN) {
            appendBelowThousandId(sb, n);
        } else {
            appendBelowThousandEn(sb, n);
        }
    }

    private static void appendBelowThousandEn(StringBuilder sb, int n) {
        if (n >= 100) {
            appendWord(sb, UNITS_EN[n / 100]);
            appendWord(sb, "hundred");
            n %= 100;
        }
        if (n >= 20) {
            int unit = n % 10;
            appendWord(sb, unit == 0 ? TENS_EN[n / 10] : TENS_EN[n / 10] + "-" + UNITS_EN[unit]);
        } else if (n > 0) {
            appendWord(sb, UNITS_EN[n]);
        }
    }

    private static void appendBelowThousandId(StringBuilder sb, int n) {
        if (n >= 200) {
            appendWord(sb, UNITS_ID[n / 100]);
            appendWord(sb, "ratus");
            n %= 100;
        } else if (n >= 100) {
            appendWord(sb, "seratus");
            n %= 100;
        }
        if (n >= 20) {
            appendWord(sb, UNITS_ID[n / 10]);
            appendWord(sb, "puluh");
            n %= 10; // fall through so the units digit is not lost: 21 -> "dua puluh satu"
        }
        if (n >= 12) {
            appendWord(sb, UNITS_ID[n - 10]);
            appendWord(sb, "belas");
        } else if (n == 11) {
            appendWord(sb, "sebelas");
        } else if (n == 10) {
            appendWord(sb, "sepuluh");
        } else if (n > 0) {
            appendWord(sb, UNITS_ID[n]);
        }
    }

    // --- Helpers ---

    private static void appendWord(StringBuilder sb, String word) {
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(word);
    }

    private static String zeroWord(Language language) {
        return language == Language.INDONESIAN ? "nol" : "zero";
    }

    private static String minusWord(Language language) {
        return "minus"; // same word in both languages
    }

    private static String digitWord(int digit, Language language) {
        if (digit == 0) {
            return zeroWord(language);
        }
        return language == Language.INDONESIAN ? UNITS_ID[digit] : UNITS_EN[digit];
    }

    private static long toLongExact(BigInteger value, BigDecimal original) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Integer part out of supported range (must fit in a signed 64-bit long): " + original);
        }
    }

    // --- Quick demo ---
    public static void main(String[] args) {
        System.out.println("EN: " + convert(11521L, Language.ENGLISH));
        // eleven thousand five hundred twenty-one
        System.out.println("ID: " + convert(11521L, Language.INDONESIAN));
        // sebelas ribu lima ratus dua puluh satu

        System.out.println("EN: " + convert(3.05, Language.ENGLISH));
        // three point zero five
        System.out.println("ID: " + convert(3.05, Language.INDONESIAN));
        // tiga koma nol lima

        System.out.println("ID: " + convertCurrency(new BigDecimal("1500.75"),
                Language.INDONESIAN, "rupiah", "sen"));
        // seribu lima ratus rupiah tujuh puluh lima sen
        System.out.println("EN: " + convertCurrency(new BigDecimal("1500.75"),
                Language.ENGLISH, "dollars", "cents"));
        // one thousand five hundred dollars and seventy-five cents

        System.out.println("EN: " + convert(Long.MIN_VALUE, Language.ENGLISH));
        // minus nine quintillion two hundred twenty-three quadrillion ...
    }
}
