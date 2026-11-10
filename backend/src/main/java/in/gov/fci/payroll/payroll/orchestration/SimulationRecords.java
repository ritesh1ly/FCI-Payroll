package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.payroll.business.SimulationRules;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The run records of C-09 (plan 05 phase 05i) in {@code rule_simulation} (target DDL v4.0: "the
 * record of a simulation run is kept and its result rows are discarded"; results_retained 'N'). A
 * payroll what-if tests no rule version (rule_version_id NULL). The columns hold who, when, the
 * population (a readable key, {@link #scope}), its size and the input hash; impact_summary holds
 * the request and, once computed, its summary ({@link Doc}, JSON).
 *
 * <p>{@code population_scope} starts with {@code C09;<yymm>;<unit code>;}: the C-09 tiles and list
 * of a cycle select on that prefix, and rule-engine simulations (module 06) never match it.
 */
@Component
public class SimulationRecords {

    /** QUEUED (background, not started), RUNNING, COMPLETE or FAILED. */
    public enum Status {
        QUEUED,
        RUNNING,
        COMPLETE,
        FAILED
    }

    /**
     * The request and its outcome.
     *
     * @param employeeIds the members (null for the whole office)
     * @param done members read so far (background progress)
     */
    public record Doc(
            Status status,
            SimulationRules.Mode mode,
            String period,
            long unitId,
            String unitCode,
            SimulationRules.Population population,
            List<Long> employeeIds,
            SimulationRules.WhatIf whatIf,
            BigDecimal daIda,
            BigDecimal daCda,
            Long jobRequestId,
            long done,
            SimulationEngine.Side asIs,
            SimulationEngine.Side whatIfSide,
            String versionSetHash,
            String failure) {

        Doc with(Status s, long d, String why) {
            return new Doc(
                    s,
                    mode,
                    period,
                    unitId,
                    unitCode,
                    population,
                    employeeIds,
                    whatIf,
                    daIda,
                    daCda,
                    jobRequestId,
                    d,
                    asIs,
                    whatIfSide,
                    versionSetHash,
                    why);
        }

        /** RUNNING with the members read so far (background progress). */
        public Doc progressed(long read) {
            return with(Status.RUNNING, read, null);
        }

        Doc job(Long requestId) {
            return new Doc(
                    status,
                    mode,
                    period,
                    unitId,
                    unitCode,
                    population,
                    employeeIds,
                    whatIf,
                    daIda,
                    daCda,
                    requestId,
                    done,
                    asIs,
                    whatIfSide,
                    versionSetHash,
                    failure);
        }

        Doc outcome(SimulationEngine.Outcome o) {
            return new Doc(
                    Status.COMPLETE,
                    mode,
                    period,
                    unitId,
                    unitCode,
                    population,
                    employeeIds,
                    whatIf,
                    daIda,
                    daCda,
                    jobRequestId,
                    o.population(),
                    o.asIs(),
                    o.whatIf(),
                    o.versionSetHash(),
                    null);
        }

        /** The engine request this record describes. */
        public SimulationEngine.Request request() {
            return new SimulationEngine.Request(
                    unitId, java.time.YearMonth.parse(period), employeeIds, whatIf, daIda, daCda);
        }
    }

    /** One record as read. */
    public record Row(
            long id,
            String requestedBy,
            Instant requestedAt,
            String scope,
            long population,
            String inputHash,
            Doc doc) {}

    /** Recorded in the input hash column until a background run has computed one. */
    static final String NOT_YET = "PENDING";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public SimulationRecords(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** A login cut to the 50 of {@code rule_simulation.requested_by}. */
    static String truncate(String login) {
        return in.gov.fci.payroll.payroll.business.CycleKeys.truncate(login);
    }

    /** The key prefix of a cycle's C-09 records. */
    public static String prefix(String yymm, String unitCode) {
        return "C09;" + yymm + ";" + unitCode + ";";
    }

    /**
     * The readable population key: prefix, population, size, what-if (DA_RATE with its rates).
     * Within the 500 of {@code population_scope}.
     */
    static String scope(String yymm, Doc doc, long count) {
        String whatIf =
                doc.whatIf() == SimulationRules.WhatIf.DA_RATE
                        ? "DA_RATE;IDA=" + plain(doc.daIda()) + ";CDA=" + plain(doc.daCda())
                        : doc.whatIf().name();
        return prefix(yymm, doc.unitCode()) + doc.population() + ";" + count + ";" + whatIf;
    }

    private static String plain(BigDecimal rate) {
        return rate == null ? "-" : rate.stripTrailingZeros().toPlainString();
    }

    @Transactional
    public long insert(String login, String yymm, Doc doc, long count, String inputHash) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        String body = json.writeValueAsString(doc);
        jdbc.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    "INSERT INTO rule_simulation (requested_by, requested_at,"
                                        + " population_scope, population_count, input_hash,"
                                        + " impact_summary, results_retained) VALUES (?, ?, ?, ?,"
                                        + " ?, ?, 'N')",
                                    new String[] {"simulation_id"});
                    ps.setString(1, truncate(login));
                    // The app clock, like the other run records: never compared with the DB's.
                    ps.setTimestamp(2, Timestamp.from(clock.instant()));
                    ps.setString(3, scope(yymm, doc, count));
                    ps.setLong(4, count);
                    ps.setString(5, inputHash);
                    // A CLOB: the member list of a large selection exceeds a VARCHAR bind.
                    ps.setCharacterStream(6, new StringReader(body), body.length());
                    return ps;
                },
                key);
        return GeneratedKeys.id(key);
    }

    @Transactional(readOnly = true)
    public Optional<Row> find(long id) {
        return jdbc
                .query(
                        "SELECT simulation_id, requested_by, requested_at, population_scope,"
                                + " population_count, input_hash, impact_summary FROM"
                                + " rule_simulation WHERE simulation_id = ? AND population_scope"
                                + " LIKE 'C09;%'",
                        (rs, i) -> row(rs), id)
                .stream()
                .findFirst();
    }

    /** A record's document replaced (background start, progress, outcome, failure). */
    @Transactional
    public void update(long id, Doc doc, String inputHash) {
        String body = json.writeValueAsString(doc);
        jdbc.update(
                "UPDATE rule_simulation SET impact_summary = ?, input_hash = COALESCE(?,"
                        + " input_hash) WHERE simulation_id = ?",
                ps -> {
                    ps.setCharacterStream(1, new StringReader(body), body.length());
                    ps.setString(2, inputHash);
                    ps.setLong(3, id);
                });
    }

    /** Background progress: its own transaction, so C-09 sees it while the job runs. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void progress(long id, Doc doc) {
        update(id, doc, null);
    }

    /** The document's {@code jobRequestId} once the job is queued. */
    @Transactional
    public void attachJob(long id, Doc doc, long requestId) {
        update(id, doc.job(requestId), null);
    }

    @Transactional
    public void complete(long id, Doc doc, SimulationEngine.Outcome outcome) {
        update(id, doc.outcome(outcome), outcome.inputHash());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long id, Doc doc, String why) {
        update(id, doc.with(Status.FAILED, doc.done(), why), null);
    }

    /**
     * Background start: its own transaction, like {@link #progress}, so the job's transaction never
     * holds the row the progress updates write.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void running(long id, Doc doc) {
        update(id, doc.with(Status.RUNNING, 0, null), null);
    }

    /**
     * SRS-15-27 (plan 05j): the background run of an abandoned C-13 request is FAILED on its record
     * (it never computed, or stopped half way); a finished one is not touched.
     *
     * @return the records closed
     */
    @Transactional
    public int abandoned(long jobRequestId) {
        List<Row> open =
                jdbc.query(
                        "SELECT simulation_id, requested_by, requested_at, population_scope,"
                                + " population_count, input_hash, impact_summary FROM"
                                + " rule_simulation WHERE population_scope LIKE 'C09;%' AND"
                                + " input_hash = ?",
                        (rs, i) -> row(rs), NOT_YET);
        int closed = 0;
        for (Row r : open) {
            Doc doc = r.doc();
            boolean unfinished = doc.status() == Status.QUEUED || doc.status() == Status.RUNNING;
            if (unfinished && Long.valueOf(jobRequestId).equals(doc.jobRequestId())) {
                update(r.id(), doc.with(Status.FAILED, doc.done(), "ABANDONED"), null);
                closed++;
            }
        }
        return closed;
    }

    Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(
                rs.getLong("simulation_id"),
                rs.getString("requested_by"),
                rs.getTimestamp("requested_at").toInstant(),
                rs.getString("population_scope"),
                rs.getLong("population_count"),
                rs.getString("input_hash"),
                json.readValue(rs.getString("impact_summary"), Doc.class));
    }
}
