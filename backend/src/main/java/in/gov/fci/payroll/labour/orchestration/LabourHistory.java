package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import in.gov.fci.payroll.audit.api.RecordHistorySource.Version;
import in.gov.fci.payroll.labour.business.LabourRules.GangRole;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The labour masters as X-04 / History-button records (SRS-20-29, SRS-20-3): M-02 labour members
 * and M-09 gangs as one current row each (as-of rebuilt from their field-level audit rows); M-05
 * labour rate sets, M-05 NWNP rate sets and M-06 depot norm sets as approved effective-dated
 * versions. A set is approved when its approval item is (a set loaded without one is in force as
 * loaded); a pending or rejected set is not history (KB AU-9). The audit keys are those the
 * screens' services write: a set under its id, a member or a gang under its code and its id.
 */
@Configuration(proxyBeanMethods = false)
public class LabourHistory {

    /** M-02 labour member, key = the labour number, at the depot (scope). */
    @Bean
    RecordHistorySource labourProfileHistory(NamedParameterJdbcTemplate named) {
        return RecordHistorySource.of(
                LabourMasterService.ENTITY,
                key ->
                        first(
                                        named.queryForList(
                                                "SELECT p.employee_id, p.labour_number,"
                                                        + " p.payroll_type, p.depot_location_id,"
                                                        + " p.labour_status, p.pf_applicable_flag,"
                                                        + " p.effective_from, e.designation_id"
                                                        + " FROM labour_profile p JOIN employee e"
                                                        + " ON e.employee_id = p.employee_id WHERE"
                                                        + " p.labour_number = :code",
                                                Map.of("code", key)))
                                .map(
                                        p -> {
                                            Map<String, String> current = new LinkedHashMap<>();
                                            current.put(
                                                    "labour_status", text(p.get("LABOUR_STATUS")));
                                            current.put(
                                                    "payroll_type", text(p.get("PAYROLL_TYPE")));
                                            current.put(
                                                    "depot_location_id",
                                                    text(p.get("DEPOT_LOCATION_ID")));
                                            current.put(
                                                    "designation_id",
                                                    text(p.get("DESIGNATION_ID")));
                                            current.put(
                                                    "pf_applicable_flag",
                                                    text(p.get("PF_APPLICABLE_FLAG")));
                                            // Created under the labour number, amended under the
                                            // member's id (LabourMasterService).
                                            return new HistoryRecord(
                                                    LabourMasterService.ENTITY,
                                                    key,
                                                    LabourMasterService.SCREEN,
                                                    id(p.get("DEPOT_LOCATION_ID")),
                                                    List.of(key, text(p.get("EMPLOYEE_ID"))),
                                                    false,
                                                    List.of(),
                                                    current,
                                                    date(p.get("EFFECTIVE_FROM")));
                                        }));
    }

    /**
     * M-05 labour rate set series of a payroll, key = the engine (all depots, e.g. {@code
     * DPS_LABOUR}) or {@code ENGINE@depotLocationId} (one depot): each set is a version, its day
     * rates by designation code. A national record: only Headquarters sets labour rates.
     */
    @Bean
    RecordHistorySource labourRateHistory(NamedParameterJdbcTemplate named) {
        return RecordHistorySource.of(
                LabourRateService.ENTITY,
                key -> {
                    int at = key.indexOf('@');
                    String engine = at < 0 ? key : key.substring(0, at);
                    long location;
                    try {
                        location = at < 0 ? -1L : Long.parseLong(key.substring(at + 1));
                    } catch (NumberFormatException notADepot) {
                        return Optional.empty();
                    }
                    List<Map<String, Object>> rows =
                            named.queryForList(
                                    "SELECT w.min_wage_id, w.effective_from, w.effective_to,"
                                            + " w.version_no, w.approval_id, q.status,"
                                            + " w.interim_relief, w.goi_rate, w.goi_rate_source,"
                                            + " w.comparison_grain, d.designation_code,"
                                            + " w.min_guaranteed_wage, w.basic_wage, w.vda"
                                            + " FROM labour_min_wage w LEFT"
                                            + " JOIN approval_queue q ON q.approval_id ="
                                            + " w.approval_id LEFT JOIN org_designation d ON"
                                            + " d.designation_id = w.designation_id WHERE"
                                            + " w.engine_code = :engine AND w.rate_set_id IS NULL"
                                            + " AND COALESCE(w.location_id, -1) = :location ORDER"
                                            + " BY w.effective_from, w.min_wage_id",
                                    Map.of("engine", engine, "location", location));
                    if (rows.isEmpty()) {
                        return Optional.empty();
                    }
                    Map<LocalDate, List<Map<String, Object>>> sets =
                            rows.stream()
                                    .collect(
                                            Collectors.groupingBy(
                                                    r -> date(r.get("EFFECTIVE_FROM")),
                                                    LinkedHashMap::new,
                                                    Collectors.toList()));
                    List<String> auditKeys = new ArrayList<>();
                    List<Version> versions = new ArrayList<>();
                    for (List<Map<String, Object>> set : sets.values()) {
                        Map<String, Object> head = set.get(0);
                        auditKeys.add(text(head.get("MIN_WAGE_ID")));
                        if (!approved(head.get("APPROVAL_ID"), head.get("STATUS"))) {
                            continue;
                        }
                        Map<String, String> image = new LinkedHashMap<>();
                        image.put("interim_relief", amount(head.get("INTERIM_RELIEF")));
                        image.put("goi_rate", amount(head.get("GOI_RATE")));
                        image.put("goi_rate_source", text(head.get("GOI_RATE_SOURCE")));
                        image.put("comparison_grain", text(head.get("COMPARISON_GRAIN")));
                        // SRS-3-13 (US-GAP-61): a Departmental set's Basic wage and DA.
                        set.forEach(
                                r -> {
                                    String code = String.valueOf(r.get("DESIGNATION_CODE"));
                                    if (r.get("BASIC_WAGE") != null) {
                                        image.put(
                                                "basic_wage." + code, amount(r.get("BASIC_WAGE")));
                                        image.put("da." + code, amount(r.get("VDA")));
                                    } else {
                                        image.put(
                                                "mgw." + code,
                                                amount(r.get("MIN_GUARANTEED_WAGE")));
                                    }
                                });
                        versions.add(version(head, image));
                    }
                    return Optional.of(
                            HistoryRecord.ofVersions(
                                    LabourRateService.ENTITY,
                                    key,
                                    LabourRateService.SCREEN,
                                    null,
                                    auditKeys,
                                    versions));
                });
    }

    /**
     * M-05 NWNP rate set series of a depot and rate version, key = its set code: each set is a
     * version with its operations' piece rates and its designations' MGW. A national record: only
     * Headquarters sets labour rates.
     */
    @Bean
    RecordHistorySource nwnpRateHistory(NamedParameterJdbcTemplate named) {
        return RecordHistorySource.of(
                NwnpRateService.ENTITY,
                code -> {
                    List<Map<String, Object>> sets =
                            named.queryForList(
                                    "SELECT rate_set_id, version_no, effective_from,"
                                            + " effective_to, approval_id, approval_state FROM"
                                            + " rate_set WHERE rate_set_code = :code AND"
                                            + " payroll_type = 'NWNP_LABOUR' ORDER BY version_no",
                                    Map.of("code", code));
                    if (sets.isEmpty()) {
                        return Optional.empty();
                    }
                    List<Long> ids = sets.stream().map(s -> id(s.get("RATE_SET_ID"))).toList();
                    Map<Long, List<Map<String, Object>>> lines =
                            bySet(
                                    named.queryForList(
                                            "SELECT rate_set_id, operation_code, rate_per_unit,"
                                                    + " unit_of_measure FROM nwnp_sor_rate WHERE"
                                                    + " rate_set_id IN (:ids) ORDER BY"
                                                    + " operation_code",
                                            Map.of("ids", ids)),
                                    "RATE_SET_ID");
                    Map<Long, List<Map<String, Object>>> mgw =
                            bySet(
                                    named.queryForList(
                                            "SELECT w.rate_set_id, d.designation_code,"
                                                    + " w.min_guaranteed_wage FROM labour_min_wage"
                                                    + " w LEFT JOIN org_designation d ON"
                                                    + " d.designation_id = w.designation_id WHERE"
                                                    + " w.rate_set_id IN (:ids) ORDER BY"
                                                    + " d.designation_code",
                                            Map.of("ids", ids)),
                                    "RATE_SET_ID");
                    List<Version> versions = new ArrayList<>();
                    for (Map<String, Object> s : sets) {
                        if (!"APPROVED".equals(s.get("APPROVAL_STATE"))) {
                            continue;
                        }
                        Long id = id(s.get("RATE_SET_ID"));
                        Map<String, String> image = new LinkedHashMap<>();
                        lines.getOrDefault(id, List.of())
                                .forEach(
                                        l ->
                                                image.put(
                                                        "rate." + l.get("OPERATION_CODE"),
                                                        amount(l.get("RATE_PER_UNIT"))
                                                                + " / "
                                                                + l.get("UNIT_OF_MEASURE")));
                        mgw.getOrDefault(id, List.of())
                                .forEach(
                                        m ->
                                                image.put(
                                                        "mgw." + m.get("DESIGNATION_CODE"),
                                                        amount(m.get("MIN_GUARANTEED_WAGE"))));
                        versions.add(version(s, image));
                    }
                    return Optional.of(
                            HistoryRecord.ofVersions(
                                    NwnpRateService.ENTITY,
                                    code,
                                    NwnpRateService.SCREEN,
                                    null,
                                    ids.stream().map(String::valueOf).toList(),
                                    versions));
                });
    }

    /**
     * M-06 norm set series of a depot, key = the depot's location code, at the depot (scope): each
     * set is a version with its Handling, Height and Lead norms, MLN, MLSW and above-norm slabs.
     */
    @Bean
    RecordHistorySource normHistory(NamedParameterJdbcTemplate named) {
        return RecordHistorySource.of(
                NormService.ENTITY,
                depot -> {
                    List<Map<String, Object>> sets =
                            named.queryForList(
                                    "SELECT n.norm_id, n.depot_location_id, n.version_no,"
                                        + " n.effective_from, n.effective_to, n.approval_id,"
                                        + " q.status, n.handling_norm, n.height_norm, n.lead_norm,"
                                        + " n.min_labour_number, n.min_labour_slab_weight,"
                                        + " n.mou_reference FROM labour_norm n JOIN org_location l"
                                        + " ON l.location_id = n.depot_location_id LEFT JOIN"
                                        + " approval_queue q ON q.approval_id = n.approval_id WHERE"
                                        + " l.location_code = :code AND n.engine_code ="
                                        + " 'DEPT_LABOUR' ORDER BY n.version_no",
                                    Map.of("code", depot));
                    if (sets.isEmpty()) {
                        return Optional.empty();
                    }
                    List<Long> ids = sets.stream().map(s -> id(s.get("NORM_ID"))).toList();
                    Map<Long, List<Map<String, Object>>> slabs =
                            bySet(
                                    named.queryForList(
                                            "SELECT norm_id, slab_table_ref,"
                                                    + " band_order, range_from, range_to,"
                                                    + " slab_rate_percent FROM"
                                                    + " labour_above_norm_slab WHERE norm_id IN"
                                                    + " (:ids) ORDER BY slab_table_ref, band_order",
                                            Map.of("ids", ids)),
                                    "NORM_ID");
                    List<Version> versions = new ArrayList<>();
                    for (Map<String, Object> s : sets) {
                        if (!approved(s.get("APPROVAL_ID"), s.get("STATUS"))) {
                            continue;
                        }
                        Map<String, String> image = new LinkedHashMap<>();
                        image.put("handling_norm", text(s.get("HANDLING_NORM")));
                        image.put("height_norm", text(s.get("HEIGHT_NORM")));
                        image.put("lead_norm", text(s.get("LEAD_NORM")));
                        image.put("min_labour_number", text(s.get("MIN_LABOUR_NUMBER")));
                        image.put(
                                "min_labour_slab_weight", amount(s.get("MIN_LABOUR_SLAB_WEIGHT")));
                        image.put("mou_reference", text(s.get("MOU_REFERENCE")));
                        slabs.getOrDefault(id(s.get("NORM_ID")), List.of())
                                .forEach(
                                        b ->
                                                image.put(
                                                        "slab."
                                                                + b.get("SLAB_TABLE_REF")
                                                                + "."
                                                                + b.get("BAND_ORDER"),
                                                        text(b.get("RANGE_FROM"))
                                                                + "-"
                                                                + Objects.toString(
                                                                        b.get("RANGE_TO"), "")
                                                                + " @ "
                                                                + amount(b.get("SLAB_RATE_PERCENT"))
                                                                + "%"));
                        versions.add(version(s, image));
                    }
                    return Optional.of(
                            HistoryRecord.ofVersions(
                                    NormService.ENTITY,
                                    depot,
                                    NormService.SCREEN,
                                    id(sets.get(0).get("DEPOT_LOCATION_ID")),
                                    ids.stream().map(String::valueOf).toList(),
                                    versions));
                });
    }

    /**
     * M-09 gang, key = the gang code, at its depot (scope): one row, its status and strength
     * changed in place. Formed under its code; ended and re-sized under its id; a member added or a
     * leader changed under {@code id:employee} or {@code id:ROLE} (GangService).
     */
    @Bean
    RecordHistorySource gangHistory(NamedParameterJdbcTemplate named) {
        return RecordHistorySource.of(
                GangService.ENTITY,
                code ->
                        first(
                                        named.queryForList(
                                                "SELECT g.gang_id, g.gang_name,"
                                                    + " g.depot_location_id, g.payroll_type,"
                                                    + " g.gang_type, g.gang_status,"
                                                    + " g.effective_from, (SELECT"
                                                    + " s.sanctioned_strength FROM"
                                                    + " labour_gang_strength s WHERE s.gang_id ="
                                                    + " g.gang_id AND s.effective_to IS NULL FETCH"
                                                    + " FIRST 1 ROWS ONLY) AS sanctioned_strength"
                                                    + " FROM labour_gang g WHERE g.gang_code ="
                                                    + " :code ORDER BY g.gang_id",
                                                Map.of("code", code)))
                                .map(
                                        g -> {
                                            String id = text(g.get("GANG_ID"));
                                            List<String> auditKeys = new ArrayList<>();
                                            auditKeys.add(code);
                                            auditKeys.add(id);
                                            for (GangRole role : GangRole.values()) {
                                                auditKeys.add(id + ":" + role.name());
                                            }
                                            named.queryForList(
                                                            "SELECT DISTINCT employee_id FROM"
                                                                    + " labour_gang_membership"
                                                                    + " WHERE gang_id = :gang",
                                                            Map.of("gang", id(g.get("GANG_ID"))),
                                                            Long.class)
                                                    .forEach(e -> auditKeys.add(id + ":" + e));
                                            Map<String, String> current = new LinkedHashMap<>();
                                            current.put("gang_name", text(g.get("GANG_NAME")));
                                            current.put("gang_type", text(g.get("GANG_TYPE")));
                                            current.put(
                                                    "payroll_type", text(g.get("PAYROLL_TYPE")));
                                            current.put("gang_status", text(g.get("GANG_STATUS")));
                                            current.put(
                                                    "sanctioned_strength",
                                                    text(g.get("SANCTIONED_STRENGTH")));
                                            return new HistoryRecord(
                                                    GangService.ENTITY,
                                                    code,
                                                    GangService.SCREEN,
                                                    id(g.get("DEPOT_LOCATION_ID")),
                                                    auditKeys,
                                                    false,
                                                    List.of(),
                                                    current,
                                                    date(g.get("EFFECTIVE_FROM")));
                                        }));
    }

    private static boolean approved(Object approvalId, Object status) {
        return approvalId == null || "APPROVED".equals(status);
    }

    private static Version version(Map<String, Object> row, Map<String, String> image) {
        return new Version(
                ((Number) row.get("VERSION_NO")).intValue(),
                date(row.get("EFFECTIVE_FROM")),
                date(row.get("EFFECTIVE_TO")),
                id(row.get("APPROVAL_ID")),
                image);
    }

    private static Map<Long, List<Map<String, Object>>> bySet(
            List<Map<String, Object>> rows, String column) {
        return rows.stream()
                .collect(
                        Collectors.groupingBy(
                                (Function<Map<String, Object>, Long>) r -> id(r.get(column))));
    }

    private static Optional<Map<String, Object>> first(List<Map<String, Object>> rows) {
        return rows.stream().findFirst();
    }

    private static Long id(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String amount(Object value) {
        return value == null ? null : new BigDecimal(value.toString()).toPlainString();
    }

    /** A DATE column as JDBC hands it over (H2 a Timestamp, Oracle a Date). */
    private static LocalDate date(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate d) {
            return d;
        }
        return new java.sql.Date(((java.util.Date) value).getTime()).toLocalDate();
    }
}
