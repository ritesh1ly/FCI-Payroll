package in.gov.fci.payroll.platform.persistence;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.ScopeType;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * The office scope of a register query (SRS-G-2, R-RBAC data scope): {@code :all} is Y at
 * Headquarters (every office), {@code :locations} the session's offices otherwise. A session
 * without an office sees nothing. Used as {@code (:all = 'Y' OR x.location_id IN (:locations))}.
 *
 * <p>P1 (PERFORMANCE_GUIDE): an OR on a bind parameter keeps the database from using the office
 * index (every request read all 50,000 employees on the volume set: 40 ms a query on H2, a full
 * scan on Oracle too). {@link #indexable} rewrites the guard before the statement is prepared -
 * {@code 1 = 1} at Headquarters, {@code x.location_id IN (:locations)} elsewhere - and {@link
 * ScopedNamedParameterJdbcTemplate} applies it to every statement, so query authors keep writing
 * the guard.
 *
 * <p>Oracle review F1 (ORA-01795): a session holding a GLOBAL group sees every office wherever it
 * signed in (ScopeResolver#dataScope), so its guard becomes {@code x.location_id IN (SELECT
 * l.location_id FROM org_location l)} - the same rows as the list of every office, with no list to
 * bind - instead of an IN list of every location (about 2,000 in production, over the 1,000 Oracle
 * takes). Any other list over 1,000 values is split by {@link InLists}.
 */
public final class OfficeScope {

    /** Y when the session's data scope is every office because it holds a GLOBAL group. */
    public static final String EVERY_OFFICE = "everyOffice";

    /** The guard as written in the registers' SQL; any column, one IN list of the offices. */
    private static final Pattern GUARD =
            Pattern.compile(
                    "\\(:all = 'Y' OR"
                        + " ([A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*|[A-Za-z_][A-Za-z0-9_]*)"
                        + " IN \\(:locations\\)\\)");

    private OfficeScope() {}

    /**
     * The statement with each office guard resolved for its {@code :all} value: {@code 1 = 1} at
     * Headquarters, every office (one subquery, no list) for a GLOBAL group elsewhere, the office
     * IN list otherwise. A statement without the guard, or parameters without {@code :all}, is
     * returned unchanged; another use of {@code :all} (an EXISTS) stays.
     */
    public static String indexable(String sql, SqlParameterSource params) {
        if (params == null || !sql.contains(":all") || !params.hasValue("all")) {
            return sql;
        }
        boolean all = "Y".equals(params.getValue("all"));
        boolean everyOffice =
                params.hasValue(EVERY_OFFICE) && "Y".equals(params.getValue(EVERY_OFFICE));
        Matcher m = GUARD.matcher(sql);
        return m.replaceAll(
                r -> {
                    if (all) {
                        return "1 = 1";
                    }
                    String column = Matcher.quoteReplacement(r.group(1));
                    return everyOffice
                            ? column + " IN (SELECT l.location_id FROM org_location l)"
                            : column + " IN (:locations)";
                });
    }

    /**
     * The {@code :all} and {@code :locations} parameters, and {@link #EVERY_OFFICE} for a session
     * holding a GLOBAL group; refused without an office.
     */
    public static MapSqlParameterSource of(AccessContext context, boolean headquarters) {
        if (context.locationId() == null) {
            throw new FciException(CommonErrorCode.ACCESS_DENIED);
        }
        return new MapSqlParameterSource()
                .addValue("all", headquarters ? "Y" : "N")
                .addValue(EVERY_OFFICE, everyOffice(context) ? "Y" : "N")
                .addValue("locations", ids(context));
    }

    /**
     * The session's data scope is every office: it holds a GLOBAL group (ScopeResolver#dataScope
     * adds every location for one, wherever the session signed in).
     */
    public static boolean everyOffice(AccessContext context) {
        return context.groups().stream().anyMatch(g -> g.scopeType() == ScopeType.GLOBAL);
    }

    /** The session's offices; "in ()" is not valid SQL, so no office matches an impossible id. */
    public static Collection<Long> ids(AccessContext context) {
        return context.dataScope().isEmpty() ? List.of(-1L) : context.dataScope();
    }
}
