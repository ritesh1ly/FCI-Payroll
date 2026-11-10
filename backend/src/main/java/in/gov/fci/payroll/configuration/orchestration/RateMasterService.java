package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.MasterVersioning;
import in.gov.fci.payroll.configuration.business.model.RateSet;
import in.gov.fci.payroll.configuration.business.model.RateSetLine;
import in.gov.fci.payroll.configuration.business.model.ReferenceValue;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-04 Staff Rate Tables and M-22 Special Compensatory &amp; Allowance Rate Masters, on {@code
 * rate_set} and {@code rate_set_line} (target DDL v4.0 S06: one shape for every rate master). A
 * change is a new dated version of a rate set, submitted for approval (workflow actions M04_CREATE,
 * M22_CREATE: Rule Administrator makes, Rule Approver approves, at Headquarters); only an APPROVED
 * version is in force, and an approval ends the previous version the day before. Each screen
 * maintains only the rate sets of its own catalogue (reference lists), whatever code a request
 * names.
 */
@Service
@Loggable
public class RateMasterService {

    public static final String ENTITY = "rate_set";

    /** The two rate-master screens and their catalogues. */
    public enum Screen {
        STAFF("M-04", List.of("STAFF_RATE_MASTER")),
        SPECIAL("M-22", List.of("SPECIAL_COMPENSATORY", "OTHER_RATE_MASTER"));

        private final String code;
        private final List<String> lists;

        Screen(String code, List<String> lists) {
            this.code = code;
            this.lists = lists;
        }

        public String code() {
            return code;
        }
    }

    public record LineView(
            Long designationId,
            String categoryCode,
            String payPattern,
            BigDecimal basicSlabFrom,
            BigDecimal basicSlabTo,
            String cityClass,
            String rateCode,
            BigDecimal rateValue,
            String unit) {}

    public record VersionView(
            Long id,
            int versionNo,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String state,
            Long approvalId,
            Long circularOrderId,
            List<LineView> lines) {}

    /** One catalogue entry with its version in force (null = not seeded) and pending proposal. */
    public record RateMasterView(
            String code,
            String name,
            String group,
            Integer payCode,
            VersionView current,
            VersionView pending) {}

    /**
     * @param computedOn the base a DA version is stated on (M-04 "Computed On", wireframe v6.1);
     *     kept with the proposal in the approval payload and read back by {@link
     *     RateScreenService}. It does not drive the computation (WF-207)
     */
    public record ProposedVersion(
            LocalDate effectiveFrom,
            Long circularOrderId,
            List<LineView> lines,
            String reason,
            String computedOn) {

        public ProposedVersion(
                LocalDate effectiveFrom,
                Long circularOrderId,
                List<LineView> lines,
                String reason) {
            this(effectiveFrom, circularOrderId, lines, reason, null);
        }
    }

    private static final Pattern RATE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    private static final Set<String> PATTERNS = Set.of("IDA", "CDA", "FND");
    private static final Set<String> CITY_CLASSES = Set.of("X", "Y", "Z");

    private final ConfigurationRepositories.ReferenceValues catalogue;
    private final ConfigurationRepositories.RateSets sets;
    private final ConfigurationRepositories.RateSetLines lines;
    private final MakerChecker approvals;
    private final Authorization policy;
    private final SystemParameters parameters;
    private final Circulars circulars;

    public RateMasterService(
            ConfigurationRepositories.ReferenceValues catalogue,
            ConfigurationRepositories.RateSets sets,
            ConfigurationRepositories.RateSetLines lines,
            MakerChecker approvals,
            Authorization policy,
            SystemParameters parameters,
            Circulars circulars) {
        this.catalogue = catalogue;
        this.sets = sets;
        this.lines = lines;
        this.approvals = approvals;
        this.policy = policy;
        this.parameters = parameters;
        this.circulars = circulars;
    }

    /**
     * The screen's catalogue with each rate set's version in force today and its pending proposal.
     */
    @Transactional(readOnly = true)
    public List<RateMasterView> list(AccessContext context, Screen screen) {
        policy.require(context, screen.code(), "VIEW");
        List<ReferenceValue> entries = catalogue.findByListCodes(screen.lists);
        Map<String, List<RateSet>> byCode = new HashMap<>();
        List<RateSet> all =
                sets.findByRateSetCodeInOrderByRateSetCodeAscVersionNoDesc(
                        entries.stream().map(ReferenceValue::getValueCode).toList());
        all.forEach(s -> byCode.computeIfAbsent(s.getRateSetCode(), k -> new ArrayList<>()).add(s));
        Map<Long, List<RateSetLine>> linesBySet = linesOf(all);
        LocalDate today = parameters.today();
        List<RateMasterView> result = new ArrayList<>();
        for (ReferenceValue entry : entries) {
            List<RateSet> versions = byCode.getOrDefault(entry.getValueCode(), List.of());
            RateSet current =
                    versions.stream().filter(v -> v.isInForceOn(today)).findFirst().orElse(null);
            RateSet pending = versions.stream().filter(RateSet::isPending).findFirst().orElse(null);
            result.add(
                    new RateMasterView(
                            entry.getValueCode(),
                            entry.getValueLabelEn(),
                            catalogue.listCodeOf(entry.getListId()).orElse(null),
                            entry.getNumericAttribute() == null
                                    ? null
                                    : entry.getNumericAttribute().intValue(),
                            view(current, linesBySet),
                            view(pending, linesBySet)));
        }
        return result;
    }

    /** Every version of one rate set, newest first. */
    @Transactional(readOnly = true)
    public List<VersionView> history(AccessContext context, Screen screen, String code) {
        policy.require(context, screen.code(), "VIEW");
        entry(screen, code);
        List<RateSet> versions = sets.findByRateSetCodeOrderByVersionNoDesc(code);
        Map<Long, List<RateSetLine>> linesBySet = linesOf(versions);
        return versions.stream().map(v -> view(v, linesBySet)).toList();
    }

    /** Proposes a new dated version of rate set {@code code}; in force only once approved. */
    @Transactional
    public VersionView propose(
            AccessContext context,
            Screen screen,
            String code,
            ProposedVersion request,
            OtpProof otp) {
        ReferenceValue entry = entry(screen, code);
        if (request == null
                || request.effectiveFrom() == null
                || request.lines() == null
                || request.lines().isEmpty()) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        validate(request.lines());
        if (screen == Screen.STAFF) {
            requirePayPatterns(request.lines());
        }
        List<RateSet> history = sets.findByRateSetCodeOrderByVersionNoDesc(code);
        MasterVersioning.requireFreeDate(
                request.effectiveFrom(),
                history.stream()
                        .filter(RateSet::isApproved)
                        .map(RateSet::getEffectiveFrom)
                        .toList(),
                history.stream().map(RateSet::getEffectiveFrom).toList());
        // SRS-1-62 (M-04 field table): a staff rate revision links its Active M-25 circular; SRS
        // l.2819 / SRS-G-38 (US-GAP-211): so does an M-22 rate change ("a new dated version ...
        // linked to its circular").
        circulars.requireActive(request.circularOrderId());
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                screen.code(),
                                "CREATE",
                                ENTITY,
                                code,
                                ActionType.CREATE,
                                policy.isAtHeadquarters(context) ? context.locationId() : null,
                                "{\"rateSet\":\""
                                        + code
                                        + "\",\"from\":\""
                                        + request.effectiveFrom()
                                        + "\",\"lines\":"
                                        + request.lines().size()
                                        + computedOnJson(request.computedOn())
                                        + "}",
                                request.effectiveFrom(),
                                request.reason()),
                        otp);
        int next = history.isEmpty() ? 1 : history.get(0).getVersionNo() + 1;
        RateSet set =
                sets.save(
                        RateSet.propose(
                                code,
                                entry.getValueLabelEn(),
                                "STAFF",
                                next,
                                request.effectiveFrom(),
                                request.circularOrderId(),
                                item.id()));
        List<RateSetLine> saved = new ArrayList<>();
        for (LineView l : request.lines()) {
            saved.add(
                    lines.save(
                            RateSetLine.of(
                                    set.getRateSetId(),
                                    l.designationId(),
                                    blankToNull(l.categoryCode()),
                                    blankToNull(l.payPattern()),
                                    l.basicSlabFrom(),
                                    l.basicSlabTo(),
                                    blankToNull(l.cityClass()),
                                    l.rateCode(),
                                    l.rateValue(),
                                    blankToNull(l.unit()))));
        }
        return view(set, Map.of(set.getRateSetId(), saved));
    }

    /** The rate-master screen whose catalogue holds {@code code} (its History button), if any. */
    java.util.Optional<Screen> screenOf(String code) {
        for (Screen screen : Screen.values()) {
            if (catalogue.findByListCodes(screen.lists).stream()
                    .anyMatch(v -> v.getValueCode().equals(code))) {
                return java.util.Optional.of(screen);
            }
        }
        return java.util.Optional.empty();
    }

    private ReferenceValue entry(Screen screen, String code) {
        return catalogue.findByListCodes(screen.lists).stream()
                .filter(v -> v.getValueCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new FciException(ApiErrorCode.MASTER_NOT_IN_CATALOGUE));
    }

    /**
     * Rate code and value (held exactly as given, SRS-G-17), percentages at most 100, slab order,
     * pattern / city class codes, no duplicate key.
     */
    static void validate(List<LineView> requested) {
        Set<String> keys = new HashSet<>();
        for (LineView l : requested) {
            boolean ok =
                    l.rateCode() != null
                            && RATE_CODE.matcher(l.rateCode()).matches()
                            && l.rateValue() != null
                            && l.rateValue().signum() >= 0
                            && heldAsGiven(l.rateValue())
                            && (!"PERCENT".equals(l.unit())
                                    || l.rateValue().compareTo(BigDecimal.valueOf(100)) <= 0)
                            && (blankToNull(l.payPattern()) == null
                                    || PATTERNS.contains(l.payPattern()))
                            && (blankToNull(l.cityClass()) == null
                                    || CITY_CLASSES.contains(l.cityClass()))
                            && (l.basicSlabFrom() == null
                                    || l.basicSlabTo() == null
                                    || l.basicSlabTo().compareTo(l.basicSlabFrom()) > 0)
                            && keys.add(
                                    String.join(
                                            "|",
                                            l.rateCode(),
                                            String.valueOf(l.designationId()),
                                            String.valueOf(blankToNull(l.categoryCode())),
                                            String.valueOf(blankToNull(l.payPattern())),
                                            String.valueOf(l.basicSlabFrom()),
                                            String.valueOf(blankToNull(l.cityClass()))));
            if (!ok) {
                throw new FciException(ApiErrorCode.MASTER_INVALID_LINES);
            }
        }
    }

    /**
     * M-04 field table (SRS l.3653): Pay Pattern is mandatory on a staff rate line - rates are held
     * separately for each pay pattern. M-22 lines may leave it blank (its field table is silent).
     */
    static void requirePayPatterns(List<LineView> requested) {
        if (requested.stream().anyMatch(l -> blankToNull(l.payPattern()) == null)) {
            throw new FciException(ApiErrorCode.MASTER_INVALID_LINES);
        }
    }

    /**
     * SRS-G-17 (US-GAP-107): a rate is held at the precision of its governing order and never
     * rounded before use - a value the stored column (rate_set_line.rate_value NUMBER(14,4), target
     * DDL v4.0) cannot hold exactly is refused rather than rounded by the database. Trailing zeros
     * do not count (12.50000 is 12.5).
     */
    static boolean heldAsGiven(BigDecimal value) {
        BigDecimal plain = value.stripTrailingZeros();
        return Math.max(plain.scale(), 0) <= 4 && plain.precision() - plain.scale() <= 10;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** The payload field of a stated DA base (the request pattern admits no quote or backslash). */
    private static String computedOnJson(String computedOn) {
        String text = blankToNull(computedOn);
        return text == null ? "" : ",\"computedOn\":\"" + text + "\"";
    }

    private Map<Long, List<RateSetLine>> linesOf(List<RateSet> versions) {
        Map<Long, List<RateSetLine>> result = new HashMap<>();
        if (!versions.isEmpty()) {
            lines.findByRateSetIdIn(versions.stream().map(RateSet::getRateSetId).toList())
                    .forEach(
                            l ->
                                    result.computeIfAbsent(l.getRateSetId(), k -> new ArrayList<>())
                                            .add(l));
        }
        return result;
    }

    private static VersionView view(RateSet set, Map<Long, List<RateSetLine>> linesBySet) {
        if (set == null) {
            return null;
        }
        List<LineView> rows =
                Optional.ofNullable(linesBySet.get(set.getRateSetId())).orElse(List.of()).stream()
                        .map(
                                l ->
                                        new LineView(
                                                l.getDesignationId(),
                                                l.getCategoryCode(),
                                                l.getPayPattern(),
                                                l.getBasicSlabFrom(),
                                                l.getBasicSlabTo(),
                                                l.getCityClass(),
                                                l.getRateCode(),
                                                l.getRateValue(),
                                                l.getUnitOfMeasure()))
                        .sorted(
                                java.util.Comparator.comparing(
                                                (LineView l) ->
                                                        Objects.toString(l.payPattern(), ""))
                                        .thenComparing(l -> Objects.toString(l.cityClass(), ""))
                                        .thenComparing(LineView::rateCode))
                        .toList();
        return new VersionView(
                set.getRateSetId(),
                set.getVersionNo(),
                set.getEffectiveFrom(),
                set.getEffectiveTo(),
                set.getApprovalState(),
                set.getApprovalId(),
                set.getCircularOrderId(),
                rows);
    }
}
