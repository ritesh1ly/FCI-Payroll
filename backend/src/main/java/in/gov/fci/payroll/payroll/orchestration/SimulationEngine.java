package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.api.StaffDeductionRunner;
import in.gov.fci.payroll.payroll.batch.computation.MemberReader;
import in.gov.fci.payroll.payroll.business.PayDataException;
import in.gov.fci.payroll.payroll.business.PayRules;
import in.gov.fci.payroll.payroll.business.SimulationRules;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The computation of a C-09 General (Simulation) Run (plan 05 phase 05i; SRS-15-7, BR-CYC-007),
 * shared by the online instance (at or below the interactive limit) and the batch job
 * SIMULATION_RUN (above it). It reads the population exactly as the regular computation does
 * ({@link MemberReader}: same eligibility), applies {@link PayRules} with the rates in force on the
 * first day of the period, and once more with the what-if DA rates when there are any. Nothing is
 * written: the caller keeps the run record and its summary, never the result rows.
 *
 * <p>Takes no cycle lease and needs no cycle instance (plan D-18): a simulation runs outside the
 * cycle sequence and never blocks a freeze or a lock.
 */
@Component
public class SimulationEngine {

    /** The members read and computed per round trip; an Oracle IN list takes at most 1000. */
    static final int PAGE = 500;

    private static final Pattern EMPLOYEE_SUFFIX = Pattern.compile(" \\(employee \\d+\\)");

    /** What to simulate. {@code employeeIds} is null for the whole office instance. */
    public record Request(
            long unitId,
            YearMonth period,
            List<Long> employeeIds,
            SimulationRules.WhatIf whatIf,
            BigDecimal daIda,
            BigDecimal daCda) {}

    /** One member's preview (interactive runs only; never stored). */
    public record MemberResult(
            long employeeId,
            String status,
            String reason,
            BigDecimal gross,
            BigDecimal net,
            BigDecimal whatIfGross,
            BigDecimal whatIfNet) {}

    /** The totals of one side; {@code components} by pay code. */
    public record Side(
            long computed,
            long stopped,
            Map<String, Long> skipped,
            BigDecimal gross,
            BigDecimal deductions,
            BigDecimal net,
            Map<Integer, BigDecimal> components) {}

    /**
     * The outcome: the members read, the as-is side, the what-if side (null for an as-is preview),
     * the hash of every input (SRS-15-7 stale alert), and the member rows when asked for.
     */
    public record Outcome(
            long population,
            Side asIs,
            Side whatIf,
            String inputHash,
            String versionSetHash,
            List<MemberResult> members) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final OrgDirectory org;
    private final PayConfiguration configuration;
    private final StaffDeductionRunner deductions;

    public SimulationEngine(
            NamedParameterJdbcTemplate jdbc, OrgDirectory org, PayConfiguration configuration) {
        this(jdbc, org, configuration, null);
    }

    /** With the month's deductions, so a simulated net is the run's (plan 16 phase 16e-2). */
    @org.springframework.beans.factory.annotation.Autowired
    public SimulationEngine(
            NamedParameterJdbcTemplate jdbc,
            OrgDirectory org,
            PayConfiguration configuration,
            StaffDeductionRunner deductions) {
        this.jdbc = jdbc;
        this.org = org;
        this.configuration = configuration;
        this.deductions = deductions;
    }

    /**
     * The locations whose employees the unit pays: its subtree, less the parts paid by a cycle unit
     * of their own below it.
     */
    public List<Long> locations(long unitId) {
        return org.subtree(unitId).stream()
                .filter(id -> org.cycleUnitOf(id).map(u -> u.getId().equals(unitId)).orElse(false))
                .sorted()
                .toList();
    }

    /** The eligible members of the request (what the run would read). */
    @Transactional(readOnly = true)
    public long count(Request request) {
        long total = 0;
        for (List<Long> locations : chunks(locations(request.unitId()))) {
            for (List<Long> ids : memberChunks(request)) {
                MapSqlParameterSource params =
                        new MapSqlParameterSource()
                                .addValue("locations", locations)
                                .addValue("yymm", MemberReader.yymm(request.period().atDay(1)));
                String members = "";
                if (ids != null) {
                    params.addValue("employeeIds", ids);
                    members = " AND e.employee_id IN (:employeeIds)";
                }
                Long n =
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM employee e " + MemberReader.where() + members,
                                params,
                                Long.class);
                total += CycleSupport.orZero(n);
            }
        }
        return total;
    }

    /**
     * Computes the request.
     *
     * @param keepMembers whether to return the member rows (interactive runs)
     * @param progress told the members done after each page (background runs)
     */
    @Transactional(readOnly = true)
    public Outcome run(Request request, boolean keepMembers, LongConsumer progress) {
        return run(locations(request.unitId()), request, keepMembers, progress);
    }

    /**
     * A run over named members at named locations, not an office instance (M-08: the sample of a
     * rule simulation, which crosses offices). Same read and rules as {@link #run}; member rows
     * returned; nothing stored.
     */
    @Transactional(readOnly = true)
    public Outcome runAt(List<Long> locations, Request request) {
        return run(locations, request, true, n -> {});
    }

    private Outcome run(
            List<Long> unitLocations, Request request, boolean keepMembers, LongConsumer progress) {
        PayConfiguration.Snapshot base =
                configuration.staffConfiguration(request.period().atDay(1));
        PayConfiguration.Snapshot whatIf =
                request.whatIf() == SimulationRules.WhatIf.DA_RATE
                        ? SimulationRules.override(base, request.daIda(), request.daCda())
                        : null;
        SimulationRules.Totals asIsTotals = new SimulationRules.Totals();
        SimulationRules.Totals whatIfTotals = new SimulationRules.Totals();
        List<MemberResult> members = new ArrayList<>();
        MessageDigest digest = digest();
        StaffDeductionRunner.Session session =
                deductions == null ? null : deductions.open(request.period());
        update(digest, session == null ? base.versionSetHash() : session.versionSetHash(base));
        update(digest, request.whatIf() + "|" + request.daIda() + "|" + request.daCda());
        long read = 0;
        for (List<Long> locations : chunks(unitLocations)) {
            for (List<Long> ids : memberChunks(request)) {
                long after = 0;
                List<MemberReader.MemberRow> page;
                do {
                    page =
                            MemberReader.page(
                                    jdbc, locations, ids, request.period().atDay(1), after, PAGE);
                    for (MemberReader.MemberRow row : page) {
                        update(digest, row.toString());
                        Computed asIs = compute(row, session, base, asIsTotals);
                        Computed preview =
                                whatIf == null ? null : compute(row, session, whatIf, whatIfTotals);
                        if (keepMembers) {
                            members.add(result(row, asIs, preview));
                        }
                        after = row.employeeId();
                    }
                    read += page.size();
                    progress.accept(read);
                } while (page.size() == PAGE);
            }
        }
        return new Outcome(
                read,
                side(asIsTotals),
                whatIf == null ? null : side(whatIfTotals),
                HexFormat.of().formatHex(digest.digest()),
                base.versionSetHash(),
                List.copyOf(members));
    }

    /** The hash of the request's inputs as they are now (SRS-15-7: "data has changed"). */
    @Transactional(readOnly = true)
    public String inputHash(Request request) {
        return run(request, false, n -> {}).inputHash();
    }

    private record Computed(PayRules.Outcome outcome, String failure) {}

    private static Computed compute(
            MemberReader.MemberRow row,
            StaffDeductionRunner.Session session,
            PayConfiguration.Snapshot config,
            SimulationRules.Totals totals) {
        try {
            PayRules.Outcome outcome = PayRules.compute(row.member(), config);
            if (session != null) {
                outcome =
                        session.apply(
                                outcome,
                                row.employeeId(),
                                row.locationId(),
                                row.payPattern(),
                                config);
            }
            totals.add(outcome);
            return new Computed(outcome, null);
        } catch (PayDataException gap) {
            // A data or configuration gap is counted by its reason (without the member), never
            // computed as zero.
            String reason =
                    EMPLOYEE_SUFFIX
                            .matcher(
                                    Objects.requireNonNullElse(
                                            gap.getMessage(), "PayDataException"))
                            .replaceAll("");
            totals.skip(reason);
            return new Computed(null, reason);
        }
    }

    private static MemberResult result(
            MemberReader.MemberRow row, Computed asIs, Computed preview) {
        String status;
        String reason;
        if (asIs.failure() != null) {
            status = "NOT_COMPUTED";
            reason = asIs.failure();
        } else {
            status = asIs.outcome().status();
            reason = asIs.outcome().skipReason();
        }
        PayRules.Outcome p = preview == null ? null : preview.outcome();
        return new MemberResult(
                row.employeeId(),
                status,
                reason,
                asIs.outcome() == null ? null : asIs.outcome().gross(),
                asIs.outcome() == null ? null : asIs.outcome().net(),
                p == null ? null : p.gross(),
                p == null ? null : p.net());
    }

    private static Side side(SimulationRules.Totals t) {
        return new Side(
                t.computed(),
                t.stopped(),
                t.skipped(),
                t.gross(),
                t.deductions(),
                t.net(),
                t.byPayCode());
    }

    /** The member chunks of the request: one null chunk (no narrowing) for the office. */
    private static List<List<Long>> memberChunks(Request request) {
        if (request.employeeIds() == null) {
            List<List<Long>> office = new ArrayList<>();
            office.add(null);
            return office;
        }
        return chunks(request.employeeIds().stream().sorted().toList());
    }

    static List<List<Long>> chunks(List<Long> ids) {
        List<List<Long>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += PAGE) {
            out.add(ids.subList(i, Math.min(ids.size(), i + PAGE)));
        }
        return out;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }
}
