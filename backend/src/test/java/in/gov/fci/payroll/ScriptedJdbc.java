package in.gov.fci.payroll;

import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.mockito.Answers;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * A {@link JdbcTemplate} / {@link NamedParameterJdbcTemplate} double for unit tests of SQL-heavy
 * orchestration: each call is answered by the latest rule whose SQL fragment the statement contains
 * (a later rule overrides an earlier one), so a test scripts only the queries its branch depends
 * on. Rows are column maps; a {@link RowMapper}, {@link RowCallbackHandler} or {@link
 * ResultSetExtractor} reads them through a {@link ResultSet} by position (in the map's order) or by
 * label. Unscripted: a count answers 0, a list is empty, other single values are null, and {@code
 * queryForMap} fails as an empty result would. Every update is recorded and answers 1.
 */
public final class ScriptedJdbc {

    /** One executed update: the statement and its arguments. */
    public record Update(String sql, List<Object> args) {}

    private record Rule(String fragment, List<Map<String, Object>> rows, Object value) {}

    private final List<Rule> rules = new ArrayList<>();
    private final List<Update> updates = new ArrayList<>();
    private final List<String> queries = new ArrayList<>();
    private long nextKey = 1000L;

    /** A value() answer of SQL NULL (an aggregate over no rows), where unscripted would be 0. */
    public static final Object NULL = new Object();

    /** The JdbcTemplate double. */
    public final JdbcTemplate jdbc = mock(JdbcTemplate.class, this::answer);

    /** The NamedParameterJdbcTemplate double, scripted by the same rules. */
    public final NamedParameterJdbcTemplate named =
            mock(NamedParameterJdbcTemplate.class, this::answer);

    /** A row: alternating column labels and values, in column order. */
    public static Map<String, Object> row(Object... labelsAndValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < labelsAndValues.length; i += 2) {
            row.put(((String) labelsAndValues[i]).toUpperCase(Locale.ROOT), labelsAndValues[i + 1]);
        }
        return row;
    }

    /** A row read by position only: the values in column order. */
    public static Map<String, Object> cols(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            row.put("C" + (i + 1), values[i]);
        }
        return row;
    }

    /** Statements containing {@code fragment} return {@code rows}. */
    @SafeVarargs
    public final ScriptedJdbc rows(String fragment, Map<String, Object>... rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            list.add(r);
        }
        rules.add(new Rule(fragment, list, null));
        return this;
    }

    /** Statements containing {@code fragment} answer {@code value} to queryForObject / update. */
    public ScriptedJdbc value(String fragment, Object value) {
        rules.add(new Rule(fragment, List.of(), value));
        return this;
    }

    /** The queries executed (anything but an update) whose statement contains {@code fragment}. */
    public List<String> queries(String fragment) {
        return queries.stream().filter(q -> q.contains(fragment)).toList();
    }

    /** The updates executed whose statement contains {@code fragment}. */
    public List<Update> updates(String fragment) {
        return updates.stream().filter(u -> u.sql().contains(fragment)).toList();
    }

    private Rule rule(String sql) {
        for (int i = rules.size() - 1; i >= 0; i--) {
            if (sql.contains(rules.get(i).fragment())) {
                return rules.get(i);
            }
        }
        return null;
    }

    private Object answer(InvocationOnMock call) throws Exception {
        Object[] args = call.getArguments();
        if (args.length > 0 && args[0] instanceof PreparedStatementCreator creator) {
            return created(creator, args.length > 1 ? args[1] : null);
        }
        if (args.length == 0 || !(args[0] instanceof String sql)) {
            return null;
        }
        Rule rule = rule(sql);
        List<Map<String, Object>> rows = rule == null ? List.of() : rule.rows();
        Object value = rule == null ? null : rule.value();
        String method = call.getMethod().getName();
        if (!method.equals("update")) {
            queries.add(sql);
        }
        Object handler = handler(args);
        return switch (method) {
            case "update" -> {
                // Arguments may be null (an absent value bound as NULL).
                updates.add(
                        new Update(sql, Arrays.asList(Arrays.copyOfRange(args, 1, args.length))));
                yield value == null ? 1 : value;
            }
            case "queryForList" ->
                    handler instanceof Class<?>
                            ? rows.stream().map(r -> r.values().iterator().next()).toList()
                            : rows;
            case "queryForMap" -> {
                if (rows.isEmpty()) {
                    throw new EmptyResultDataAccessException(1);
                }
                yield rows.get(0);
            }
            case "queryForObject" -> single(handler, rows, value);
            case "query" -> query(handler, rows);
            default -> null;
        };
    }

    /**
     * An update through a {@link PreparedStatementCreator}: the creator runs against a recording
     * statement, the statement and its bound values (by index; setNull binds null) are recorded,
     * and a {@link GeneratedKeyHolder} receives the next generated key (1000, 1001, ...).
     */
    private Object created(PreparedStatementCreator creator, Object keys) throws Exception {
        String[] sql = {null};
        Map<Integer, Object> bound = new TreeMap<>();
        // A null argument is bound through setNull, which first asks the statement for its
        // connection's metadata: the recording statement answers its own connection.
        Connection[] owner = {null};
        java.sql.DatabaseMetaData metadata =
                mock(
                        java.sql.DatabaseMetaData.class,
                        call ->
                                call.getMethod().getReturnType() == String.class
                                        ? "ScriptedJdbc"
                                        : Answers.RETURNS_DEFAULTS.answer(call));
        PreparedStatement ps =
                mock(
                        PreparedStatement.class,
                        call -> {
                            String m = call.getMethod().getName();
                            Object[] a = call.getArguments();
                            if (m.startsWith("set") && a.length >= 2 && a[0] instanceof Integer i) {
                                bound.put(i, m.equals("setNull") ? null : a[1]);
                                return null;
                            }
                            if (m.equals("getConnection")) {
                                return owner[0];
                            }
                            if (m.equals("getParameterMetaData")) {
                                return mock(java.sql.ParameterMetaData.class);
                            }
                            return Answers.RETURNS_DEFAULTS.answer(call);
                        });
        Connection connection =
                mock(
                        Connection.class,
                        call -> {
                            String m = call.getMethod().getName();
                            if (m.equals("prepareStatement")) {
                                sql[0] = call.getArgument(0);
                                return ps;
                            }
                            if (m.equals("getMetaData")) {
                                return metadata;
                            }
                            return Answers.RETURNS_DEFAULTS.answer(call);
                        });
        owner[0] = connection;
        creator.createPreparedStatement(connection);
        updates.add(new Update(sql[0], new ArrayList<>(bound.values())));
        if (keys instanceof GeneratedKeyHolder holder) {
            holder.getKeyList().add(Map.of("ID", nextKey++));
        }
        return 1;
    }

    private static Object handler(Object[] args) {
        for (int i = 1; i < args.length; i++) {
            if (args[i] instanceof RowMapper<?>
                    || args[i] instanceof RowCallbackHandler
                    || args[i] instanceof ResultSetExtractor<?>
                    || args[i] instanceof Class<?>) {
                return args[i];
            }
        }
        return null;
    }

    private static Object single(Object handler, List<Map<String, Object>> rows, Object value)
            throws Exception {
        if (handler instanceof RowMapper<?> mapper) {
            if (rows.isEmpty()) {
                throw new EmptyResultDataAccessException(1);
            }
            return mapper.mapRow(resultSet(List.of(rows.get(0)), 0), 0);
        }
        if (value == NULL) {
            return null;
        }
        if (value == null && !rows.isEmpty()) {
            value = rows.get(0).values().iterator().next();
        }
        if (value == null && handler instanceof Class<?> type) {
            if (type == Long.class) {
                return 0L;
            }
            if (type == Integer.class) {
                return 0;
            }
        }
        if (value instanceof Number n && handler == Long.class) {
            return n.longValue();
        }
        if (value instanceof Number n && handler == Integer.class) {
            return n.intValue();
        }
        return value;
    }

    private static Object query(Object handler, List<Map<String, Object>> rows) throws Exception {
        if (handler instanceof RowMapper<?> mapper) {
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                out.add(mapper.mapRow(resultSet(rows, i), i));
            }
            return out;
        }
        if (handler instanceof RowCallbackHandler callback) {
            for (int i = 0; i < rows.size(); i++) {
                callback.processRow(resultSet(rows, i));
            }
            return null;
        }
        if (handler instanceof ResultSetExtractor<?> extractor) {
            return extractor.extractData(resultSet(rows, -1));
        }
        return rows;
    }

    /**
     * A ResultSet over {@code rows} positioned at {@code at} (-1: before the first row, for an
     * extractor that calls next()).
     */
    static ResultSet resultSet(List<Map<String, Object>> rows, int at) {
        int[] cursor = {at};
        boolean[] wasNull = {false};
        return mock(
                ResultSet.class,
                call -> {
                    String m = call.getMethod().getName();
                    if (m.equals("next")) {
                        cursor[0]++;
                        return cursor[0] < rows.size();
                    }
                    if (m.equals("wasNull")) {
                        return wasNull[0];
                    }
                    if (call.getArguments().length == 0 || !m.startsWith("get")) {
                        return null;
                    }
                    Object key = call.getArgument(0);
                    Map<String, Object> row = rows.get(cursor[0]);
                    Object v =
                            key instanceof Integer i
                                    ? new ArrayList<>(row.values()).get(i - 1)
                                    : row.get(((String) key).toUpperCase(Locale.ROOT));
                    wasNull[0] = v == null;
                    return convert(m, v);
                });
    }

    private static Object convert(String getter, Object v) {
        return switch (getter) {
            case "getLong" -> v == null ? 0L : ((Number) v).longValue();
            case "getInt" -> v == null ? 0 : ((Number) v).intValue();
            case "getDouble" -> v == null ? 0d : ((Number) v).doubleValue();
            case "getBoolean" -> v != null && (Boolean) v;
            case "getString" -> v == null ? null : v.toString();
            case "getBigDecimal" ->
                    v == null || v instanceof BigDecimal ? v : new BigDecimal(v.toString());
            case "getDate" -> v instanceof java.time.LocalDate d ? Date.valueOf(d) : v;
            case "getTimestamp" -> v instanceof java.time.Instant t ? Timestamp.from(t) : v;
            default -> v;
        };
    }
}
