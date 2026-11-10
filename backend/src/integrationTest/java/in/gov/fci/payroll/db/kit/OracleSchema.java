package in.gov.fci.payroll.db.kit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OD-160 (owner 2026-11-09, "for Oracle script add schema PAYROLL for every DDL/DML"): every object
 * name of the Oracle install kit is written with the fixed schema {@value #SCHEMA}, so the kit runs
 * from any login that may create objects in it and always lands in PAYROLL.
 *
 * <p>The kit text is generated as before and then qualified here as the last step, so the order,
 * data and validators are untouched: {@link #qualify} only inserts {@code PAYROLL.} in front of a
 * name, and proves it (removing every {@code PAYROLL.} gives the input back exactly). A name is
 * qualified where its position says it is an object, and only if the kit creates that object (a
 * column, alias, DUAL or a dictionary view never is):
 *
 * <ul>
 *   <li>CREATE [UNIQUE] TABLE | VIEW | SEQUENCE | INDEX | TRIGGER name, and the table after ON of
 *       an index or trigger; ALTER | DROP TABLE | VIEW | SEQUENCE | INDEX | TRIGGER name; COMMENT
 *       ON TABLE name / COLUMN name.column;
 *   <li>INSERT INTO, UPDATE, DELETE [FROM]; FROM / JOIN / REFERENCES name; name.NEXTVAL / CURRVAL;
 *   <li>the same inside PL/SQL: the data blocks, the guards, trigger bodies (statement by
 *       statement) and the dynamic SQL of {@code EXECUTE IMMEDIATE 'DROP TABLE "X" ...'} (a string
 *       literal that is a complete statement or its head).
 * </ul>
 *
 * The dictionary lookups (ALL_* views filtered on the owner) and the dynamic constraint toggles are
 * written with the schema by {@link KitWriter} itself. A port of db/tools/oracle/qualify_schema.py,
 * extended to PL/SQL bodies; the H2 kit is not qualified (the H2 test databases have no PAYROLL
 * schema). INSTALL_KIT.md "Schema PAYROLL".
 */
final class OracleSchema {

    /** The fixed schema of the Oracle kit (OD-160: a fixed name, not a variable). */
    static final String SCHEMA = "PAYROLL";

    private static final String PREFIX = SCHEMA + ".";

    private static final Pattern TOKEN =
            Pattern.compile(
                    "(?<ws>\\s+)|(?<lc>--[^\\n]*)|(?<bc>/\\*.*?\\*/)|(?<str>'(?:[^']|'')*')"
                            + "|(?<qid>\"[^\"]*\")|(?<word>[A-Za-z_][A-Za-z0-9_$#]*)"
                            + "|(?<num>[0-9]+(?:\\.[0-9]+)?)|(?<p>.)",
                    Pattern.DOTALL);
    private static final Pattern UPPER_QID = Pattern.compile("^\"[A-Z][A-Z0-9_$#]*\"$");
    private static final Set<String> SQLPLUS =
            new HashSet<>(
                    Set.of(
                            "PROMPT",
                            "SET",
                            "SPOOL",
                            "WHENEVER",
                            "EXIT",
                            "ACCEPT",
                            "DEFINE",
                            "UNDEFINE",
                            "REM",
                            "CONNECT"));
    private static final Set<String> PLSQL_OBJECTS =
            new HashSet<>(Set.of("TRIGGER", "FUNCTION", "PROCEDURE", "PACKAGE", "TYPE"));
    private static final Set<String> CREATE_WORDS =
            new HashSet<>(
                    Set.of(
                            "OR",
                            "REPLACE",
                            "EDITIONABLE",
                            "NONEDITIONABLE",
                            "FORCE",
                            "NOFORCE",
                            "GLOBAL",
                            "TEMPORARY",
                            "PRIVATE",
                            "UNIQUE",
                            "BITMAP"));
    private static final Set<String> DEFINING =
            new HashSet<>(Set.of("TABLE", "VIEW", "SEQUENCE", "INDEX", "TRIGGER"));
    private static final Set<String> CLAUSE =
            new HashSet<>(
                    Set.of(
                            "WHERE",
                            "GROUP",
                            "ORDER",
                            "HAVING",
                            "UNION",
                            "INTERSECT",
                            "MINUS",
                            "JOIN",
                            "INNER",
                            "LEFT",
                            "RIGHT",
                            "FULL",
                            "CROSS",
                            "NATURAL",
                            "ON",
                            "USING",
                            "CONNECT",
                            "START",
                            "FOR",
                            "FETCH",
                            "OFFSET",
                            "WINDOW",
                            "SET",
                            "VALUES",
                            "WHEN",
                            "THEN",
                            "ELSE",
                            "END",
                            "AND",
                            "OR",
                            "LOOP",
                            "SELECT",
                            "PIVOT",
                            "UNPIVOT",
                            "MODEL",
                            "INTO"));

    /** Words that open a PL/SQL statement's block position; skipped before the statement's verb. */
    private static final Set<String> BLOCK_WORDS =
            new HashSet<>(
                    Set.of("BEGIN", "DECLARE", "THEN", "ELSE", "LOOP", "EXCEPTION", "IS", "AS"));

    /** A string literal whose text is a statement (or its head): dynamic SQL. */
    private static final Pattern DYNAMIC =
            Pattern.compile(
                    "^\\s*(CREATE|ALTER|DROP|COMMENT|INSERT|UPDATE|DELETE|TRUNCATE|SELECT)\\s",
                    Pattern.CASE_INSENSITIVE);

    private OracleSchema() {}

    private record Tok(String kind, String text) {}

    /** The names a script creates: (tables, views and sequences; plus indexes and triggers). */
    record Known(Set<String> objects, Set<String> everything) {
        Known plus(Known o) {
            Set<String> a = new HashSet<>(objects);
            a.addAll(o.objects);
            Set<String> b = new HashSet<>(everything);
            b.addAll(o.everything);
            return new Known(a, b);
        }
    }

    private record Unit(int start, int end, String kind) {}

    private static List<Tok> tokenize(String text) {
        List<Tok> toks = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        int pos = 0;
        while (m.find()) {
            if (m.start() != pos) {
                throw new IllegalStateException("the tokenizer lost text at " + pos);
            }
            String kind = null;
            for (String g : List.of("ws", "lc", "bc", "str", "qid", "word", "num", "p")) {
                if (m.group(g) != null) {
                    kind = g;
                    break;
                }
            }
            toks.add(new Tok(kind, m.group()));
            pos = m.end();
        }
        if (pos != text.length()) {
            throw new IllegalStateException("the tokenizer lost the end of the text");
        }
        return toks;
    }

    private static boolean trivia(Tok t) {
        return t.kind.equals("ws") || t.kind.equals("lc") || t.kind.equals("bc");
    }

    private static String upperWord(Tok t) {
        return t.kind.equals("word") ? t.text.toUpperCase(Locale.ROOT) : "";
    }

    private static boolean slashLine(List<Tok> toks, int i) {
        if (!toks.get(i).kind.equals("p") || !toks.get(i).text.equals("/")) {
            return false;
        }
        if (i > 0 && !(toks.get(i - 1).kind.equals("ws") && toks.get(i - 1).text.endsWith("\n"))) {
            return false;
        }
        return i + 1 >= toks.size()
                || toks.get(i + 1).kind.equals("ws")
                        && toks.get(i + 1).text.matches("(?s)[ \\t]*\\r?\\n.*");
    }

    private static String createKind(List<Tok> toks, int i) {
        for (int j = i + 1; j < toks.size(); j++) {
            Tok t = toks.get(j);
            if (trivia(t) || CREATE_WORDS.contains(upperWord(t))) {
                continue;
            }
            return t.kind.equals("word") ? upperWord(t) : null;
        }
        return null;
    }

    private static List<Unit> units(List<Tok> toks) {
        List<Unit> out = new ArrayList<>();
        int i = 0;
        int n = toks.size();
        while (i < n) {
            if (trivia(toks.get(i))) {
                i++;
                continue;
            }
            int start = i;
            String first = upperWord(toks.get(i));
            if (SQLPLUS.contains(first)) {
                while (i < n
                        && !(toks.get(i).kind.equals("ws") && toks.get(i).text.contains("\n"))) {
                    i++;
                }
                out.add(new Unit(start, i, "cmd"));
                continue;
            }
            if (first.isEmpty() && toks.get(i).text.equals("@")) {
                while (i < n
                        && !(toks.get(i).kind.equals("ws") && toks.get(i).text.contains("\n"))) {
                    i++;
                }
                out.add(new Unit(start, i, "cmd"));
                continue;
            }
            String create = first.equals("CREATE") ? createKind(toks, i) : null;
            if (first.equals("DECLARE")
                    || first.equals("BEGIN")
                    || PLSQL_OBJECTS.contains(create)) {
                while (i < n && !slashLine(toks, i)) {
                    i++;
                }
                if (i >= n) {
                    throw new IllegalStateException("a PL/SQL unit without its / terminator");
                }
                i++;
                out.add(new Unit(start, i, "TRIGGER".equals(create) ? "trigger" : "plsql"));
                continue;
            }
            int depth = 0;
            while (i < n) {
                Tok t = toks.get(i++);
                if (t.kind.equals("p")) {
                    if (t.text.equals("(")) {
                        depth++;
                    } else if (t.text.equals(")")) {
                        depth--;
                    } else if (t.text.equals(";") && depth == 0) {
                        break;
                    }
                }
            }
            out.add(new Unit(start, i, "sql"));
        }
        return out;
    }

    /** The significant tokens of one statement, as positions into the token list. */
    private static final class View {
        final List<Tok> toks;
        final List<Integer> sig;

        View(List<Tok> toks, List<Integer> sig) {
            this.toks = toks;
            this.sig = sig;
        }

        int n() {
            return sig.size();
        }

        String word(int k) {
            return k >= 0 && k < sig.size() && toks.get(sig.get(k)).kind.equals("word")
                    ? toks.get(sig.get(k)).text.toUpperCase(Locale.ROOT)
                    : null;
        }

        String punct(int k) {
            return k >= 0 && k < sig.size() && toks.get(sig.get(k)).kind.equals("p")
                    ? toks.get(sig.get(k)).text
                    : null;
        }

        boolean isName(int k) {
            if (k < 0 || k >= sig.size()) {
                return false;
            }
            String kind = toks.get(sig.get(k)).kind;
            return kind.equals("word") || kind.equals("qid");
        }

        String key(int k) {
            Tok t = toks.get(sig.get(k));
            if (t.kind.equals("word")) {
                return t.text.toLowerCase(Locale.ROOT);
            }
            // "APP_USER" (quoted upper case) is the same object as app_user.
            return UPPER_QID.matcher(t.text).matches()
                    ? t.text.substring(1, t.text.length() - 1).toLowerCase(Locale.ROOT)
                    : t.text;
        }
    }

    /**
     * The statements of a unit: one for SQL; for PL/SQL each statement of the body, split at ";".
     */
    private static List<List<Integer>> statements(List<Tok> toks, Unit u) {
        List<List<Integer>> out = new ArrayList<>();
        if (u.kind.equals("sql")) {
            List<Integer> sig = new ArrayList<>();
            for (int k = u.start; k < u.end; k++) {
                if (!trivia(toks.get(k))) {
                    sig.add(k);
                }
            }
            out.add(sig);
            return out;
        }
        List<Integer> current = new ArrayList<>();
        int depth = 0;
        boolean header = u.kind.equals("trigger");
        for (int k = u.start; k < u.end; k++) {
            Tok t = toks.get(k);
            if (trivia(t)) {
                continue;
            }
            if (header && (upperWord(t).equals("BEGIN") || upperWord(t).equals("DECLARE"))) {
                // the trigger header (CREATE TRIGGER ... ON table) is one statement of its own
                out.add(current);
                current = new ArrayList<>();
                header = false;
            }
            if (current.isEmpty() && BLOCK_WORDS.contains(upperWord(t))) {
                continue;
            }
            current.add(k);
            if (t.kind.equals("p")) {
                if (t.text.equals("(")) {
                    depth++;
                } else if (t.text.equals(")")) {
                    depth--;
                } else if (t.text.equals(";") && depth == 0) {
                    out.add(current);
                    current = new ArrayList<>();
                }
            }
        }
        if (!current.isEmpty()) {
            out.add(current);
        }
        return out;
    }

    private static String[] defined(View v) {
        if (!"CREATE".equals(v.word(0))) {
            return null;
        }
        int j = 1;
        while (CREATE_WORDS.contains(v.word(j))) {
            j++;
        }
        String typ = v.word(j);
        if (typ != null && DEFINING.contains(typ) && v.isName(j + 1)) {
            return new String[] {typ, v.key(j + 1)};
        }
        return null;
    }

    /** The names a script creates (CREATE statements and dynamic CREATE SEQUENCE). */
    static Known known(String text) {
        List<Tok> toks = tokenize(text);
        Set<String> objects = new HashSet<>();
        Set<String> everything = new HashSet<>();
        for (Unit u : units(toks)) {
            if (u.kind.equals("cmd") || u.kind.equals("plsql")) {
                continue;
            }
            String[] d = defined(new View(toks, statements(toks, u).get(0)));
            if (d != null) {
                everything.add(d[1]);
                if (!d[0].equals("INDEX") && !d[0].equals("TRIGGER")) {
                    objects.add(d[1]);
                }
            }
        }
        Matcher m =
                Pattern.compile(
                                "CREATE\\s+SEQUENCE\\s+\"?([A-Za-z_][A-Za-z0-9_$#]*)",
                                Pattern.CASE_INSENSITIVE)
                        .matcher(text);
        while (m.find()) {
            objects.add(m.group(1).toLowerCase(Locale.ROOT));
            everything.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return new Known(objects, everything);
    }

    /** {token index: rule} of the names to qualify in one statement. */
    private static Map<Integer, String> rules(View v, Known known) {
        Map<Integer, String> found = new HashMap<>();
        Set<String> objects = known.objects();
        Set<String> everything = known.everything();
        String first = v.word(0);
        String second = v.word(1);
        if ("CREATE".equals(first)) {
            int j = 1;
            while (CREATE_WORDS.contains(v.word(j))) {
                j++;
            }
            String typ = v.word(j);
            if (typ != null && DEFINING.contains(typ)) {
                mark(v, found, j + 1, "create " + typ.toLowerCase(Locale.ROOT), everything, false);
                if (typ.equals("INDEX") && "ON".equals(v.word(j + 2))) {
                    mark(v, found, j + 3, "index on table", objects, false);
                }
                if (typ.equals("TRIGGER")) {
                    for (int k = j + 2; k < v.n(); k++) {
                        if ("ON".equals(v.word(k))) {
                            mark(v, found, k + 1, "trigger on table", objects, false);
                            break;
                        }
                    }
                }
            }
        } else if (("ALTER".equals(first) || "DROP".equals(first))
                && second != null
                && DEFINING.contains(second)) {
            mark(
                    v,
                    found,
                    2,
                    first.toLowerCase(Locale.ROOT) + " " + second.toLowerCase(Locale.ROOT),
                    everything,
                    false);
        } else if ("COMMENT".equals(first) && "ON".equals(second)) {
            if ("TABLE".equals(v.word(2))) {
                mark(v, found, 3, "comment on table", objects, false);
            } else if ("COLUMN".equals(v.word(2)) && ".".equals(v.punct(4))) {
                mark(v, found, 3, "comment on column", objects, true);
            }
        } else if ("INSERT".equals(first)) {
            for (int k = 1; k < v.n(); k++) {
                if ("INTO".equals(v.word(k))) {
                    mark(v, found, k + 1, "insert into", objects, false);
                    break;
                }
            }
        } else if ("UPDATE".equals(first)) {
            mark(v, found, 1, "update", objects, false);
        } else if ("DELETE".equals(first)) {
            mark(v, found, "FROM".equals(second) ? 2 : 1, "delete from", objects, false);
        } else if ("TRUNCATE".equals(first) && "TABLE".equals(second)) {
            mark(v, found, 2, "truncate table", objects, false);
        }
        List<String> parens = new ArrayList<>();
        for (int k = 0; k < v.n(); k++) {
            String p = v.punct(k);
            String w = v.word(k);
            if ("(".equals(p)) {
                parens.add(v.word(k - 1));
            } else if (")".equals(p) && !parens.isEmpty()) {
                parens.remove(parens.size() - 1);
            } else if ("REFERENCES".equals(w)) {
                mark(v, found, k + 1, "references", objects, false);
            } else if ("JOIN".equals(w)) {
                mark(v, found, k + 1, "join", objects, false);
            } else if ("FROM".equals(w)
                    && !(!parens.isEmpty()
                            && ("EXTRACT".equals(parens.get(parens.size() - 1))
                                    || "TRIM".equals(parens.get(parens.size() - 1))))) {
                fromList(v, found, k, objects);
            } else if (v.isName(k)
                    && ".".equals(v.punct(k + 1))
                    && ("NEXTVAL".equals(v.word(k + 2)) || "CURRVAL".equals(v.word(k + 2)))) {
                mark(v, found, k, "sequence value", objects, true);
            }
        }
        return found;
    }

    private static void mark(
            View v,
            Map<Integer, String> found,
            int k,
            String rule,
            Set<String> names,
            boolean dot) {
        if (!v.isName(k) || ".".equals(v.punct(k - 1))) {
            return;
        }
        if (".".equals(v.punct(k + 1)) && !dot) {
            return;
        }
        if (names.contains(v.key(k))) {
            found.putIfAbsent(v.sig.get(k), rule);
        }
    }

    private static void fromList(View v, Map<Integer, String> found, int k, Set<String> objects) {
        int p = k + 1;
        while (v.isName(p)) {
            mark(v, found, p, "from", objects, false);
            int q = p + 1;
            if (".".equals(v.punct(q))) {
                q += 2;
            }
            if ("AS".equals(v.word(q))) {
                q++;
            }
            if (v.isName(q) && !CLAUSE.contains(v.word(q))) {
                q++;
            }
            if (!",".equals(v.punct(q))) {
                return;
            }
            p = q + 1;
        }
    }

    /**
     * The script with its object names written {@value #SCHEMA}.name, and the count per rule.
     *
     * @param known the names the kit's DDL creates (02_ddl), added to the script's own
     */
    static Result qualify(String script, Known known) {
        List<String> prompts = new ArrayList<>();
        String text = mask(script, prompts);
        List<Tok> toks = tokenize(text);
        Known all = known(text).plus(known);
        Map<Integer, String> marked = new HashMap<>();
        Map<Integer, String> literals = new HashMap<>();
        Map<String, Integer> counts = new TreeMap<>();
        for (Unit u : units(toks)) {
            if (u.kind.equals("cmd")) {
                continue;
            }
            for (List<Integer> st : statements(toks, u)) {
                marked.putAll(rules(new View(toks, st), all));
            }
            if (!u.kind.equals("sql")) {
                // dynamic SQL in a string literal: EXECUTE IMMEDIATE 'DROP TABLE "X" ...'
                for (int k = u.start; k < u.end; k++) {
                    Tok t = toks.get(k);
                    if (t.kind.equals("str")) {
                        String body = t.text.substring(1, t.text.length() - 1).replace("''", "'");
                        if (DYNAMIC.matcher(body).find()) {
                            Result inner = qualifyStatement(body, all);
                            if (!inner.text.equals(body)) {
                                literals.put(k, "'" + inner.text.replace("'", "''") + "'");
                                inner.counts.forEach(
                                        (r, c) -> counts.merge("dynamic " + r, c, Integer::sum));
                            }
                        }
                    }
                }
            }
        }
        marked.values().forEach(r -> counts.merge(r, 1, Integer::sum));
        StringBuilder out = new StringBuilder(text.length() + 8 * marked.size());
        for (int i = 0; i < toks.size(); i++) {
            if (literals.containsKey(i)) {
                out.append(literals.get(i));
            } else {
                if (marked.containsKey(i)) {
                    out.append(PREFIX);
                }
                out.append(toks.get(i).text);
            }
        }
        String result = unmask(out.toString(), prompts);
        // Only "PAYROLL." was added: removing it gives the input back (KitWriter itself writes the
        // schema into the dynamic constraint toggles, so both sides are compared without it).
        if (!result.replace(PREFIX, "").equals(script.replace(PREFIX, ""))) {
            throw new IllegalStateException(
                    "removing "
                            + PREFIX
                            + " does not give back the input: more than the schema changed");
        }
        return new Result(result, counts);
    }

    /**
     * PROMPT lines are SQL*Plus text, not SQL: one holding a quote would mislead the tokenizer, so
     * each is replaced by a numbered placeholder and put back afterwards.
     */
    private static String mask(String text, List<String> saved) {
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            if (line.toUpperCase(Locale.ROOT).startsWith("PROMPT")) {
                out.append("PROMPT KITLINE").append(saved.size());
                saved.add(line);
            } else {
                out.append(line);
            }
        }
        return out.toString();
    }

    private static String unmask(String text, List<String> saved) {
        Matcher m = Pattern.compile("(?m)^PROMPT KITLINE(\\d+)$").matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            m.appendReplacement(
                    out, Matcher.quoteReplacement(saved.get(Integer.parseInt(m.group(1)))));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** One statement (the text of a dynamic SQL literal), qualified with the same rules. */
    private static Result qualifyStatement(String body, Known known) {
        List<Tok> toks = tokenize(body);
        List<Integer> sig = new ArrayList<>();
        for (int k = 0; k < toks.size(); k++) {
            if (!trivia(toks.get(k))) {
                sig.add(k);
            }
        }
        Map<Integer, String> marked = rules(new View(toks, sig), known);
        Map<String, Integer> counts = new TreeMap<>();
        marked.values().forEach(r -> counts.merge(r, 1, Integer::sum));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < toks.size(); i++) {
            if (marked.containsKey(i)) {
                out.append(PREFIX);
            }
            out.append(toks.get(i).text);
        }
        return new Result(out.toString(), counts);
    }

    /** A qualified script and how many names each rule qualified. */
    record Result(String text, Map<String, Integer> counts) {}

    private static final Pattern LOGIN_USER_VIEWS =
            Pattern.compile(
                    "\\buser_(objects|constraints|tables|indexes|ind_columns|cons_columns|tab_columns"
                        + "|tab_cols|sequences|triggers|errors|source|views|synonyms|identity_cols|dependencies)\\b|COMPILE_SCHEMA\\(schema"
                        + " => USER",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Refuses a qualified script that still reads the login's own dictionary or leaves a name the
     * kit creates unqualified in an object position (outside comments and PROMPT lines).
     */
    static void audit(String file, String text, Known known) {
        for (String line : text.split("\n")) {
            String l = line.strip();
            if (l.startsWith("--") || l.toUpperCase(Locale.ROOT).startsWith("PROMPT")) {
                continue;
            }
            if (LOGIN_USER_VIEWS.matcher(line).find()) {
                throw new IllegalStateException(
                        file + " reads the login's own dictionary (OD-160): " + l);
            }
        }
        List<Tok> toks = tokenize(mask(text, new ArrayList<>()));
        Known all = known(mask(text.replace(PREFIX, ""), new ArrayList<>())).plus(known);
        for (Unit u : units(toks)) {
            if (u.kind.equals("cmd")) {
                continue;
            }
            if (!u.kind.equals("sql")) {
                for (int k = u.start; k < u.end; k++) {
                    Tok t = toks.get(k);
                    if (t.kind.equals("str")) {
                        String body = t.text.substring(1, t.text.length() - 1).replace("''", "'");
                        if (DYNAMIC.matcher(body).find()
                                && !qualifyStatement(body, all).text().equals(body)) {
                            throw new IllegalStateException(
                                    file + ": dynamic SQL without " + PREFIX + ": " + body);
                        }
                    }
                }
            }
            for (List<Integer> st : statements(toks, u)) {
                Map<Integer, String> left = rules(new View(toks, st), all);
                if (!left.isEmpty()) {
                    int at = left.keySet().iterator().next();
                    throw new IllegalStateException(
                            file
                                    + ": an object name without "
                                    + PREFIX
                                    + " ("
                                    + left.get(at)
                                    + "): "
                                    + toks.get(at).text);
                }
            }
        }
    }
}
