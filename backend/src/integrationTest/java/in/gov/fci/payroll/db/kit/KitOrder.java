package in.gov.fci.payroll.db.kit;

import in.gov.fci.payroll.db.kit.Catalog.Constraint;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Orders derived from the real dependencies of a {@link Catalog}: tables by their foreign keys
 * (parents first), views by the views they read, code by USER_DEPENDENCIES. A foreign-key cycle
 * between tables is broken on the keys that close it ({@link #backEdges}): the data scripts switch
 * those off for the load and on again, validated, after it.
 */
final class KitOrder {

    private KitOrder() {}

    /**
     * The table order of the data scripts.
     *
     * @param tables tables, parents first (ties by name)
     * @param deferred the foreign keys that close a cycle between tables: switched off during the
     *     data load and switched on again with validation
     * @param cycles the table cycles found (strongly connected groups of more than one table)
     * @param selfReferencing tables with a foreign key to themselves (rows ordered by level)
     */
    record TableOrder(
            List<String> tables,
            Set<Constraint> deferred,
            List<Set<String>> cycles,
            Set<String> selfReferencing) {}

    static TableOrder tables(Catalog catalog) {
        Set<String> names = new TreeSet<>(catalog.tables().keySet());
        List<Constraint> edges = new ArrayList<>();
        Set<String> self = new TreeSet<>();
        for (Constraint fk : catalog.foreignKeys()) {
            if (fk.table().equals(fk.refTable())) {
                self.add(fk.table());
            } else {
                edges.add(fk);
            }
        }
        List<Set<String>> sccs = stronglyConnected(names, edges);
        List<Set<String>> cycles = sccs.stream().filter(s -> s.size() > 1).toList();
        Set<Constraint> deferred = new LinkedHashSet<>();
        for (Set<String> cycle : cycles) {
            deferred.addAll(backEdges(cycle, edges));
        }
        List<Constraint> kept = edges.stream().filter(e -> !deferred.contains(e)).toList();
        Map<String, Set<String>> parents = new TreeMap<>();
        for (String n : names) {
            parents.put(n, new TreeSet<>());
        }
        for (Constraint fk : kept) {
            parents.get(fk.table()).add(fk.refTable());
        }
        List<String> order = topological(names, parents::get);
        if (order == null) {
            throw new IllegalStateException(
                    "foreign-key cycle with no nullable foreign key: cannot be ordered " + cycles);
        }
        return new TableOrder(order, deferred, cycles, self);
    }

    /**
     * The foreign keys that close a cycle inside one strongly connected group: the back edges of a
     * depth-first search in name order. Without them the group is acyclic, so few keys need to be
     * switched off for the load (INSTALL_KIT.md "Foreign-key cycles").
     */
    static List<Constraint> backEdges(Set<String> group, List<Constraint> edges) {
        Map<String, List<Constraint>> next = new TreeMap<>();
        for (Constraint e : edges) {
            if (group.contains(e.table()) && group.contains(e.refTable())) {
                next.computeIfAbsent(e.table(), k -> new ArrayList<>()).add(e);
            }
        }
        next.values().forEach(l -> l.sort(java.util.Comparator.comparing(Constraint::name)));
        List<Constraint> back = new ArrayList<>();
        Set<String> done = new java.util.HashSet<>();
        Set<String> onPath = new java.util.HashSet<>();
        for (String start : new TreeSet<>(group)) {
            visit(start, next, done, onPath, back);
        }
        return back;
    }

    private static void visit(
            String node,
            Map<String, List<Constraint>> next,
            Set<String> done,
            Set<String> onPath,
            List<Constraint> back) {
        if (done.contains(node)) {
            return;
        }
        onPath.add(node);
        for (Constraint e : next.getOrDefault(node, List.of())) {
            if (onPath.contains(e.refTable())) {
                back.add(e);
            } else {
                visit(e.refTable(), next, done, onPath, back);
            }
        }
        onPath.remove(node);
        done.add(node);
    }

    /** Whether every child column of a foreign key admits NULL. */
    static boolean nullable(Catalog catalog, Constraint fk) {
        return fk.columns().stream()
                .allMatch(c -> catalog.tables().get(fk.table()).column(c).nullable());
    }

    /**
     * Kahn's topological order of {@code nodes} given each node's prerequisites, ties broken by the
     * natural order of the names; null if the graph has a cycle.
     */
    static List<String> topological(
            Collection<String> nodes, Function<String, Collection<String>> before) {
        Map<String, Integer> missing = new HashMap<>();
        Map<String, List<String>> after = new HashMap<>();
        for (String n : nodes) {
            int count = 0;
            for (String p : before.apply(n)) {
                if (nodes.contains(p) && !p.equals(n)) {
                    count++;
                    after.computeIfAbsent(p, k -> new ArrayList<>()).add(n);
                }
            }
            missing.put(n, count);
        }
        TreeSet<String> ready = new TreeSet<>();
        missing.forEach(
                (n, c) -> {
                    if (c == 0) {
                        ready.add(n);
                    }
                });
        List<String> out = new ArrayList<>();
        while (!ready.isEmpty()) {
            String n = ready.pollFirst();
            out.add(n);
            for (String m : after.getOrDefault(n, List.of())) {
                if (missing.merge(m, -1, Integer::sum) == 0) {
                    ready.add(m);
                }
            }
        }
        return out.size() == nodes.size() ? out : null;
    }

    /** Tarjan's strongly connected components of the child-to-parent graph. */
    static List<Set<String>> stronglyConnected(Set<String> nodes, List<Constraint> edges) {
        Map<String, List<String>> next = new TreeMap<>();
        for (Constraint e : edges) {
            next.computeIfAbsent(e.table(), k -> new ArrayList<>()).add(e.refTable());
        }
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new java.util.HashSet<>();
        List<Set<String>> out = new ArrayList<>();
        int[] counter = {0};
        for (String n : nodes) {
            if (!index.containsKey(n)) {
                strongConnect(n, next, index, low, stack, onStack, out, counter);
            }
        }
        return out;
    }

    private static void strongConnect(
            String v,
            Map<String, List<String>> next,
            Map<String, Integer> index,
            Map<String, Integer> low,
            Deque<String> stack,
            Set<String> onStack,
            List<Set<String>> out,
            int[] counter) {
        // Iterative form of Tarjan's algorithm: 300 tables would not overflow, but a deep chain
        // in a later schema might.
        record Frame(String node, java.util.Iterator<String> children) {}
        Deque<Frame> work = new ArrayDeque<>();
        index.put(v, counter[0]);
        low.put(v, counter[0]++);
        stack.push(v);
        onStack.add(v);
        work.push(new Frame(v, next.getOrDefault(v, List.of()).iterator()));
        while (!work.isEmpty()) {
            Frame f = work.peek();
            if (f.children().hasNext()) {
                String w = f.children().next();
                if (!index.containsKey(w)) {
                    index.put(w, counter[0]);
                    low.put(w, counter[0]++);
                    stack.push(w);
                    onStack.add(w);
                    work.push(new Frame(w, next.getOrDefault(w, List.of()).iterator()));
                } else if (onStack.contains(w)) {
                    low.put(f.node(), Math.min(low.get(f.node()), index.get(w)));
                }
            } else {
                work.pop();
                if (!work.isEmpty()) {
                    String parent = work.peek().node();
                    low.put(parent, Math.min(low.get(parent), low.get(f.node())));
                }
                if (low.get(f.node()).equals(index.get(f.node()))) {
                    Set<String> scc = new TreeSet<>();
                    String w;
                    do {
                        w = stack.pop();
                        onStack.remove(w);
                        scc.add(w);
                    } while (!w.equals(f.node()));
                    out.add(scc);
                }
            }
        }
    }
}
