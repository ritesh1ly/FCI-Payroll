package in.gov.fci.payroll.platform.export;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CSV for list exports (SRS-G-13): RFC 4180 quoting of every cell, plus a leading apostrophe on a
 * cell that starts with {@code = + - @}, tab or carriage return, so a spreadsheet never evaluates
 * exported text as a formula (CSV / formula injection, OWASP).
 */
public final class Csv {

    private Csv() {}

    /** One quoted, injection-safe cell; null is an empty cell. */
    public static String cell(String value) {
        if (value == null) {
            return "";
        }
        String safe =
                !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    /** One CSV line (CRLF-terminated, as RFC 4180) of the given values. */
    public static String line(Object... values) {
        return Arrays.stream(values)
                        .map(v -> cell(v == null ? null : String.valueOf(v)))
                        .collect(Collectors.joining(","))
                + "\r\n";
    }

    /**
     * RFC 4180 CSV: comma-separated, fields optionally in double quotes, a doubled quote inside
     * quotes is one quote, quoted fields may hold commas and line breaks; CRLF or LF line ends.
     */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"' && field.isEmpty()) {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
