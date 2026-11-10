package in.gov.fci.payroll.db.kit;

import in.gov.fci.payroll.db.kit.Catalog.Constraint;
import in.gov.fci.payroll.db.kit.KitData.Row;
import in.gov.fci.payroll.db.kit.KitData.TableRows;
import in.gov.fci.payroll.db.kit.KitOrder.TableOrder;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The row operations that take a schema from one data set's end state to the next (03: empty to
 * REFERENCE, 04: REFERENCE to DEMO, 05: DEMO to EDGE, 05b: DEMO to SCREENS; each from its parent's,
 * KitBuild.Profile#parent), in an order no foreign key can refuse, and the independent {@link
 * #validate validator} that replays them. INSTALL_KIT.md "Data order".
 *
 * <ul>
 *   <li>A row of the earlier set that the later one changes or drops is deleted, together with
 *       every earlier row that references it (transitively), children first; then the later set's
 *       rows that are not already there are inserted, parents first.
 *   <li>Tables in foreign-key order ({@link KitOrder}); rows of a self-referencing table by level
 *       (a parent row before its children).
 *   <li>The foreign keys that close a cycle between tables ({@link TableOrder#deferred()}) are
 *       switched off for the load by the script and switched on again, with validation, after it.
 *       Writing the nullable side NULL and updating it later is not used: CHECK constraints of this
 *       schema tie several of those columns to a status (CHK_HRMS_TASK_ORDER refused the NULL), so
 *       the rows are loaded as they are.
 * </ul>
 */
final class KitPlan {

    private KitPlan() {}

    /** A row operation. */
    sealed interface Op permits Insert, Delete {
        String table();

        Row row();
    }

    /** Insert a row with all its values. */
    record Insert(String table, Row row) implements Op {}

    /** Delete an existing row (by its key). */
    record Delete(String table, Row row) implements Op {}

    /** What a plan does, for the manifest. */
    record Summary(Map<String, Integer> deletes, Map<String, Integer> inserts) {}

    /** The operations from {@code prev} (may be empty) to {@code next}. */
    static List<Op> plan(
            Catalog catalog,
            TableOrder order,
            Map<String, TableRows> prev,
            Map<String, TableRows> next,
            Duration tolerance) {
        Map<String, LinkedHashMap<String, Row>> deleted = new TreeMap<>();
        // 1. Earlier rows the later set changes or drops.
        Deque<Object[]> work = new ArrayDeque<>();
        for (TableRows p : prev.values()) {
            TableRows n = next.get(p.table());
            for (Row r : p.rows().values()) {
                Row later = n == null ? null : n.rows().get(r.key());
                if (later == null || !KitData.same(p, r, later, tolerance)) {
                    if (mark(deleted, p.table(), r)) {
                        work.add(new Object[] {p.table(), r});
                    }
                }
            }
        }
        // 2. Every earlier row that references a deleted row goes too (it is reinserted below if
        // the later set still has it).
        Map<String, List<Constraint>> toParent = new TreeMap<>();
        for (Constraint fk : catalog.foreignKeys()) {
            toParent.computeIfAbsent(fk.refTable(), k -> new ArrayList<>()).add(fk);
        }
        Map<Constraint, Map<String, List<Row>>> children = new HashMap<>();
        while (!work.isEmpty()) {
            Object[] w = work.poll();
            String table = (String) w[0];
            Row r = (Row) w[1];
            for (Constraint fk : toParent.getOrDefault(table, List.of())) {
                String tuple = KitData.tuple(prev.get(table), r.values(), fk.refColumns());
                TableRows child = prev.get(fk.table());
                if (tuple == null || child == null) {
                    continue;
                }
                Map<String, List<Row>> index =
                        children.computeIfAbsent(fk, k -> childIndex(child, k));
                for (Row c : index.getOrDefault(tuple, List.of())) {
                    if (mark(deleted, fk.table(), c)) {
                        work.add(new Object[] {fk.table(), c});
                    }
                }
            }
        }
        List<Op> ops = new ArrayList<>();
        Map<String, List<Constraint>> fksByChild = KitData.foreignKeysByChild(catalog);
        // 3. Deletes: tables children first, rows of a self-referencing table children first.
        List<String> reverse = new ArrayList<>(order.tables());
        Collections.reverse(reverse);
        for (String table : reverse) {
            LinkedHashMap<String, Row> rows = deleted.get(table);
            if (rows != null) {
                for (Row r : levels(prev.get(table), rows, fksByChild.get(table)).reversed()) {
                    ops.add(new Delete(table, r));
                }
            }
        }
        // 4. Inserts: the later set's rows that are not there, parents first.
        Map<String, Set<String>> kept = new HashMap<>();
        Alive alive = new Alive(catalog, next);
        for (TableRows p : prev.values()) {
            Set<String> keys = new HashSet<>(p.rows().keySet());
            LinkedHashMap<String, Row> gone = deleted.get(p.table());
            if (gone != null) {
                keys.removeAll(gone.keySet());
            }
            kept.put(p.table(), keys);
            for (String k : keys) {
                alive.add(p.table(), p.rows().get(k).values());
            }
        }
        for (String table : order.tables()) {
            TableRows n = next.get(table);
            if (n == null) {
                continue;
            }
            LinkedHashMap<String, Row> rows = new LinkedHashMap<>();
            Set<String> have = kept.getOrDefault(table, Set.of());
            for (Row r : n.rows().values()) {
                if (!have.contains(r.key())) {
                    rows.put(r.key(), r);
                }
            }
            List<Constraint> fks = fksByChild.getOrDefault(table, List.of());
            for (Row r : levels(n, rows, fks)) {
                for (Constraint fk : fks) {
                    String tuple = KitData.tuple(n, r.values(), fk.columns());
                    if (tuple == null
                            || order.deferred().contains(fk)
                            || alive.has(fk, tuple)
                            || selfRow(n, r.values(), fk, tuple)) {
                        continue;
                    }
                    throw new IllegalStateException(
                            "cannot order: "
                                    + table
                                    + " row "
                                    + KitData.show(r)
                                    + " references "
                                    + fk.refTable()
                                    + " ("
                                    + fk.name()
                                    + ") before that row exists");
                }
                ops.add(new Insert(table, r));
                alive.add(table, r.values());
            }
        }
        return ops;
    }

    private static boolean selfRow(TableRows t, String[] values, Constraint fk, String tuple) {
        return fk.refTable().equals(t.table())
                && tuple.equals(KitData.tuple(t, values, fk.refColumns()));
    }

    private static boolean mark(Map<String, LinkedHashMap<String, Row>> deleted, String t, Row r) {
        return deleted.computeIfAbsent(t, k -> new LinkedHashMap<>()).putIfAbsent(r.key(), r)
                == null;
    }

    private static Map<String, List<Row>> childIndex(TableRows child, Constraint fk) {
        Map<String, List<Row>> index = new HashMap<>();
        for (Row c : child.rows().values()) {
            String tuple = KitData.tuple(child, c.values(), fk.columns());
            if (tuple != null) {
                index.computeIfAbsent(tuple, k -> new ArrayList<>()).add(c);
            }
        }
        return index;
    }

    /**
     * Rows of one table, each after the rows of the same set it references through a
     * self-referencing foreign key (a parent before its children), key order otherwise. A reference
     * cycle among the rows cannot be ordered and fails generation.
     */
    private static List<Row> levels(
            TableRows t, LinkedHashMap<String, Row> rows, List<Constraint> fks) {
        List<Constraint> self = new ArrayList<>();
        for (Constraint fk : fks == null ? List.<Constraint>of() : fks) {
            if (fk.refTable().equals(t.table())) {
                self.add(fk);
            }
        }
        if (self.isEmpty() || rows.size() < 2) {
            return new ArrayList<>(rows.values());
        }
        Map<String, Set<String>> before = new HashMap<>();
        for (Constraint fk : self) {
            Map<String, String> byTuple = new HashMap<>();
            for (Row r : rows.values()) {
                String tuple = KitData.tuple(t, r.values(), fk.refColumns());
                if (tuple != null) {
                    byTuple.put(tuple, r.key());
                }
            }
            for (Row r : rows.values()) {
                String tuple = KitData.tuple(t, r.values(), fk.columns());
                String parent = tuple == null ? null : byTuple.get(tuple);
                if (parent != null && !parent.equals(r.key())) {
                    before.computeIfAbsent(r.key(), k -> new LinkedHashSet<>()).add(parent);
                }
            }
        }
        List<String> order =
                KitOrder.topological(rows.keySet(), k -> before.getOrDefault(k, Set.of()));
        if (order == null) {
            throw new IllegalStateException(
                    "cannot order: rows of " + t.table() + " reference each other in a cycle");
        }
        List<Row> out = new ArrayList<>();
        for (String k : order) {
            out.add(rows.get(k));
        }
        return out;
    }

    /** Live rows by the key tuples that foreign keys reference. */
    static final class Alive {
        private final Map<String, Map<List<String>, Set<String>>> keys = new HashMap<>();
        private final Map<String, List<List<String>>> referenced = new HashMap<>();
        private final Map<String, TableRows> shape;

        Alive(Catalog catalog, Map<String, TableRows> shape) {
            this.shape = shape;
            for (Constraint fk : catalog.foreignKeys()) {
                List<List<String>> sets =
                        referenced.computeIfAbsent(fk.refTable(), k -> new ArrayList<>());
                if (!sets.contains(fk.refColumns())) {
                    sets.add(fk.refColumns());
                }
            }
        }

        void add(String table, String[] values) {
            change(table, values, true);
        }

        void remove(String table, String[] values) {
            change(table, values, false);
        }

        private void change(String table, String[] values, boolean add) {
            TableRows t = shape.get(table);
            for (List<String> cols : referenced.getOrDefault(table, List.of())) {
                String tuple = KitData.tuple(t, values, cols);
                if (tuple == null) {
                    continue;
                }
                Set<String> set =
                        keys.computeIfAbsent(table, k -> new HashMap<>())
                                .computeIfAbsent(cols, k -> new HashSet<>());
                if (add) {
                    set.add(tuple);
                } else {
                    set.remove(tuple);
                }
            }
        }

        boolean has(Constraint fk, String tuple) {
            return keys.getOrDefault(fk.refTable(), Map.of())
                    .getOrDefault(fk.refColumns(), Set.of())
                    .contains(tuple);
        }
    }

    /**
     * Replays {@code ops} on {@code prev} without a database and fails on the first operation a
     * database would refuse for ordering reasons: an insert whose foreign-key values have no parent
     * row yet, a duplicate key, a delete of a row another live row still references; a foreign key
     * of {@code disabled} (switched off by the script during the load) is only checked at the end,
     * when the script switches it on again with validation. At the end the state must be exactly
     * {@code next}. Independent of {@link #plan}: it reads only the operations and the catalog.
     */
    static Summary validate(
            Catalog catalog,
            Map<String, TableRows> prev,
            Map<String, TableRows> next,
            List<Op> ops,
            Set<Constraint> disabled,
            Duration tolerance) {
        Map<String, Map<String, String[]>> live = new HashMap<>();
        for (TableRows p : prev.values()) {
            Map<String, String[]> m = new HashMap<>();
            for (Row r : p.rows().values()) {
                m.put(r.key(), r.values());
            }
            live.put(p.table(), m);
        }
        Map<String, List<Constraint>> fksByChild = KitData.foreignKeysByChild(catalog);
        Map<String, List<Constraint>> fksByParent = new HashMap<>();
        for (Constraint fk : catalog.foreignKeys()) {
            fksByParent.computeIfAbsent(fk.refTable(), k -> new ArrayList<>()).add(fk);
        }
        Map<Constraint, Map<String, Integer>> children = new HashMap<>();
        Alive alive = new Alive(catalog, next);
        for (Map.Entry<String, Map<String, String[]>> e : live.entrySet()) {
            for (String[] v : e.getValue().values()) {
                alive.add(e.getKey(), v);
                count(children, fksByChild.get(e.getKey()), shape(next, prev, e.getKey()), v, 1);
            }
        }
        Map<String, Integer> deletes = new TreeMap<>();
        Map<String, Integer> inserts = new TreeMap<>();
        int step = 0;
        for (Op op : ops) {
            step++;
            TableRows t = shape(next, prev, op.table());
            Map<String, String[]> rows = live.computeIfAbsent(op.table(), k -> new HashMap<>());
            String where =
                    "data step "
                            + step
                            + " "
                            + op.getClass().getSimpleName()
                            + " "
                            + op.table()
                            + " "
                            + KitData.show(op.row());
            switch (op) {
                case Insert ins -> {
                    if (rows.containsKey(ins.row().key())) {
                        throw new IllegalStateException(where + ": key already present");
                    }
                    String[] v = ins.row().values();
                    for (Constraint fk : fksByChild.getOrDefault(op.table(), List.of())) {
                        String tuple = KitData.tuple(t, v, fk.columns());
                        if (tuple != null
                                && !disabled.contains(fk)
                                && !alive.has(fk, tuple)
                                && !selfRow(t, v, fk, tuple)) {
                            throw new IllegalStateException(
                                    where
                                            + ": references a "
                                            + fk.refTable()
                                            + " row not inserted yet ("
                                            + fk.name()
                                            + ")");
                        }
                    }
                    rows.put(ins.row().key(), v);
                    alive.add(op.table(), v);
                    count(children, fksByChild.get(op.table()), t, v, 1);
                    inserts.merge(op.table(), 1, Integer::sum);
                }
                case Delete del -> {
                    String[] v = rows.get(del.row().key());
                    if (v == null) {
                        throw new IllegalStateException(where + ": row not present");
                    }
                    count(children, fksByChild.get(op.table()), t, v, -1);
                    for (Constraint fk : fksByParent.getOrDefault(op.table(), List.of())) {
                        String tuple = KitData.tuple(t, v, fk.refColumns());
                        if (tuple != null
                                && !disabled.contains(fk)
                                && children.getOrDefault(fk, Map.of()).getOrDefault(tuple, 0) > 0) {
                            throw new IllegalStateException(
                                    where
                                            + ": still referenced by "
                                            + fk.table()
                                            + " ("
                                            + fk.name()
                                            + ")");
                        }
                    }
                    rows.remove(del.row().key());
                    alive.remove(op.table(), v);
                    deletes.merge(op.table(), 1, Integer::sum);
                }
            }
        }
        // Every foreign key holds at the end (the disabled ones are validated when switched on).
        for (Map.Entry<String, Map<String, String[]>> e : live.entrySet()) {
            TableRows t = shape(next, prev, e.getKey());
            for (Constraint fk : fksByChild.getOrDefault(e.getKey(), List.of())) {
                for (String[] v : e.getValue().values()) {
                    String tuple = KitData.tuple(t, v, fk.columns());
                    if (tuple != null && !alive.has(fk, tuple) && !selfRow(t, v, fk, tuple)) {
                        throw new IllegalStateException(
                                "after the script "
                                        + e.getKey()
                                        + " row "
                                        + java.util.Arrays.toString(v)
                                        + " references a missing "
                                        + fk.refTable()
                                        + " ("
                                        + fk.name()
                                        + ")");
                    }
                }
            }
        }
        // The end state is exactly the later data set.
        for (TableRows n : next.values()) {
            Map<String, String[]> rows = live.getOrDefault(n.table(), Map.of());
            if (rows.size() != n.rows().size()) {
                throw new IllegalStateException(
                        n.table()
                                + ": "
                                + rows.size()
                                + " rows after the script, expected "
                                + n.rows().size());
            }
            for (Row r : n.rows().values()) {
                String[] v = rows.get(r.key());
                if (v == null
                        || !KitData.same(n, new Row(r.key(), v, r.relative()), r, tolerance)) {
                    throw new IllegalStateException(
                            n.table() + ": row " + KitData.show(r) + " differs after the script");
                }
            }
        }
        return new Summary(deletes, inserts);
    }

    private static TableRows shape(
            Map<String, TableRows> next, Map<String, TableRows> prev, String table) {
        TableRows t = next.get(table);
        return t != null ? t : prev.get(table);
    }

    private static void count(
            Map<Constraint, Map<String, Integer>> children,
            List<Constraint> fks,
            TableRows t,
            String[] values,
            int delta) {
        if (fks == null) {
            return;
        }
        for (Constraint fk : fks) {
            String tuple = KitData.tuple(t, values, fk.columns());
            if (tuple != null && !selfRow(t, values, fk, tuple)) {
                children.computeIfAbsent(fk, k -> new HashMap<>())
                        .merge(tuple, delta, Integer::sum);
            }
        }
    }
}
