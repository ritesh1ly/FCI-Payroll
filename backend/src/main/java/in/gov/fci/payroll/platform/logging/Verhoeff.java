package in.gov.fci.payroll.platform.logging;

/** Verhoeff check-digit validation, used by UIDAI for Aadhaar numbers. */
final class Verhoeff {

    private static final int[][] MULTIPLICATION = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
        {1, 2, 3, 4, 0, 6, 7, 8, 9, 5},
        {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
        {3, 4, 0, 1, 2, 8, 9, 5, 6, 7},
        {4, 0, 1, 2, 3, 9, 5, 6, 7, 8},
        {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
        {6, 5, 9, 8, 7, 1, 0, 4, 3, 2},
        {7, 6, 5, 9, 8, 2, 1, 0, 4, 3},
        {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
        {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}
    };

    private static final int[][] PERMUTATION = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
        {1, 5, 7, 6, 2, 8, 3, 0, 9, 4},
        {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
        {8, 9, 1, 6, 0, 4, 3, 5, 2, 7},
        {9, 4, 5, 3, 1, 2, 6, 8, 7, 0},
        {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
        {2, 7, 9, 3, 8, 0, 6, 4, 1, 5},
        {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}
    };

    private Verhoeff() {}

    /** Whether the digit string (check digit last) passes the Verhoeff checksum. */
    static boolean isValid(String digits) {
        if (digits == null || digits.isEmpty()) {
            return false;
        }
        int check = 0;
        int length = digits.length();
        for (int i = 0; i < length; i++) {
            char c = digits.charAt(length - 1 - i);
            if (c < '0' || c > '9') {
                return false;
            }
            check = MULTIPLICATION[check][PERMUTATION[i % 8][c - '0']];
        }
        return check == 0;
    }

    /** Computes the check digit to append to {@code digits}. Used to build test fixtures. */
    static int checkDigit(String digits) {
        int check = 0;
        int length = digits.length();
        for (int i = 0; i < length; i++) {
            int d = digits.charAt(length - 1 - i) - '0';
            check = MULTIPLICATION[check][PERMUTATION[(i + 1) % 8][d]];
        }
        return INVERSE[check];
    }

    private static final int[] INVERSE = {0, 4, 3, 2, 1, 5, 6, 7, 8, 9};
}
