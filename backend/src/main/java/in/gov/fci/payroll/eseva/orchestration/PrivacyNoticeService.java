package in.gov.fci.payroll.eseva.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The eSeva privacy notice (plan 14 phase 14a; SRS-SEC-9, BR-SEC-005, KB ES-9; D14-3): shown at the
 * member's first eSeva sign-in until the member acknowledges it, once per notice version. The text
 * is the label {@code eseva.privacy.text} (English and Hindi), describing the personal data held
 * and its purpose, and saying that HRMS data is corrected in HRMS (SRS-G-45). No DDL table holds
 * the acknowledgement: it is an audit entry (entity {@value #ENTITY}, key the member), read back
 * here.
 */
@Service
@Loggable
public class PrivacyNoticeService {

    static final String ENTITY = "eseva_privacy_notice";

    /** The notice's version: a new text raises it, and every member acknowledges again. */
    static final String VERSION = "1";

    /** Whether the member has acknowledged the notice in force. */
    public record Notice(String version, boolean acknowledged) {}

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final AuditService audit;

    public PrivacyNoticeService(JdbcTemplate jdbc, Authorization policy, AuditService audit) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.audit = audit;
    }

    /** E-01: the notice for the signed-in member. */
    @Transactional(readOnly = true)
    public Notice mine(AccessContext context) {
        return new Notice(VERSION, acknowledged(self(context)));
    }

    /** E-01: the member acknowledges the notice in force (once; again is harmless). */
    @Transactional
    public Notice acknowledge(AccessContext context) {
        long me = self(context);
        if (!acknowledged(me)) {
            audit.record(
                    context.loginId(),
                    AuditService.Entry.change(
                                    ENTITY,
                                    String.valueOf(me),
                                    "version",
                                    null,
                                    VERSION,
                                    "Privacy notice acknowledged at eSeva sign-in (SRS-SEC-9)")
                            .module("E-01"));
        }
        return new Notice(VERSION, true);
    }

    private boolean acknowledged(long employeeId) {
        Long n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_event WHERE entity_name = ? AND entity_key = ?"
                                + " AND field_name = 'version' AND new_value = ?",
                        Long.class,
                        ENTITY,
                        String.valueOf(employeeId),
                        VERSION);
        return RowCounts.of(n) > 0;
    }

    /** The signed-in member: eSeva is the member's own record only (SRS-17-9). */
    private long self(AccessContext context) {
        policy.require(context, "E-01", "SELF");
        if (context.employeeId() == null) {
            throw new FciException(
                    ApiErrorCode.PERMISSION_DENIED, new Object[] {"E-01", "SELF"}, List.of(), null);
        }
        return context.employeeId();
    }
}
