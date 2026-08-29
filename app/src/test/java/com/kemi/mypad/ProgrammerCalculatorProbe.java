package com.kemi.mypad;

/** Plain Java regression probe for programmer-mode arithmetic and conversions. */
public final class ProgrammerCalculatorProbe {
    public static void main(String[] args) {
        ProgrammerCalculator calc = new ProgrammerCalculator();
        calc.press("1"); calc.press("0"); calc.press("+"); calc.press("5"); calc.press("=");
        require("15".equals(calc.dec()), "10 + 5");
        require("F".equals(calc.hex()), "HEX conversion");
        require("17".equals(calc.oct()), "OCT conversion");
        require("1111".equals(calc.bin()), "BIN conversion");

        calc.setRadix(16); calc.press("C"); calc.press("F"); calc.press("F"); calc.press("HEX_C");
        require("FFC".equals(calc.hex()), "hex C digit");
        calc.press("C"); calc.press("F"); calc.press("F");
        calc.press("AND"); calc.press("F"); calc.press("=");
        require("F".equals(calc.hex()), "FF AND F");

        calc.press("C"); calc.press("1"); calc.press("<<"); calc.press("4"); calc.press("=");
        require("10".equals(calc.hex()), "1 << 4");
        calc.press("NOT");
        require("FFFF FFFF FFFF FFEF".equals(calc.hex()), "64-bit NOT");
        System.out.println("ProgrammerCalculatorProbe PASS");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
