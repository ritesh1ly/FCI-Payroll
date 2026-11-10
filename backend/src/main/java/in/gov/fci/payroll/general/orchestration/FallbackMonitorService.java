package in.gov.fci.payroll.general.orchestration;

import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-only "HRMS Fallback Uploads" list of X-03 (SRS-INT-22, BR-INT-011; X-03 field table;
 * US-GAP-63): per cycle and office, each fallback upload Headquarters enabled, the status of its
 * enablement and of the office's latest upload (its approval), and that upload's reconciliation
 * with HRMS (SRS-G-47). Over the session's offices; paged. The uploads themselves are worked on
 * X-07.
 */
@Service
public class FallbackMonitorService {

    static final String SCREEN = "X-03";

    /** One office of one enablement. */
    public record Row(
            Long enablementId,
            String reference,
            String type,
            String payrollYymm,
            String officeCode,
            String officeName,
            String enablementStatus,
            String batchNo,
            String uploadStatus,
            String reconStatus) {}

    private static final String FROM =
            " FROM hrms_fallback_enablement e JOIN hrms_fallback_enable_scope s ON s.enablement_id"
                + " = e.enablement_id JOIN org_location l ON l.location_id = s.org_unit_id LEFT"
                + " JOIN hrms_fallback_upload u ON u.fallback_upload_id = (SELECT"
                + " MAX(x.fallback_upload_id) FROM hrms_fallback_upload x WHERE x.enablement_id ="
                + " e.enablement_id AND x.org_unit_id = s.org_unit_id) WHERE s.org_unit_id IN"
                + " (:scope)";

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;

    public FallbackMonitorService(NamedParameterJdbcTemplate named, Authorization policy) {
        this.named = named;
        this.policy = policy;
    }

    /** X-03: the enabled uploads, newest cycle first; paged. */
    @Transactional(readOnly = true)
    public Paged<Row> page(AccessContext context, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p = new MapSqlParameterSource("scope", Scopes.of(context));
        Long total = named.queryForObject("SELECT COUNT(*)" + FROM, p, Long.class);
        List<Row> rows =
                named.query(
                        "SELECT e.enablement_id, e.enablement_ref, e.fallback_type, e.payroll_yymm,"
                                + " l.location_code, l.location_name, e.enablement_status,"
                                + " u.batch_no, u.upload_status, u.recon_status"
                                + FROM
                                + " ORDER BY e.payroll_yymm DESC, e.enablement_id DESC,"
                                + " l.location_code OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                        p.addValue("offset", (long) spec.page() * spec.size())
                                .addValue("size", spec.size()),
                        (rs, i) ->
                                new Row(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getString(7),
                                        rs.getString(8),
                                        rs.getString(9),
                                        rs.getString(10)));
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }
}
