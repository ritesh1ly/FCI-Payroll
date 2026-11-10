package in.gov.fci.payroll.jobs.orchestration;

import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * What each job takes, and the server-side validation of a submission. The same definitions drive
 * the submit form (C-13), so the screen never offers a parameter the server would not accept.
 *
 * <ul>
 *   <li>PAYROLL_DRY_RUN, PAYROLL_COMPUTATION: {@code locationId} (the run is on its payroll cycle
 *       unit), {@code payPeriod} (not after the current payroll period, not older than
 *       MAX_RETRO_PERIOD months);
 *   <li>DA_ARREAR: {@code locationId}, {@code fromPeriod} and {@code toPeriod} (from &lt;= to &lt;=
 *       current period, span within MAX_RETRO_PERIOD months).
 * </ul>
 */
@Component
public class JobCatalog {

    public enum Kind {
        LOCATION,
        PERIOD,
        /** A financial year as fin_year holds it, 2026-27 (Module 08). */
        FIN_YEAR,
        /** A quarter of a financial year, Q1 … Q4 (Module 08). */
        QUARTER
    }

    public record ParamDef(String name, Kind kind, boolean required) {}

    /**
     * A submittable job type, its parameters and its queue priority (SRS-NF-11, DR-17): the highest
     * a requester may ask for; the form offers that level and the lower ones.
     */
    public record JobDef(JobType type, List<ParamDef> params, int priority) {
        public JobDef(JobType type, List<ParamDef> params) {
            this(type, params, type.priority());
        }
    }

    private static final Pattern PERIOD = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");
    private static final Pattern ID = Pattern.compile("^[1-9]\\d{0,17}$");
    private static final Pattern FIN_YEAR = Pattern.compile("^\\d{4}-\\d{2}$");
    private static final Pattern QUARTER = Pattern.compile("^Q[1-4]$");

    private static final Map<JobType, List<ParamDef>> DEFS =
            Map.of(
                    JobType.PAYROLL_DRY_RUN,
                            List.of(
                                    new ParamDef("locationId", Kind.LOCATION, true),
                                    new ParamDef("payPeriod", Kind.PERIOD, true)),
                    JobType.PAYROLL_COMPUTATION,
                            List.of(
                                    new ParamDef("locationId", Kind.LOCATION, true),
                                    new ParamDef("payPeriod", Kind.PERIOD, true)),
                    JobType.DA_ARREAR,
                            List.of(
                                    new ParamDef("locationId", Kind.LOCATION, true),
                                    new ParamDef("fromPeriod", Kind.PERIOD, true),
                                    new ParamDef("toPeriod", Kind.PERIOD, true)),
                    JobType.HRA_ARREAR,
                            List.of(
                                    new ParamDef("locationId", Kind.LOCATION, true),
                                    new ParamDef("fromPeriod", Kind.PERIOD, true),
                                    new ParamDef("toPeriod", Kind.PERIOD, true)),
                    JobType.TAX_FORM16_PART_B,
                            List.of(
                                    new ParamDef("locationId", Kind.LOCATION, true),
                                    new ParamDef("finYear", Kind.FIN_YEAR, true)),
                    JobType.TAX_24Q_RETURN,
                            List.of(
                                    new ParamDef("locationId", Kind.LOCATION, true),
                                    new ParamDef("finYear", Kind.FIN_YEAR, true),
                                    new ParamDef("quarter", Kind.QUARTER, true)));

    private final SystemParameters parameters;
    private final PayrollPeriods periods;

    public JobCatalog(SystemParameters parameters, PayrollPeriods periods) {
        this.parameters = parameters;
        this.periods = periods;
    }

    public List<JobDef> submittable() {
        return List.of(
                new JobDef(JobType.PAYROLL_DRY_RUN, DEFS.get(JobType.PAYROLL_DRY_RUN)),
                new JobDef(JobType.PAYROLL_COMPUTATION, DEFS.get(JobType.PAYROLL_COMPUTATION)),
                new JobDef(JobType.DA_ARREAR, DEFS.get(JobType.DA_ARREAR)),
                new JobDef(JobType.HRA_ARREAR, DEFS.get(JobType.HRA_ARREAR)),
                new JobDef(JobType.TAX_FORM16_PART_B, DEFS.get(JobType.TAX_FORM16_PART_B)),
                new JobDef(JobType.TAX_24Q_RETURN, DEFS.get(JobType.TAX_24Q_RETURN)));
    }

    /** The current payroll period of the M-10 calendar (C-13 default and upper bound). */
    public YearMonth currentPeriod() {
        return periods.current();
    }

    /**
     * Validates and normalises a submission: only the job's own parameters, each well formed and
     * within the period rules. Returns the canonical parameter map (sorted).
     */
    public Map<String, String> validate(JobType type, Map<String, String> raw) {
        if (!type.isSubmittable()) {
            throw new FciException(ApiErrorCode.JOB_TYPE_NOT_SUBMITTABLE);
        }
        Map<String, String> input = raw == null ? Map.of() : raw;
        List<ParamDef> defs = DEFS.get(type);
        for (String key : input.keySet()) {
            if (defs.stream().noneMatch(d -> d.name().equals(key))) {
                throw invalid(key);
            }
        }
        Map<String, String> clean = new TreeMap<>();
        for (ParamDef def : defs) {
            String value = input.get(def.name());
            value = value == null ? null : value.strip();
            if (value == null || value.isEmpty()) {
                if (def.required()) {
                    throw invalid(def.name());
                }
                continue;
            }
            boolean ok =
                    switch (def.kind()) {
                        case LOCATION -> ID.matcher(value).matches();
                        case PERIOD -> PERIOD.matcher(value).matches();
                        case FIN_YEAR -> finYear(value);
                        case QUARTER -> QUARTER.matcher(value).matches();
                    };
            if (!ok) {
                throw invalid(def.name());
            }
            clean.put(def.name(), value);
        }
        YearMonth current = currentPeriod();
        for (ParamDef def : defs) {
            if (def.kind() == Kind.PERIOD && clean.containsKey(def.name())) {
                YearMonth period = period(clean.get(def.name()));
                // The current period is inside every back-date limit; an earlier one needs the
                // limit (CLR-147: unset, refused "not configured", never a guessed window).
                if (period.isAfter(current)
                        || (period.isBefore(current)
                                && period.isBefore(current.minusMonths(maxRetroMonths())))) {
                    throw invalid(def.name());
                }
            }
            if (def.kind() == Kind.FIN_YEAR && clean.containsKey(def.name())) {
                // Not a year after the one the current payroll period is in (April to March).
                int start = Integer.parseInt(clean.get(def.name()).substring(0, 4));
                int now = current.getMonthValue() >= 4 ? current.getYear() : current.getYear() - 1;
                if (start > now) {
                    throw invalid(def.name());
                }
            }
        }
        if (isArrear(type)) {
            YearMonth from = period(clean.get("fromPeriod"));
            YearMonth to = period(clean.get("toPeriod"));
            if (from.isAfter(to)
                    || (from.isBefore(to)
                            && ChronoUnit.MONTHS.between(from, to) > maxRetroMonths())) {
                throw invalid("fromPeriod");
            }
        }
        return clean;
    }

    /** A financial year 2026-27: the second part is the year after the first. */
    private static boolean finYear(String value) {
        if (!FIN_YEAR.matcher(value).matches()) {
            return false;
        }
        int start = Integer.parseInt(value.substring(0, 4));
        return Integer.parseInt(value.substring(5)) == (start + 1) % 100;
    }

    /** The period a request is filed under (list filter): the pay period, or the arrear's end. */
    public static String periodOf(JobType type, Map<String, String> params) {
        return isArrear(type) ? params.get("toPeriod") : params.get("payPeriod");
    }

    /** The rate arrear jobs: a period range rather than one pay period (SRS-8-6, SRS-8-10). */
    static boolean isArrear(JobType type) {
        return type == JobType.DA_ARREAR || type == JobType.HRA_ARREAR;
    }

    /** A period already checked against {@code PERIOD} (and required where it is read). */
    private static YearMonth period(String value) {
        return YearMonth.parse(value);
    }

    /**
     * SRS-8-6: the back-date limit (A-07 MAX_RETRO_PERIOD) in force; while FCI has not set it
     * (CLR-147) a period check that depends on it is refused RULE-422-NOT-CONFIGURED (R-RULE).
     */
    private long maxRetroMonths() {
        return parameters
                .maxRetroMonths()
                .orElseThrow(
                        () ->
                                new FciException(
                                        ApiErrorCode.RULE_NOT_CONFIGURED,
                                        new Object[] {"MAX_RETRO_PERIOD", parameters.today()},
                                        List.of(),
                                        null));
    }

    private static FciException invalid(String name) {
        return new FciException(
                ApiErrorCode.JOB_PARAMETER_INVALID, new Object[] {name}, List.of(), null);
    }
}
