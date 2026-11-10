package in.gov.fci.payroll.platform.persistence;

/**
 * Safe {@code LIKE} values: wildcards in user text are escaped, so a search term never becomes a
 * pattern (portable to H2 and Oracle with {@code ESCAPE '\'}).
 */
public final class Likes {

    public static final char ESCAPE = '\\';

    private Likes() {}

    public static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 4);
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '%' || c == '_') {
                out.append(ESCAPE);
            }
            out.append(c);
        }
        return out.toString();
    }
}
