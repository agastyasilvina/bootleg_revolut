package com.example.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Plain-Java test harness (no JUnit dependency). Run with:
 *   javac NumberToWordsConverter.java NumberToWordsConverterTest.java
 *   java com.example.util.NumberToWordsConverterTest
 * Exits non-zero on any failure.
 */
public final class NumberToWordsConverterTest {

    private static final NumberToWordsConverter.Language EN = NumberToWordsConverter.Language.ENGLISH;
    private static final NumberToWordsConverter.Language ID = NumberToWordsConverter.Language.INDONESIAN;

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // --- English integers ---
        check("zero", words(0L, EN));
        check("seven", words(7L, EN));
        check("thirteen", words(13L, EN));
        check("twenty-one", words(21L, EN));
        check("forty", words(40L, EN));
        check("one hundred", words(100L, EN));
        check("one hundred five", words(105L, EN));
        check("nine hundred ninety-nine", words(999L, EN));
        check("one thousand", words(1000L, EN));
        check("five thousand", words(5000L, EN));
        check("eleven thousand five hundred twenty-one", words(11521L, EN));
        check("one million", words(1_000_000L, EN));
        check("two billion one hundred forty-seven million four hundred eighty-three thousand "
                + "six hundred forty-seven", words(2_147_483_647L, EN));
        check("one quadrillion", words(1_000_000_000_000_000L, EN));
        check("minus forty-two", words(-42L, EN));
        check("nine quintillion two hundred twenty-three quadrillion three hundred seventy-two "
                + "trillion thirty-six billion eight hundred fifty-four million seven hundred "
                + "seventy-five thousand eight hundred seven", words(Long.MAX_VALUE, EN));
        check("minus nine quintillion two hundred twenty-three quadrillion three hundred "
                + "seventy-two trillion thirty-six billion eight hundred fifty-four million "
                + "seven hundred seventy-five thousand eight hundred eight",
                words(Long.MIN_VALUE, EN));

        // --- Indonesian integers ---
        check("nol", words(0L, ID));
        check("satu", words(1L, ID));
        check("sepuluh", words(10L, ID));
        check("sebelas", words(11L, ID));
        check("dua belas", words(12L, ID));
        check("sembilan belas", words(19L, ID));
        check("dua puluh", words(20L, ID));
        check("dua puluh satu", words(21L, ID)); // units digit must not be dropped
        check("sembilan puluh sembilan", words(99L, ID));
        check("seratus", words(100L, ID));
        check("seratus satu", words(101L, ID));
        check("seratus sepuluh", words(110L, ID));
        check("seratus sebelas", words(111L, ID));
        check("dua ratus", words(200L, ID));
        check("sembilan ratus sembilan puluh sembilan", words(999L, ID));
        check("seribu", words(1000L, ID));
        check("seribu lima ratus", words(1500L, ID));
        check("dua ribu", words(2000L, ID));
        check("lima ribu", words(5000L, ID));
        check("sepuluh ribu", words(10_000L, ID));
        check("sebelas ribu lima ratus dua puluh satu", words(11521L, ID));
        check("seratus ribu", words(100_000L, ID));
        check("satu juta", words(1_000_000L, ID));
        check("satu juta seribu", words(1_001_000L, ID));
        check("satu miliar", words(1_000_000_000L, ID));
        check("satu triliun", words(1_000_000_000_000L, ID));
        check("satu kuadriliun", words(1_000_000_000_000_000L, ID));
        check("minus sembilan kuintiliun dua ratus dua puluh tiga kuadriliun tiga ratus tujuh "
                + "puluh dua triliun tiga puluh enam miliar delapan ratus lima puluh empat juta "
                + "tujuh ratus tujuh puluh lima ribu delapan ratus delapan",
                words(Long.MIN_VALUE, ID));

        // --- Doubles (digit-by-digit fractions) ---
        check("one thousand five hundred point seven five", words(1500.75, EN));
        check("seribu lima ratus koma tujuh lima", words(1500.75, ID));
        check("three point zero five", words(3.05, EN));
        check("tiga koma nol lima", words(3.05, ID));
        check("one point nine nine nine", words(1.999, EN));
        check("minus zero point five", words(-0.5, EN));
        check("minus nol koma lima", words(-0.5, ID));
        check("two", words(2.0, EN));
        checkThrows(() -> words(Double.NaN, EN));
        checkThrows(() -> words(Double.POSITIVE_INFINITY, EN));

        // --- BigDecimal (exact, no double round-trip) ---
        check("one hundred twenty-three quadrillion four hundred fifty-six trillion seven "
                + "hundred eighty-nine billion twelve million three hundred forty-five thousand "
                + "six hundred seventy-eight point nine",
                words(new BigDecimal("123456789012345678.90"), EN));
        check("zero point one two five", words(new BigDecimal("0.125"), EN));
        check("nol koma nol lima", words(new BigDecimal("0.05"), ID));
        check("one thousand five hundred", words(new BigDecimal("1500.00"), EN));
        check("minus seribu lima ratus koma tujuh lima",
                words(new BigDecimal("-1500.75"), ID));
        checkThrows(() -> words(new BigDecimal("1E20"), EN)); // beyond long range
        checkThrows(() -> words((BigDecimal) null, EN));

        // --- Currency / terbilang ---
        check("seribu lima ratus rupiah tujuh puluh lima sen",
                money(new BigDecimal("1500.75"), ID, "rupiah", "sen"));
        check("one thousand five hundred dollars and seventy-five cents",
                money(new BigDecimal("1500.75"), EN, "dollars", "cents"));
        check("dua juta lima ratus ribu rupiah",
                money(new BigDecimal("2500000"), ID, "rupiah", "sen"));
        check("fifty cents",
                money(new BigDecimal("0.50"), EN, "dollars", "cents"));
        check("minus fifty cents",
                money(new BigDecimal("-0.50"), EN, "dollars", "cents"));
        check("zero dollars",
                money(BigDecimal.ZERO, EN, "dollars", "cents"));
        check("sepuluh rupiah satu sen", // HALF_UP: 10.005 -> 10.01
                money(new BigDecimal("10.005"), ID, "rupiah", "sen"));
        check("sepuluh rupiah", // HALF_EVEN: 10.005 -> 10.00
                NumberToWordsConverter.convertCurrency(new BigDecimal("10.005"), ID,
                        "rupiah", "sen", RoundingMode.HALF_EVEN));
        check("minus seribu lima ratus rupiah tujuh puluh lima sen",
                money(new BigDecimal("-1500.75"), ID, "rupiah", "sen"));

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // --- Shorthands ---

    private static String words(long n, NumberToWordsConverter.Language lang) {
        return NumberToWordsConverter.convert(n, lang);
    }

    private static String words(double n, NumberToWordsConverter.Language lang) {
        return NumberToWordsConverter.convert(n, lang);
    }

    private static String words(BigDecimal n, NumberToWordsConverter.Language lang) {
        return NumberToWordsConverter.convert(n, lang);
    }

    private static String money(BigDecimal amount, NumberToWordsConverter.Language lang,
                                String mainUnit, String subUnit) {
        return NumberToWordsConverter.convertCurrency(amount, lang, mainUnit, subUnit);
    }

    private static void check(String expected, String actual) {
        if (expected.equals(actual)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: expected \"" + expected + "\"\n      but got  \"" + actual + "\"");
        }
    }

    private static void checkThrows(Runnable call) {
        try {
            call.run();
            failed++;
            System.out.println("FAIL: expected an exception but none was thrown");
        } catch (IllegalArgumentException | NullPointerException e) {
            passed++;
        }
    }

    private NumberToWordsConverterTest() {
    }
}
