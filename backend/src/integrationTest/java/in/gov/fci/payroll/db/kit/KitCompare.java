package in.gov.fci.payroll.db.kit;

import in.gov.fci.payroll.db.kit.Catalog.Column;
import in.gov.fci.payroll.db.kit.Catalog.Constraint;
import in.gov.fci.payroll.db.kit.Catalog.Index;
import in.gov.fci.payroll.db.kit.Catalog.Kind;
import in.gov.fci.payroll.db.kit.Catalog.Sequence;
import in.gov.fci.payroll.db.kit.Catalog.Table;
import in.gov.fci.payroll.db.kit.Catalog.Trigger;
import in.gov.fci.payroll.db.kit.Catalog.View;
import in.gov.fci.payroll.db.kit.KitData.Row;
import in.gov.fci.payroll.db.kit.KitData.TableRows;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Equivalence of two schemas, one built by Flyway and one by the install kit: tables, columns
 * (type, length, nullability, default, identity), constraints, indexes, views, triggers, sequences,
 * row counts per table and, when asked, every row value (timestamps within a tolerance: a value
 * computed from "now" moves with the build time). Every difference is listed; an empty list means
 * equivalent. INSTALL_KIT.md "Equivalence check".
 */
final class KitCompare {

    private static final Pattern IDENTITY_START =
            Pattern.compile("(START WITH)( |: )-?\\d+", Pattern.CASE_INSENSITIVE);

    private KitCompare() {}

    /**
     * Differences between {@code expected} (Flyway) and {@code actual} (kit).
     *
     * @param rows when true, every row is compared (else counts only)
     * @param tolerance for date / timestamp cells
     */
    static List<String> compare(
            Catalog expected,
            Map<String, TableRows> expectedRows,
            Catalog actual,
            Map<String, TableRows> actualRows,
            boolean rows,
            Duration tolerance) {
        List<String> out = new ArrayList<>();
        diff(out, "table", expected.tables(), actual.tables(), KitCompare::tableShape);
        diff(
                out,
                "constraint",
                byName(expected.constraints()),
                byName(actual.constraints()),
                KitCompare::constraintShape);
        diff(out, "index", index(expected.indexes()), index(actual.indexes()), Index::ddl);
        diff(out, "view", expected.views(), actual.views(), KitCompare::viewShape);
        diff(
                out,
                "trigger",
                trigger(expected.triggers()),
                trigger(actual.triggers()),
                t ->
                        t.table()
                                + " "
                                + t.timing()
                                + " "
                                + t.events()
                                + " "
                                + t.body()
                                + " "
                                + t.enabled());
        diff(
                out,
                "sequence",
                sequence(expected.sequences()),
                sequence(actual.sequences()),
                s -> normalise(s.ddl()) + " BY " + s.increment());
        for (String table : expectedRows.keySet()) {
            TableRows e = expectedRows.get(table);
            TableRows a = actualRows.get(table);
            if (a == null) {
                continue; // reported as a missing table
            }
            if (e.rows().size() != a.rows().size()) {
                out.add(
                        "rows of "
                                + table
                                + ": Flyway "
                                + e.rows().size()
                                + ", kit "
                                + a.rows().size());
                continue;
            }
            if (rows) {
                int shown = 0;
                for (Row r : e.rows().values()) {
                    Row k = a.rows().get(r.key());
                    String why = k == null ? "missing" : cells(e, r, k, tolerance);
                    if (why != null && shown++ < 5) {
                        out.add("row " + table + " " + r.key().replace('\u0001', '|') + ": " + why);
                    }
                }
                if (shown > 5) {
                    out.add("row " + table + ": " + (shown - 5) + " more rows differ");
                }
            }
        }
        // Every identity continues above its rows, every sequence above its Flyway value.
        for (Table t : actual.tables().values()) {
            for (Column c : t.columns()) {
                if (c.identity() == null || actualRows.get(t.name()) == null) {
                    continue;
                }
                TableRows r = actualRows.get(t.name());
                int i = r.index(c.name());
                long max = 0;
                for (Row row : r.rows().values()) {
                    if (row.values()[i] != null) {
                        max = Math.max(max, new BigDecimal(row.values()[i]).longValueExact());
                    }
                }
                if (c.identity().next() <= max) {
                    out.add(
                            "identity "
                                    + t.name()
                                    + "."
                                    + c.name()
                                    + " gives "
                                    + c.identity().next()
                                    + " next but rows reach "
                                    + max);
                }
            }
        }
        Map<String, Sequence> flywaySeq = sequence(expected.sequences());
        for (Sequence s : actual.sequences()) {
            Sequence f = flywaySeq.get(s.name());
            if (f != null && s.next() < f.next()) {
                out.add(
                        "sequence "
                                + s.name()
                                + " gives "
                                + s.next()
                                + " next, Flyway "
                                + f.next());
            }
        }
        return out;
    }

    private static String cells(TableRows t, Row e, Row a, Duration tolerance) {
        for (int i = 0; i < e.values().length; i++) {
            String x = e.values()[i];
            String y = a.values()[i];
            if (x == null ? y == null : x.equals(y)) {
                continue;
            }
            Kind k = t.kinds().get(i);
            if (x != null && y != null && (k == Kind.DATE || k == Kind.TIMESTAMP)) {
                Duration d = Duration.between(KitData.time(x), KitData.time(y)).abs();
                if (d.compareTo(tolerance) <= 0) {
                    continue;
                }
            }
            if (t.table().equalsIgnoreCase("flyway_schema_history")
                    && Set.of("installed_by", "installed_on", "execution_time")
                            .contains(t.columns().get(i))) {
                continue;
            }
            return t.columns().get(i) + " Flyway " + abbreviate(x) + " kit " + abbreviate(y);
        }
        return null;
    }

    private static String abbreviate(String s) {
        return s == null ? "NULL" : s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }

    private static <T> void diff(
            List<String> out,
            String what,
            Map<String, T> e,
            Map<String, T> a,
            Function<T, String> shape) {
        Set<String> names = new TreeSet<>(e.keySet());
        names.addAll(a.keySet());
        for (String n : names) {
            T x = e.get(n);
            T y = a.get(n);
            if (x == null) {
                out.add(what + " " + n + ": only in the kit");
            } else if (y == null) {
                out.add(what + " " + n + ": only in Flyway");
            } else if (!shape.apply(x).equals(shape.apply(y))) {
                String fx = shape.apply(x);
                String fy = shape.apply(y);
                int at = 0;
                while (at < fx.length() && at < fy.length() && fx.charAt(at) == fy.charAt(at)) {
                    at++;
                }
                int from = Math.max(0, at - 80);
                out.add(
                        what
                                + " "
                                + n
                                + ": differs at character "
                                + at
                                + "\n    Flyway: ..."
                                + abbreviate(fx.substring(from), 200)
                                + "\n    kit:    ..."
                                + abbreviate(fy.substring(Math.min(from, fy.length())), 200));
            }
        }
    }

    private static String abbreviate(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }

    private static String tableShape(Table t) {
        StringBuilder b = new StringBuilder(normalise(t.ddl()));
        for (Column c : t.columns()) {
            b.append('|')
                    .append(c.name())
                    .append(' ')
                    .append(c.type())
                    .append(c.nullable() ? " NULL" : " NOT NULL")
                    .append(" DEFAULT ")
                    .append(c.defaultExpr() == null ? "-" : c.defaultExpr().strip())
                    .append(
                            c.identity() == null
                                    ? ""
                                    : " IDENTITY "
                                            + c.identity().generation()
                                            + (c.identity().onNull() ? " ON NULL" : "")
                                            + " BY "
                                            + c.identity().increment());
        }
        for (String comment : t.comments()) {
            b.append('|').append(comment);
        }
        return b.toString();
    }

    /**
     * A view as the multiset of its trimmed lines: H2 writes a view's join conditions in the order
     * its optimizer chose, which changes when the view is created again from that text (the same
     * conditions, one per line, in another order).
     */
    private static String viewShape(View v) {
        String ddl = v.ddl();
        int as = ddl.indexOf(" AS");
        List<String> tokens = new ArrayList<>();
        java.util.regex.Matcher m = TOKEN.matcher(as < 0 ? ddl : ddl.substring(as));
        while (m.find()) {
            String t = m.group();
            if (!t.equalsIgnoreCase("AND")
                    && !t.equalsIgnoreCase("ON")
                    && !t.equalsIgnoreCase("WHERE")) {
                tokens.add(t);
            }
        }
        java.util.Collections.sort(tokens);
        return (as < 0 ? "" : ddl.substring(0, as)) + " | " + String.join(" ", tokens);
    }

    /** Identifiers, literals, words and comparison operators of a view text. */
    private static final Pattern TOKEN = Pattern.compile("\"[^\"]+\"|'[^']*'|\\w+|[<>=!]+");

    private static String normalise(String ddl) {
        return IDENTITY_START.matcher(ddl).replaceAll("$1 1");
    }

    /** A system-named constraint is matched by its shape (Oracle renames it on every build). */
    private static Map<String, Constraint> byName(List<Constraint> list) {
        Map<String, Constraint> out = new TreeMap<>();
        for (Constraint c : list) {
            String k =
                    c.systemName()
                            ? "(system) "
                                    + c.type()
                                    + " "
                                    + c.table()
                                    + "("
                                    + String.join(",", c.columns())
                                    + ")"
                                    + (c.refTable() == null ? "" : " -> " + c.refTable())
                                    + (c.type() == 'C'
                                            ? " " + Integer.toHexString(checkText(c).hashCode())
                                            : "")
                            : c.name();
            while (out.containsKey(k)) {
                k = k + "'";
            }
            out.put(k, c);
        }
        return out;
    }

    private static String constraintShape(Constraint c) {
        return c.type()
                + " "
                + c.table()
                + " "
                + c.columns()
                + " "
                + c.refTable()
                + " "
                + c.refColumns()
                + " "
                + (c.systemName() ? checkText(c) : c.ddl());
    }

    private static String checkText(Constraint c) {
        String ddl = c.ddl();
        int i = ddl.toUpperCase(java.util.Locale.ROOT).indexOf(" CHECK");
        return i < 0 ? ddl.replaceAll("\"(SYS_C|CONSTRAINT_)[0-9A-F]+\"", "") : ddl.substring(i);
    }

    private static Map<String, Index> index(List<Index> list) {
        Map<String, Index> out = new TreeMap<>();
        for (Index i : list) {
            out.put(i.name(), i);
        }
        return out;
    }

    private static Map<String, Trigger> trigger(List<Trigger> list) {
        Map<String, Trigger> out = new TreeMap<>();
        for (Trigger t : list) {
            out.put(t.name(), t);
        }
        return out;
    }

    private static Map<String, Sequence> sequence(List<Sequence> list) {
        Map<String, Sequence> out = new TreeMap<>();
        for (Sequence s : list) {
            out.put(s.name(), s);
        }
        return out;
    }
}
