package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.RateSet;
import in.gov.fci.payroll.configuration.business.model.RateSetLine;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.configuration.persistence.RateVersionFacts;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads Wireframes v6.1 draws over the rate sets (OD-121): the M-04 Dearness Allowance register
 * and the HRA &amp; CCA table by city class (SRS M-04 field table, SRS-1-3, SRS-1-62), the M-22
 * filters and export (SRS-11-15, SRS-G-13) and the M-04 order copy of a version (SRS-1-62,
 * SRS-G-30). Nothing here changes a rate: a change stays a dated version under maker-checker in
 * {@link RateMasterService}.
 */
@Service
@Loggable
public class RateScreenService {

    static final String M04 = "M-04";
    static final String M22 = "M-22";
    private static final List<String> DEARNESS = List.of("DA_IDA", "DA_CDA");
    private static final List<String> CITY_SETS = List.of("HRA", "HRA_CDA", "CCA");

    /** The state of a version as M-04 draws it: Active, Superseded, Awaiting approval, ... */
    public enum VersionStatus {
        ACTIVE,
        SCHEDULED,
        SUPERSEDED,
        AWAITING_APPROVAL,
        REJECTED
    }

    /** One row of the Dearness Allowance table: a version of DA_IDA or DA_CDA. */
    public record DearnessRow(
            Long id,
            String code,
            String pattern,
            BigDecimal rate,
            String computedOn,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            int versionNo,
            String arrearReference,
            BigDecimal arrearAmount,
            VersionStatus status) {}

    /**
     * One row of the HRA &amp; CCA table: the rates in force for a city class; {@code DOUBLE} is
     * the drawn Double HRA row (no rate of its own: twice the class rate within the window).
     */
    public record CityClassRow(
            String cityClass,
            BigDecimal hraPercent,
            BigDecimal hraCdaPercent,
            BigDecimal ccaAmount,
            boolean inForce,
            boolean pending) {}

    private final ConfigurationRepositories.RateSets sets;
    private final ConfigurationRepositories.RateSetLines lines;
    private final RateVersionFacts facts;
    private final RateMasterService rates;
    private final Authorization policy;
    private final SystemParameters parameters;
    private final DocumentStore documents;

    public RateScreenService(
            ConfigurationRepositories.RateSets sets,
            ConfigurationRepositories.RateSetLines lines,
            RateVersionFacts facts,
            RateMasterService rates,
            Authorization policy,
            SystemParameters parameters,
            DocumentStore documents) {
        this.sets = sets;
        this.lines = lines;
        this.facts = facts;
        this.rates = rates;
        this.policy = policy;
        this.parameters = parameters;
        this.documents = documents;
    }

    /** Every version of the two DA rate sets, the pattern's newest first. */
    @Transactional(readOnly = true)
    public List<DearnessRow> dearness(AccessContext context) {
        policy.require(context, M04, "VIEW");
        List<RateSet> versions =
                sets.findByRateSetCodeInOrderByRateSetCodeAscVersionNoDesc(DEARNESS);
        if (versions.isEmpty()) {
            return List.of();
        }
        Map<Long, BigDecimal> rate = new HashMap<>();
        lines.findByRateSetIdIn(versions.stream().map(RateSet::getRateSetId).toList())
                .forEach(l -> rate.putIfAbsent(l.getRateSetId(), l.getRateValue()));
        Map<Long, String> stated =
                facts.computedOn(
                        versions.stream()
                                .map(RateSet::getApprovalId)
                                .filter(Objects::nonNull)
                                .toList());
        Map<LocalDate, RateVersionFacts.ArrearImpact> arrears =
                facts.dearnessArrears(
                        versions.stream().map(RateSet::getEffectiveFrom).distinct().toList());
        LocalDate today = parameters.today();
        List<DearnessRow> rows = new ArrayList<>();
        for (RateSet v : versions) {
            RateVersionFacts.ArrearImpact arrear = arrears.get(v.getEffectiveFrom());
            rows.add(
                    new DearnessRow(
                            v.getRateSetId(),
                            v.getRateSetCode(),
                            v.getRateSetCode().substring("DA_".length()),
                            rate.get(v.getRateSetId()),
                            stated.get(v.getApprovalId()),
                            v.getEffectiveFrom(),
                            v.getEffectiveTo(),
                            v.getVersionNo(),
                            arrear == null ? null : arrear.reference(),
                            arrear == null ? null : arrear.amount(),
                            statusOf(v, today)));
        }
        return rows;
    }

    /** The state of a version on {@code today}. */
    static VersionStatus statusOf(RateSet version, LocalDate today) {
        if (version.isPending()) {
            return VersionStatus.AWAITING_APPROVAL;
        }
        if (!version.isApproved()) {
            return VersionStatus.REJECTED;
        }
        if (version.isInForceOn(today)) {
            return VersionStatus.ACTIVE;
        }
        return version.getEffectiveFrom().isAfter(today)
                ? VersionStatus.SCHEDULED
                : VersionStatus.SUPERSEDED;
    }

    /** X, Y, Z and the Double HRA row, from the HRA, HRA_CDA and CCA sets in force today. */
    @Transactional(readOnly = true)
    public List<CityClassRow> cityClasses(AccessContext context) {
        policy.require(context, M04, "VIEW");
        List<RateSet> versions =
                sets.findByRateSetCodeInOrderByRateSetCodeAscVersionNoDesc(CITY_SETS);
        LocalDate today = parameters.today();
        List<RateSet> inForce = versions.stream().filter(v -> v.isInForceOn(today)).toList();
        boolean pending = versions.stream().anyMatch(RateSet::isPending);
        List<RateSetLine> current =
                inForce.isEmpty()
                        ? List.of()
                        : lines.findByRateSetIdIn(
                                inForce.stream().map(RateSet::getRateSetId).toList());
        List<CityClassRow> rows = new ArrayList<>();
        for (String cityClass : List.of("X", "Y", "Z")) {
            BigDecimal hra = value(inForce, current, "HRA", cityClass);
            BigDecimal hraCda = value(inForce, current, "HRA_CDA", cityClass);
            BigDecimal cca = value(inForce, current, "CCA", cityClass);
            rows.add(
                    new CityClassRow(
                            cityClass,
                            hra,
                            hraCda,
                            cca,
                            hra != null || hraCda != null || cca != null,
                            pending));
        }
        rows.add(
                new CityClassRow(
                        "DOUBLE",
                        null,
                        null,
                        null,
                        inForce.stream().anyMatch(v -> v.getRateSetCode().startsWith("HRA")),
                        pending));
        return rows;
    }

    private static BigDecimal value(
            List<RateSet> inForce, List<RateSetLine> current, String code, String cityClass) {
        return inForce.stream()
                .filter(v -> v.getRateSetCode().equals(code))
                .flatMap(
                        v ->
                                current.stream()
                                        .filter(l -> l.getRateSetId().equals(v.getRateSetId())))
                .filter(l -> cityClass.equals(l.getCityClass()))
                .map(RateSetLine::getRateValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * The M-22 filters: the pay pattern (each master is kept per pattern, its code ending _IDA or
     * _CDA), the allowance type (the catalogue list) and the list search over the name.
     */
    public static List<RateMasterService.RateMasterView> filter(
            List<RateMasterService.RateMasterView> rows,
            String pattern,
            String group,
            String query) {
        String text = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return rows.stream()
                .filter(
                        r ->
                                pattern == null
                                        || pattern.isEmpty()
                                        || r.code().endsWith("_" + pattern))
                .filter(r -> group == null || group.isEmpty() || group.equals(r.group()))
                .filter(r -> text.isEmpty() || r.name().toLowerCase(Locale.ROOT).contains(text))
                .toList();
    }

    /** The M-22 list as filtered, for the row count before an export starts (SRS-G-13). */
    @Transactional(readOnly = true)
    public long specialExportSize(
            AccessContext context, String pattern, String group, String query) {
        policy.require(context, M22, "EXPORT");
        return filter(rates.list(context, RateMasterService.Screen.SPECIAL), pattern, group, query)
                .size();
    }

    /** The M-22 list as filtered, with the version in force (SRS-G-13). */
    @Transactional(readOnly = true)
    public void exportSpecial(
            AccessContext context, String pattern, String group, String query, ExportSink sink)
            throws IOException {
        policy.require(context, M22, "EXPORT");
        sink.header("Code", "Allowance", "Pay code", "Version", "Effective from", "Effective to");
        List<RateMasterService.RateMasterView> rows =
                filter(
                        rates.list(context, RateMasterService.Screen.SPECIAL),
                        pattern,
                        group,
                        query);
        for (RateMasterService.RateMasterView r : rows) {
            RateMasterService.VersionView v = r.current();
            sink.row(
                    r.code(),
                    r.name(),
                    r.payCode(),
                    v == null ? null : v.versionNo(),
                    v == null ? null : v.effectiveFrom(),
                    v == null ? null : v.effectiveTo());
        }
        ExportSink.requireComplete(rows.size(), sink);
    }

    /** The order copies stored with a version of an M-04 rate set (SRS-1-62, SRS-G-30). */
    @Transactional(readOnly = true)
    public List<DocumentStore.AttachmentView> orders(AccessContext context, Long versionId) {
        policy.require(context, M04, "VIEW");
        return documents.active(RateMasterService.ENTITY, key(versionId));
    }

    /** Stores an order copy with the version; the version itself does not change. */
    @Transactional
    public DocumentStore.AttachmentView attachOrder(
            AccessContext context,
            Long versionId,
            String documentType,
            String reference,
            LocalDate orderDate,
            String authority,
            String fileName,
            byte[] content) {
        policy.require(context, M04, "CREATE");
        return documents.attach(
                context.loginId(),
                new DocumentStore.NewAttachment(
                        RateMasterService.ENTITY,
                        key(versionId),
                        documentType,
                        reference,
                        orderDate,
                        authority,
                        fileName,
                        content,
                        false));
    }

    @Transactional
    public DocumentStore.Content file(
            AccessContext context, String sourceIp, Long versionId, Long attachmentId) {
        policy.require(context, M04, "VIEW");
        return documents.read(
                context.loginId(),
                sourceIp,
                RateMasterService.ENTITY,
                key(versionId),
                attachmentId);
    }

    /** The key of a version of an M-04 rate set; any other id is refused. */
    private String key(Long versionId) {
        RateSet version =
                sets.findByRateSetId(versionId)
                        .filter(
                                v ->
                                        rates.screenOf(v.getRateSetCode())
                                                .filter(s -> s == RateMasterService.Screen.STAFF)
                                                .isPresent())
                        .orElseThrow(() -> new FciException(ApiErrorCode.MASTER_NOT_IN_CATALOGUE));
        return String.valueOf(version.getRateSetId());
    }
}
