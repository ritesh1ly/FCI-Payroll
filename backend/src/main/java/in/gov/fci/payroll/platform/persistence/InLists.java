package in.gov.fci.payroll.platform.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Named IN lists that Oracle can take (ORA-01795: at most 1,000 expressions in one list; Oracle
 * review F1, docs/db/ORACLE_REVIEW.md). Spring expands {@code x IN (:ids)} into one bind per value
 * and never splits the list, so an office scope of a zone or of the whole Corporation, or a member
 * list, would be refused by Oracle 19c (26ai and H2 accept more, so neither the local database nor
 * the tests show it).
 *
 * <p>{@link #expand} rewrites each {@code <column> [NOT] IN (:name)} whose collection has more than
 * {@link #MAX} values into {@code (<column> IN (:name__0) OR <column> IN (:name__1) ...)} (NOT IN:
 * the chunks joined by AND), each chunk at most {@link #MAX} values and the last one padded to that
 * size with its own last value (same rows; a bounded number of statement texts). A list of {@link
 * #MAX} values or fewer is left as written. An oversized list used in any other form is refused
 * before the database sees it, rather than failing on Oracle only.
 *
 * <p>{@link ScopedNamedParameterJdbcTemplate} applies it to every statement of the application's
 * named template; a reader that builds its own template (the Spring Batch paging reader) calls
 * {@link #expand(String, Map)} itself. JPQL needs nothing: Hibernate splits an IN list at the
 * dialect's limit (1,000 for Oracle).
 */
public final class InLists {

    /** The most values Oracle takes in one IN list. */
    public static final int MAX = 1000;

    private static final String SUFFIX = "__";

    /** {@code column [NOT] IN (:name)}; the column may be qualified ({@code e.location_id}). */
    private static final Pattern IN_LIST =
            Pattern.compile(
                    "([A-Za-z_][A-Za-z0-9_$#]*(?:\\.[A-Za-z_][A-Za-z0-9_$#]*)?)\\s+(NOT\\s+)?IN\\s*"
                            + "\\(\\s*:([A-Za-z_][A-Za-z0-9_]*)\\s*\\)",
                    Pattern.CASE_INSENSITIVE);

    private InLists() {}

    /** A statement and its parameters with every oversized IN list split into chunks. */
    public record Expanded(String sql, SqlParameterSource params) {}

    /** The same for a parameter map (a reader that is given its parameters as a map). */
    public record ExpandedMap(String sql, Map<String, Object> params) {}

    /**
     * The statement with each IN list of more than {@link #MAX} values split; unchanged (the same
     * instances) when no list is oversized.
     *
     * @throws InvalidDataAccessApiUsageException if an oversized list is used other than as {@code
     *     column [NOT] IN (:name)}
     */
    public static Expanded expand(String sql, SqlParameterSource params) {
        if (params == null) {
            return new Expanded(sql, null);
        }
        Rewrite rewrite =
                rewrite(sql, name -> params.hasValue(name) ? params.getValue(name) : null);
        if (rewrite == null) {
            return new Expanded(sql, params);
        }
        return new Expanded(rewrite.sql(), new WithChunks(params, rewrite.chunks()));
    }

    /** {@link #expand(String, SqlParameterSource)} for a parameter map. */
    public static ExpandedMap expand(String sql, Map<String, ?> params) {
        Map<String, Object> copy = new LinkedHashMap<>(params);
        Rewrite rewrite = rewrite(sql, copy::get);
        if (rewrite == null) {
            return new ExpandedMap(sql, copy);
        }
        rewrite.chunks().forEach((name, chunk) -> copy.put(name, chunk.values()));
        return new ExpandedMap(rewrite.sql(), copy);
    }

    private record Chunk(String origin, List<Object> values) {}

    private record Rewrite(String sql, Map<String, Chunk> chunks) {}

    private static Rewrite rewrite(String sql, java.util.function.Function<String, Object> value) {
        if (sql == null || sql.indexOf(':') < 0) {
            return null;
        }
        Map<String, List<Object>> oversized = new LinkedHashMap<>();
        Set<String> small = new LinkedHashSet<>();
        Matcher m = IN_LIST.matcher(sql);
        StringBuilder out = new StringBuilder();
        Map<String, Chunk> chunks = new LinkedHashMap<>();
        while (m.find()) {
            String name = m.group(3);
            List<Object> values = oversized.get(name);
            if (values == null && small.add(name)) {
                values = oversizedValues(value.apply(name));
                if (values != null) {
                    small.remove(name);
                    oversized.put(name, values);
                }
            }
            if (values == null) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }
            boolean not = m.group(2) != null;
            m.appendReplacement(
                    out, Matcher.quoteReplacement(chunked(m.group(1), not, name, values, chunks)));
        }
        if (chunks.isEmpty()) {
            requireNoneOversized(sql, value, small);
            return null;
        }
        m.appendTail(out);
        String result = out.toString();
        for (Map.Entry<String, List<Object>> list : oversized.entrySet()) {
            if (Pattern.compile(":" + Pattern.quote(list.getKey()) + "(?![A-Za-z0-9_])")
                    .matcher(result)
                    .find()) {
                throw refused(list.getKey(), list.getValue().size());
            }
        }
        return new Rewrite(result, chunks);
    }

    /** {@code (col IN (:n__0) OR col IN (:n__1))}; NOT IN: the chunks joined by AND. */
    private static String chunked(
            String column, boolean not, String name, List<Object> values, Map<String, Chunk> out) {
        StringBuilder sql = new StringBuilder("(");
        int parts = (values.size() + MAX - 1) / MAX;
        for (int i = 0; i < parts; i++) {
            List<Object> part =
                    new ArrayList<>(
                            values.subList(i * MAX, Math.min(values.size(), (i + 1) * MAX)));
            // Padded with its own last value: the same rows, one statement text per chunk count.
            Object last = part.get(part.size() - 1);
            while (part.size() < MAX) {
                part.add(last);
            }
            String chunkName = name + SUFFIX + i;
            out.put(chunkName, new Chunk(name, part));
            if (i > 0) {
                sql.append(not ? " AND " : " OR ");
            }
            sql.append(column).append(not ? " NOT IN (:" : " IN (:").append(chunkName).append(')');
        }
        return sql.append(')').toString();
    }

    /** An oversized list that the statement uses outside {@code column IN (:name)} is refused. */
    private static void requireNoneOversized(
            String sql, java.util.function.Function<String, Object> value, Set<String> checked) {
        Matcher names = Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)").matcher(sql);
        while (names.find()) {
            String name = names.group(1);
            if (checked.add(name)) {
                List<Object> values = oversizedValues(value.apply(name));
                if (values != null) {
                    throw refused(name, values.size());
                }
            }
        }
    }

    private static List<Object> oversizedValues(Object value) {
        if (value instanceof Collection<?> c && c.size() > MAX) {
            return new ArrayList<>(c);
        }
        return null;
    }

    private static InvalidDataAccessApiUsageException refused(String name, int size) {
        return new InvalidDataAccessApiUsageException(
                "The list :"
                        + name
                        + " has "
                        + size
                        + " values, more than the "
                        + MAX
                        + " an Oracle IN list takes (ORA-01795), and is not used as"
                        + " 'column IN (:"
                        + name
                        + ")', so it cannot be split");
    }

    /** The caller's parameters plus the chunks; a chunk takes the SQL type of its list. */
    private static final class WithChunks implements SqlParameterSource {

        private final SqlParameterSource base;
        private final Map<String, Chunk> chunks;

        WithChunks(SqlParameterSource base, Map<String, Chunk> chunks) {
            this.base = base;
            this.chunks = chunks;
        }

        @Override
        public boolean hasValue(String paramName) {
            return chunks.containsKey(paramName) || base.hasValue(paramName);
        }

        @Override
        public Object getValue(String paramName) {
            Chunk chunk = chunks.get(paramName);
            return chunk != null ? chunk.values() : base.getValue(paramName);
        }

        @Override
        public int getSqlType(String paramName) {
            Chunk chunk = chunks.get(paramName);
            return base.getSqlType(chunk != null ? chunk.origin() : paramName);
        }

        @Override
        public String getTypeName(String paramName) {
            Chunk chunk = chunks.get(paramName);
            return base.getTypeName(chunk != null ? chunk.origin() : paramName);
        }

        @Override
        public String[] getParameterNames() {
            Set<String> names = new LinkedHashSet<>(chunks.keySet());
            String[] own = base.getParameterNames();
            if (own != null) {
                names.addAll(List.of(own));
            }
            return names.toArray(String[]::new);
        }
    }
}
