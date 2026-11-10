package in.gov.fci.payroll.db.kit;

import in.gov.fci.payroll.db.kit.Catalog.CodeObject;
import in.gov.fci.payroll.db.kit.Catalog.Constraint;
import in.gov.fci.payroll.db.kit.Catalog.Index;
import in.gov.fci.payroll.db.kit.Catalog.Sequence;
import in.gov.fci.payroll.db.kit.Catalog.Table;
import in.gov.fci.payroll.db.kit.Catalog.Trigger;
import in.gov.fci.payroll.db.kit.Catalog.View;
import in.gov.fci.payroll.db.kit.KitOrder.TableOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The DDL of the kit as an ordered list of {@link Step}s, each with the objects it creates and the
 * objects it needs, and the static order validators that fail generation when a statement needs an
 * object not created earlier (02_ddl) or drops an object while something that depends on it still
 * exists (01_drop). INSTALL_KIT.md "DDL order" / "Drop order".
 */
final class KitSteps {

    private KitSteps() {}

    /**
     * One statement of 01_drop or 02_ddl.
     *
     * @param section the heading it is written under
     * @param sql the statement (without vendor terminator handling: the writer adds it)
     * @param plsql whether it is a PL/SQL unit (Oracle: ends with a "/" line)
     * @param creates object keys it creates (T:, S:, K:, I:, V:, TR:, C:)
     * @param requires object keys that must exist before it (01) or be gone before it (00)
     */
    record Step(
            String section, String sql, boolean plsql, Set<String> creates, Set<String> requires) {}

    /** Object keys used by the validators. */
    static String table(String n) {
        return "T:" + n;
    }

    /** A primary or unique key by its column set (a foreign key may list them in another order). */
    static String key(String table, List<String> columns) {
        return "K:" + table + "(" + String.join(",", new TreeSet<>(columns)) + ")";
    }

    /**
     * 02_ddl in its order: sequences, tables, primary / unique keys, checks, comments, indexes,
     * foreign keys (all after all tables: cycles and self references never fail), views by their
     * view dependencies, Oracle types / package specs / functions / procedures by dependency, then
     * bodies, triggers last (after their tables, sequences and code), synonyms.
     */
    static List<Step> ddl(Catalog c, TableOrder order, DdlText text) {
        List<Step> out = new ArrayList<>();
        Set<String> sequenceNames = new TreeSet<>();
        for (Sequence s : c.sequences()) {
            sequenceNames.add(s.name());
            out.add(
                    new Step(
                            "Sequences",
                            text.sequence(s),
                            false,
                            Set.of("S:" + s.name()),
                            Set.of()));
        }
        for (String name : order.tables()) {
            Table t = c.tables().get(name);
            Set<String> needs = new LinkedHashSet<>();
            for (String s : sequenceNames) {
                if (t.ddl().contains("\"" + s + "\"")) {
                    needs.add("S:" + s);
                }
            }
            out.add(new Step("Tables", text.table(t), false, Set.of(table(name)), needs));
        }
        for (char type : new char[] {'P', 'U'}) {
            for (Constraint k : c.constraints()) {
                if (k.type() == type) {
                    out.add(
                            new Step(
                                    type == 'P' ? "Primary keys" : "Unique keys",
                                    text.constraint(k),
                                    false,
                                    Set.of(key(k.table(), k.columns()), "N:" + k.name()),
                                    Set.of(table(k.table()))));
                }
            }
        }
        for (Constraint k : c.constraints()) {
            if (k.type() == 'C') {
                out.add(
                        new Step(
                                "Check constraints",
                                text.constraint(k),
                                false,
                                Set.of("N:" + k.name()),
                                Set.of(table(k.table()))));
            }
        }
        for (String name : order.tables()) {
            for (String comment : c.tables().get(name).comments()) {
                out.add(
                        new Step(
                                "Comments",
                                comment,
                                c.vendor() == Catalog.Vendor.ORACLE,
                                Set.of(),
                                Set.of(table(name))));
            }
        }
        Map<String, Set<String>> keysOf = new TreeMap<>();
        for (Constraint k : c.constraints()) {
            if (k.type() == 'P' || k.type() == 'U') {
                keysOf.computeIfAbsent(k.table(), x -> new TreeSet<>())
                        .add(key(k.table(), k.columns()));
            }
        }
        for (Index i : c.indexes()) {
            Set<String> needs = new LinkedHashSet<>();
            needs.add(table(i.table()));
            needs.addAll(keysOf.getOrDefault(i.table(), Set.of()));
            out.add(new Step("Indexes", text.index(i), false, Set.of("I:" + i.name()), needs));
        }
        for (Constraint k : c.foreignKeys()) {
            out.add(
                    new Step(
                            "Foreign keys",
                            text.constraint(k),
                            false,
                            Set.of("N:" + k.name()),
                            new LinkedHashSet<>(
                                    List.of(
                                            table(k.table()),
                                            table(k.refTable()),
                                            key(k.refTable(), k.refColumns())))));
        }
        List<String> views = viewOrder(c);
        for (String v : views) {
            View view = c.views().get(v);
            Set<String> needs = new LinkedHashSet<>();
            for (String u : view.uses()) {
                needs.add("V:" + u);
            }
            out.add(new Step("Views", text.view(view), false, Set.of("V:" + v), needs));
        }
        List<CodeObject> code = codeOrder(c);
        for (CodeObject o : code) {
            Set<String> needs = new LinkedHashSet<>();
            for (String u : o.uses()) {
                needs.add("C:" + u);
            }
            if (o.type().endsWith(" BODY")) {
                needs.add("C:" + o.type().replace(" BODY", "") + " " + o.name());
            }
            out.add(
                    new Step(
                            "Code: " + o.type(),
                            o.ddl(),
                            !o.type().equals("SYNONYM"),
                            Set.of("C:" + o.type() + " " + o.name()),
                            needs));
        }
        for (Trigger t : c.triggers()) {
            Set<String> needs = new LinkedHashSet<>();
            needs.add(table(t.table()));
            needs.addAll(t.uses());
            out.add(
                    new Step(
                            "Triggers",
                            text.trigger(t),
                            c.vendor() == Catalog.Vendor.ORACLE,
                            Set.of("TR:" + t.name()),
                            needs));
        }
        return out;
    }

    /** Views ordered by the views they read. */
    static List<String> viewOrder(Catalog c) {
        List<String> order = KitOrder.topological(c.views().keySet(), v -> c.views().get(v).uses());
        if (order == null) {
            throw new IllegalStateException("views reference each other in a cycle");
        }
        return order;
    }

    /**
     * Code objects: types, then package specs / functions / procedures by USER_DEPENDENCIES, then
     * package and type bodies, then synonyms.
     */
    static List<CodeObject> codeOrder(Catalog c) {
        Map<String, CodeObject> byKey = new TreeMap<>();
        for (CodeObject o : c.code()) {
            byKey.put(o.type() + " " + o.name(), o);
        }
        List<String> order =
                KitOrder.topological(
                        byKey.keySet(),
                        k -> {
                            Set<String> before = new HashSet<>(byKey.get(k).uses());
                            if (k.endsWith(" BODY") || k.contains(" BODY ")) {
                                before.add(
                                        byKey.get(k).type().replace(" BODY", "")
                                                + " "
                                                + byKey.get(k).name());
                            }
                            return before;
                        });
        if (order == null) {
            throw new IllegalStateException("code objects depend on each other in a cycle");
        }
        List<CodeObject> out = new ArrayList<>();
        for (String phase : List.of("TYPE", "SPEC", "BODY", "SYNONYM")) {
            for (String k : order) {
                CodeObject o = byKey.get(k);
                String p =
                        o.type().equals("TYPE")
                                ? "TYPE"
                                : o.type().endsWith("BODY")
                                        ? "BODY"
                                        : o.type().equals("SYNONYM") ? "SYNONYM" : "SPEC";
                if (p.equals(phase)) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    /**
     * 01_drop: views (a view before the views it reads), tables children first (reverse foreign key
     * order; a table of a cycle with its partners, which CASCADE releases), sequences after the
     * tables that use them, then code.
     */
    static List<Step> drop(Catalog c, TableOrder order, DdlText text) {
        List<Step> out = new ArrayList<>();
        List<String> views = new ArrayList<>(viewOrder(c));
        java.util.Collections.reverse(views);
        for (String v : views) {
            Set<String> gone = new TreeSet<>();
            for (View other : c.views().values()) {
                if (other.uses().contains(v)) {
                    gone.add("V:" + other.name());
                }
            }
            out.add(new Step("Views", text.dropView(v), text.plsqlDrops(), Set.of("V:" + v), gone));
        }
        Map<String, Set<String>> cycleOf = new TreeMap<>();
        for (Set<String> cycle : order.cycles()) {
            for (String t : cycle) {
                cycleOf.put(t, cycle);
            }
        }
        List<String> tables = new ArrayList<>(order.tables());
        java.util.Collections.reverse(tables);
        for (String t : tables) {
            Set<String> gone = new TreeSet<>();
            for (Constraint fk : c.foreignKeys()) {
                if (fk.refTable().equals(t)
                        && !fk.table().equals(t)
                        && !cycleOf.getOrDefault(t, Set.of()).contains(fk.table())) {
                    gone.add(table(fk.table()));
                }
            }
            for (View v : c.views().values()) {
                if (v.ddl().contains("\"" + t + "\"")) {
                    gone.add("V:" + v.name());
                }
            }
            out.add(
                    new Step(
                            "Tables",
                            text.dropTable(t),
                            text.plsqlDrops(),
                            Set.of(table(t)),
                            gone));
        }
        for (Sequence s : c.sequences()) {
            Set<String> gone = new TreeSet<>();
            for (Table t : c.tables().values()) {
                if (t.ddl().contains("\"" + s.name() + "\"")) {
                    gone.add(table(t.name()));
                }
            }
            out.add(
                    new Step(
                            "Sequences",
                            text.dropSequence(s.name()),
                            text.plsqlDrops(),
                            Set.of("S:" + s.name()),
                            gone));
        }
        List<CodeObject> code = new ArrayList<>(codeOrder(c));
        java.util.Collections.reverse(code);
        for (CodeObject o : code) {
            if (o.type().endsWith(" BODY")) {
                continue; // dropped with its specification
            }
            Set<String> gone = new TreeSet<>();
            for (CodeObject other : c.code()) {
                if (other.uses().contains(o.type() + " " + o.name())) {
                    gone.add("C:" + other.type() + " " + other.name());
                }
            }
            gone.removeIf(k -> k.endsWith(" BODY " + o.name()));
            out.add(
                    new Step(
                            "Code",
                            text.dropCode(o),
                            true,
                            Set.of("C:" + o.type() + " " + o.name()),
                            gone));
        }
        return out;
    }

    /**
     * Fails when a 02_ddl step needs an object no earlier step created, or creates one twice.
     *
     * @return the number of steps checked
     */
    static int validateDdl(List<Step> steps) {
        Set<String> created = new HashSet<>();
        int n = 0;
        for (Step s : steps) {
            n++;
            for (String r : s.requires()) {
                if (!created.contains(r)) {
                    throw new IllegalStateException(
                            "02_ddl step "
                                    + n
                                    + " ("
                                    + s.section()
                                    + ") needs "
                                    + r
                                    + " before it is created: "
                                    + first(s.sql()));
                }
            }
            for (String k : s.creates()) {
                if (!created.add(k) && !k.startsWith("K:")) {
                    throw new IllegalStateException(
                            "02_ddl step " + n + " creates " + k + " twice");
                }
            }
        }
        return n;
    }

    /**
     * Fails when a 01_drop step drops an object while an object that depends on it (a child table,
     * a view reading it, a table using a sequence) is still there.
     *
     * @return the number of steps checked
     */
    static int validateDrop(List<Step> steps) {
        Set<String> dropped = new HashSet<>();
        int n = 0;
        for (Step s : steps) {
            n++;
            for (String r : s.requires()) {
                if (!dropped.contains(r)) {
                    throw new IllegalStateException(
                            "01_drop step "
                                    + n
                                    + " drops "
                                    + s.creates()
                                    + " while "
                                    + r
                                    + " still exists");
                }
            }
            dropped.addAll(s.creates());
        }
        return n;
    }

    static String first(String sql) {
        return sql.lines().findFirst().orElse("");
    }

    /** Vendor text of each statement. */
    interface DdlText {
        String sequence(Sequence s);

        String table(Table t);

        String constraint(Constraint k);

        String index(Index i);

        String view(View v);

        String trigger(Trigger t);

        String dropView(String name);

        String dropTable(String name);

        String dropSequence(String name);

        String dropCode(CodeObject o);

        boolean plsqlDrops();
    }
}
