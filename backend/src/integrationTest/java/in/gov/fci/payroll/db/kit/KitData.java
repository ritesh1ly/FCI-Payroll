package in.gov.fci.payroll.db.kit;

import in.gov.fci.payroll.db.kit.Catalog.Column;
import in.gov.fci.payroll.db.kit.Catalog.Constraint;
import in.gov.fci.payroll.db.kit.Catalog.Kind;
import in.gov.fci.payroll.db.kit.Catalog.Table;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rows of a Flyway-built schema, as canonical text per cell, and the classification of each
 * date / timestamp cell as fixed or computed from today (by comparison with a build whose clock was
 * moved {@link KitBuild#SHIFT_YEARS} years ahead). INSTALL_KIT.md "Data".
 */
final class KitData {

    static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS");
    private static final String NUL = "\u0000";
    private static final String SEP = "\u0001";
    static final String HISTORY = "flyway_schema_history";

    private KitData() {}

    /**
     * One row: canonical text per column (null for NULL) and which cells hold a value computed from
     * the build's today.
     */
    record Row(String key, String[] values, BitSet relative) {}

    /**
     * The rows of one table, sorted by primary key.
     *
     * @param table table name
     * @param columns column names in order
     * @param kinds literal kind per column
     * @param pk positions of the primary key columns (all columns when it has none)
     * @param rows rows by key, in key order
     * @param generated computed columns (read, never written)
     */
    record TableRows(
            String table,
            List<String> columns,
            List<Kind> kinds,
            int[] pk,
            LinkedHashMap<String, Row> rows,
            BitSet generated) {
        int index(String column) {
            int i = columns.indexOf(column);
            if (i < 0) {
                throw new IllegalStateException(table + "." + column);
            }
            return i;
        }
    }

    /** Reads every table of the catalog. */
    static Map<String, TableRows> read(Connection connection, Catalog catalog) throws SQLException {
        Map<String, TableRows> out = new TreeMap<>();
        for (Table t : catalog.tables().values()) {
            out.put(t.name(), readTable(connection, catalog, t));
        }
        return out;
    }

    private static TableRows readTable(Connection connection, Catalog catalog, Table t)
            throws SQLException {
        List<String> columns = t.columns().stream().map(Column::name).toList();
        List<Kind> kinds = t.columns().stream().map(Column::kind).toList();
        List<String> pkCols = catalog.primaryKey(t.name());
        int[] pk =
                pkCols.isEmpty()
                        ? java.util.stream.IntStream.range(0, columns.size()).toArray()
                        : pkCols.stream().mapToInt(columns::indexOf).toArray();
        List<Row> rows = new ArrayList<>();
        StringBuilder select = new StringBuilder("SELECT ");
        for (int i = 0; i < columns.size(); i++) {
            select.append(i == 0 ? "" : ", ").append(quote(columns.get(i)));
        }
        select.append(" FROM ").append(quote(t.name()));
        try (Statement s = connection.createStatement();
                ResultSet rs = s.executeQuery(select.toString())) {
            while (rs.next()) {
                String[] values = new String[columns.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = cell(rs, i + 1, kinds.get(i));
                    if (HISTORY.equals(t.name()) && "execution_time".equals(columns.get(i))) {
                        // How long Flyway took is not state: written 0, so the kit is stable.
                        values[i] = "0";
                    }
                }
                rows.add(new Row(key(values, pk), values, new BitSet()));
            }
        }
        Comparator<Row> order = keyOrder(kinds, pk);
        rows.sort(order);
        LinkedHashMap<String, Row> byKey = new LinkedHashMap<>();
        for (Row r : rows) {
            if (byKey.put(r.key(), r) != null && !pkCols.isEmpty()) {
                throw new IllegalStateException("duplicate key in " + t.name() + ": " + r.key());
            }
        }
        if (byKey.size() != rows.size()) {
            // A table without a primary key may hold equal rows: number them.
            byKey.clear();
            int n = 0;
            for (Row r : rows) {
                String k = r.key() + SEP + "#" + n++;
                byKey.put(k, new Row(k, r.values(), r.relative()));
            }
        }
        BitSet generated = new BitSet();
        for (int i = 0; i < t.columns().size(); i++) {
            generated.set(i, t.columns().get(i).generated());
        }
        return new TableRows(t.name(), columns, kinds, pk, byKey, generated);
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    static String key(String[] values, int[] pk) {
        StringBuilder b = new StringBuilder();
        for (int i : pk) {
            b.append(values[i] == null ? NUL : values[i]).append(SEP);
        }
        return b.toString();
    }

    /** Key tuple of {@code columns} of a row, or null when one of them is NULL (no reference). */
    static String tuple(TableRows t, String[] values, List<String> columns) {
        StringBuilder b = new StringBuilder();
        for (String c : columns) {
            String v = values[t.index(c)];
            if (v == null) {
                return null;
            }
            b.append(v).append(SEP);
        }
        return b.toString();
    }

    private static Comparator<Row> keyOrder(List<Kind> kinds, int[] pk) {
        return (a, b) -> {
            for (int i : pk) {
                String x = a.values()[i];
                String y = b.values()[i];
                if (x == null || y == null) {
                    int c = x == null ? (y == null ? 0 : 1) : -1;
                    if (c != 0) {
                        return c;
                    }
                    continue;
                }
                int c =
                        kinds.get(i) == Kind.NUMBER
                                ? new BigDecimal(x).compareTo(new BigDecimal(y))
                                : x.compareTo(y);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        };
    }

    private static String cell(ResultSet rs, int i, Kind kind) throws SQLException {
        return switch (kind) {
            case STRING, CLOB -> rs.getString(i);
            case NUMBER -> {
                BigDecimal d = rs.getBigDecimal(i);
                yield d == null ? null : d.toPlainString();
            }
            case DATE, TIMESTAMP -> {
                Timestamp ts = rs.getTimestamp(i);
                yield ts == null ? null : TS.format(ts.toLocalDateTime());
            }
            case BLOB, RAW -> {
                byte[] b = rs.getBytes(i);
                yield b == null ? null : HexFormat.of().withUpperCase().formatHex(b);
            }
            case BOOLEAN -> {
                boolean b = rs.getBoolean(i);
                yield rs.wasNull() ? null : (b ? "TRUE" : "FALSE");
            }
        };
    }

    static LocalDateTime time(String canonical) {
        return LocalDateTime.parse(canonical, TS);
    }

    /** Counts of the clock classification, for the manifest. */
    record ClockReport(
            Map<String, Integer> relativeCells,
            Map<String, Integer> fiscalCells,
            Map<String, Integer> irregularCells,
            Map<String, Integer> unmatchedRows) {}

    /** No clock-dependent cell. */
    static final int FIXED = 0;

    /** A value that moves with today's month (the month shift k). */
    static final int MONTHLY = 1;

    /** A value anchored on the current financial year (April to March): moves with whole years. */
    static final int FISCAL = 2;

    /** The clock class of cell {@code i} (two bits per column in {@link Row#relative()}). */
    static int clock(Row r, int i) {
        return !r.relative().get(2 * i) ? FIXED : r.relative().get(2 * i + 1) ? FISCAL : MONTHLY;
    }

    static boolean relative(Row r, int i) {
        return r.relative().get(2 * i);
    }

    private static final Pattern YYMM = Pattern.compile("^(\\d{2})(0[1-9]|1[0-2])$");
    private static final Pattern YYYYMM = Pattern.compile("^(\\d{4})(0[1-9]|1[0-2])$");
    private static final Pattern FY = Pattern.compile("^(\\d{4})-(\\d{2})$");

    /**
     * A period written as text, moved by years and months: YYMM, YYYYMM, or a financial year
     * YYYY-YY (years only); null when the text is none of them.
     */
    static String shiftText(String text, int years, int months) {
        if (text == null) {
            return null;
        }
        Matcher m;
        if ((m = YYMM.matcher(text)).matches()) {
            YearMonth ym =
                    YearMonth.of(2000 + Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)))
                            .plusYears(years)
                            .plusMonths(months);
            return String.format(
                    java.util.Locale.ROOT, "%02d%02d", ym.getYear() % 100, ym.getMonthValue());
        }
        if ((m = YYYYMM.matcher(text)).matches()) {
            YearMonth ym =
                    YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)))
                            .plusYears(years)
                            .plusMonths(months);
            return String.format(
                    java.util.Locale.ROOT, "%04d%02d", ym.getYear(), ym.getMonthValue());
        }
        if ((m = FY.matcher(text)).matches()
                && (Integer.parseInt(m.group(1)) + 1) % 100 == Integer.parseInt(m.group(2))) {
            if (months != 0) {
                return null;
            }
            int y = Integer.parseInt(m.group(1)) + years;
            return String.format(java.util.Locale.ROOT, "%04d-%02d", y, (y + 1) % 100);
        }
        return null;
    }

    /** The text period kind of a value: "YYMM", "YYYYMM", "FY" or null. */
    static String textPeriod(String text) {
        if (text == null) {
            return null;
        }
        if (YYMM.matcher(text).matches()) {
            return "YYMM";
        }
        if (YYYYMM.matcher(text).matches()) {
            return "YYYYMM";
        }
        Matcher m = FY.matcher(text);
        return m.matches()
                        && (Integer.parseInt(m.group(1)) + 1) % 100 == Integer.parseInt(m.group(2))
                ? "FY"
                : null;
    }

    /**
     * Classifies every cell of {@code built} by comparing it with two builds whose clock was moved:
     * {@code shifted} by {@link KitBuild#SHIFT_YEARS} years, {@code shiftedMonth} by the same and
     * one month more. A cell equal in all three is fixed; one that moved by the years is computed
     * from today, and it is {@link #MONTHLY} if the extra month moved it too, else {@link #FISCAL}
     * (anchored on the financial year, which one month did not change). Dates, timestamps and the
     * text periods YYMM / YYYYMM / YYYY-YY are classified; any other difference is "irregular" and
     * stays fixed (reported). Rows are matched by key, a key holding a moved date or period being
     * moved back first.
     */
    static ClockReport markRelative(
            Map<String, TableRows> built,
            Map<String, TableRows> shifted,
            Map<String, TableRows> shiftedMonth,
            Duration tolerance,
            List<String> fixedTables) {
        Map<String, Integer> relative = new TreeMap<>();
        Map<String, Integer> fiscal = new TreeMap<>();
        Map<String, Integer> irregular = new TreeMap<>();
        Map<String, Integer> unmatched = new TreeMap<>();
        for (TableRows t : built.values()) {
            if (fixedTables.contains(t.table())) {
                continue;
            }
            Map<String, Row> b = byMovedBackKey(t, shifted.get(t.table()), 0);
            Map<String, Row> c =
                    byMovedBackKey(t, shiftedMonth == null ? null : shiftedMonth.get(t.table()), 1);
            Map<String, Row> cYears =
                    byMovedBackKey(t, shiftedMonth == null ? null : shiftedMonth.get(t.table()), 0);
            for (Row r : t.rows().values()) {
                Row o = b.get(r.key());
                if (o == null) {
                    unmatched.merge(t.table(), 1, Integer::sum);
                    continue;
                }
                Row m = c.get(r.key());
                if (m == null) {
                    m = cYears.get(r.key());
                }
                for (int i = 0; i < r.values().length; i++) {
                    String av = r.values()[i];
                    String bv = o.values()[i];
                    if (av == null ? bv == null : av.equals(bv)) {
                        continue;
                    }
                    String cell = t.table() + "." + t.columns().get(i);
                    String cv = m == null ? null : m.values()[i];
                    int clock = classify(t.kinds().get(i), av, bv, cv, tolerance);
                    if (clock == FIXED) {
                        irregular.merge(cell, 1, Integer::sum);
                        continue;
                    }
                    r.relative().set(2 * i);
                    if (clock == FISCAL) {
                        r.relative().set(2 * i + 1);
                        fiscal.merge(cell, 1, Integer::sum);
                    } else {
                        relative.merge(cell, 1, Integer::sum);
                    }
                }
            }
        }
        return new ClockReport(relative, fiscal, irregular, unmatched);
    }

    private static int classify(Kind kind, String a, String b, String c, Duration tolerance) {
        if (a == null || b == null) {
            return FIXED;
        }
        if (kind == Kind.DATE || kind == Kind.TIMESTAMP) {
            LocalDateTime ta = time(a);
            LocalDateTime tb = time(b);
            if (!within(ta.plusYears(KitBuild.SHIFT_YEARS), tb, tolerance)) {
                return FIXED;
            }
            if (c == null) {
                return MONTHLY;
            }
            LocalDateTime tc = time(c);
            if (within(tb, tc, tolerance) || within(tb.plusMonths(12), tc, tolerance)) {
                return FISCAL;
            }
            return MONTHLY;
        }
        if (kind == Kind.STRING && b.equals(shiftText(a, KitBuild.SHIFT_YEARS, 0))) {
            if ("FY".equals(textPeriod(a)) || b.equals(c)) {
                return FISCAL;
            }
            return MONTHLY;
        }
        return FIXED;
    }

    /** Rows of a shifted build by their key with moved dates / periods moved back. */
    private static Map<String, Row> byMovedBackKey(TableRows base, TableRows shifted, int months) {
        Map<String, Row> out = new HashMap<>();
        if (shifted == null) {
            return out;
        }
        for (Row r : shifted.rows().values()) {
            out.putIfAbsent(r.key(), r);
            String[] moved = r.values().clone();
            boolean changed = false;
            for (int i : shifted.pk()) {
                String v = moved[i];
                if (v == null) {
                    continue;
                }
                Kind k = shifted.kinds().get(i);
                if (k == Kind.DATE || k == Kind.TIMESTAMP) {
                    moved[i] =
                            TS.format(time(v).minusMonths(months).minusYears(KitBuild.SHIFT_YEARS));
                    changed = true;
                } else if (k == Kind.STRING && textPeriod(v) != null) {
                    String back =
                            shiftText(
                                    v,
                                    -KitBuild.SHIFT_YEARS,
                                    "FY".equals(textPeriod(v)) ? 0 : -months);
                    if (back != null) {
                        moved[i] = back;
                        changed = true;
                    }
                }
            }
            if (changed) {
                out.putIfAbsent(key(moved, base.pk()), r);
            }
        }
        return out;
    }

    private static boolean within(
            LocalDateTime expected, LocalDateTime actual, Duration tolerance) {
        Duration d = Duration.between(expected, actual);
        return !d.isNegative() && d.compareTo(tolerance) <= 0
                // a date computed from today may fall on the next day if the builds crossed
                // midnight
                || expected.toLocalDate().plusDays(1).equals(actual.toLocalDate())
                        && expected.toLocalTime().equals(actual.toLocalTime());
    }

    /** Whether two rows hold the same values; relative cells compare within the tolerance. */
    static boolean same(TableRows t, Row a, Row b, Duration tolerance) {
        for (int i = 0; i < a.values().length; i++) {
            String x = a.values()[i];
            String y = b.values()[i];
            if (x == null ? y == null : x.equals(y)) {
                continue;
            }
            Kind k = t.kinds().get(i);
            if (x != null
                    && y != null
                    && (k == Kind.DATE || k == Kind.TIMESTAMP)
                    && relative(a, i)
                    && relative(b, i)) {
                Duration d = Duration.between(time(x), time(y)).abs();
                if (d.compareTo(tolerance) <= 0) {
                    continue;
                }
            }
            return false;
        }
        return true;
    }

    /** Whether a canonical date-time is midnight on the last day of its month. */
    static boolean lastDayMidnight(String canonical) {
        LocalDateTime t = time(canonical);
        LocalDate d = t.toLocalDate();
        return t.toLocalTime().toSecondOfDay() == 0
                && t.getNano() == 0
                && d.getDayOfMonth() == d.lengthOfMonth();
    }

    /** Debug text of a row. */
    static String show(Row r) {
        return Arrays.toString(r.values());
    }

    /** The foreign keys between tables of the catalog (child table to constraint). */
    static Map<String, List<Constraint>> foreignKeysByChild(Catalog catalog) {
        Map<String, List<Constraint>> out = new TreeMap<>();
        for (Constraint fk : catalog.foreignKeys()) {
            out.computeIfAbsent(fk.table(), k -> new ArrayList<>()).add(fk);
        }
        return out;
    }
}
