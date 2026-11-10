package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.jobs.api.ResumeCheck;
import in.gov.fci.payroll.payroll.api.StaffDeductionRunner;
import in.gov.fci.payroll.payroll.business.ResumeRule;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-15-26 / BR-CYC-013 for the C-02 computation jobs (PAYROLL_COMPUTATION, PAYROLL_DRY_RUN): the
 * run a Resume or Restart would continue is compared with what it would use now ({@link
 * ResumeRule}). Its start is the latest {@code computation_run} of the request (CycleRuns writes
 * {@code parameters_json} as {@code {"jobRequestId":<id>,"locationId":..,"payPeriod":"yyyy-MM"}});
 * the version set is resolved for its period exactly as the run resolved it at the start ({@code
 * PayConfiguration.staffConfiguration(first day of the period)}). A request whose run never opened
 * computed nothing, so it continues.
 *
 * <p>Used by the online instance (C-13 control command) and by the batch instance (the run
 * listener, just before the relaunch).
 */
@Component
public class ComputationResumeCheck implements ResumeCheck {

    /** The jobs whose runs this module opens (jobs JobType names). */
    static final Set<String> JOBS = Set.of("PAYROLL_COMPUTATION", "PAYROLL_DRY_RUN");

    /** The hash a run records when its request has none (ComputationRunListener). */
    static final String NO_PARAMETER_HASH = "-";

    private static final Pattern PERIOD = Pattern.compile("\"payPeriod\":\"(\\d{4}-\\d{2})\"");

    private final JdbcTemplate jdbc;
    private final PayConfiguration configuration;
    private final StaffDeductionRunner deductions;

    public ComputationResumeCheck(JdbcTemplate jdbc, PayConfiguration configuration) {
        this(jdbc, configuration, null);
    }

    /** With the deduction sources, whose versions enter the hash (plan 16 phase 16e-2). */
    @org.springframework.beans.factory.annotation.Autowired
    public ComputationResumeCheck(
            JdbcTemplate jdbc, PayConfiguration configuration, StaffDeductionRunner deductions) {
        this.jdbc = jdbc;
        this.configuration = configuration;
        this.deductions = deductions;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ErrorCode> refusal(long jobRequestId, String jobName, String parameterHash) {
        if (!JOBS.contains(jobName)) {
            return Optional.empty();
        }
        List<Map<String, Object>> runs =
                jdbc.queryForList(
                        "SELECT parameter_hash, rule_version_set_hash, parameters_json FROM"
                                + " computation_run WHERE parameters_json LIKE ? ORDER BY run_id"
                                + " DESC FETCH FIRST 1 ROWS ONLY",
                        "{\"jobRequestId\":" + jobRequestId + ",%");
        if (runs.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> run = runs.get(0);
        Matcher period = PERIOD.matcher(String.valueOf(run.get("PARAMETERS_JSON")));
        // A run without its period cannot be compared: it is not continued on a guess.
        String versionsNow =
                period.find() ? versionsOf(YearMonth.parse(period.group(1)).atDay(1)) : null;
        return ResumeRule.change(
                        (String) run.get("PARAMETER_HASH"),
                        (String) run.get("RULE_VERSION_SET_HASH"),
                        parameterHash == null ? NO_PARAMETER_HASH : parameterHash,
                        versionsNow)
                .map(
                        change ->
                                change == ResumeRule.Change.PARAMETERS
                                        ? ApiErrorCode.JOB_RESUME_PARAMETERS_CHANGED
                                        : ApiErrorCode.JOB_RESUME_RULES_CHANGED);
    }

    private String versionsOf(java.time.LocalDate start) {
        PayConfiguration.Snapshot snapshot = configuration.staffConfiguration(start);
        return deductions == null
                ? snapshot.versionSetHash()
                : deductions.open(YearMonth.from(start)).versionSetHash(snapshot);
    }
}
