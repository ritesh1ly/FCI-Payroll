package in.gov.fci.payroll.eseva.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E-02 eSeva submission log (SRS-17-18): a member without a last name (none or blank) is named by
 * the first; a member of no office shows none; a submission not routed to an approval shows none;
 * blank filters are none.
 */
class SubmissionLogPageTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final SubmissionLogService service =
            new SubmissionLogService(
                    db.jdbc,
                    db.named,
                    mock(Authorization.class),
                    mock(MemberNotifications.class),
                    Clock.systemUTC());

    private static Map<String, Object> submission(
            long id, String last, String office, Long approval) {
        return row(
                "submission_id",
                id,
                "emp_num",
                "E" + id,
                "first_name",
                "Asha",
                "last_name",
                last,
                "location_code",
                office,
                "location_name",
                office == null ? null : "Delhi",
                "submission_type",
                "TAX_DECLARATION",
                "reference_entity_name",
                "it_declaration",
                "reference_entity_id",
                7L,
                "submitted_value",
                "80C",
                "submitted_at",
                Timestamp.from(Instant.parse("2026-10-29T06:00:00Z")),
                "submission_status",
                "SUBMITTED",
                "routed_to_approval_id",
                approval);
    }

    @Test
    void SRS_17_18_theLogNamesMembersOfficesAndApprovalsAsHeld() {
        db.rows(
                "ORDER BY s.submission_id DESC OFFSET",
                submission(1L, null, null, null),
                submission(2L, " ", "DH001", 41L),
                submission(3L, "Rao", "DH001", null));
        List<SubmissionLogService.Row> rows =
                service.page(
                                mock(AccessContext.class),
                                new SubmissionLogService.Filter(" ", null, " "),
                                new PageSpec(0, 20))
                        .items();
        assertThat(rows)
                .extracting(SubmissionLogService.Row::member)
                .containsExactly("Asha", "Asha", "Asha Rao");
        assertThat(rows)
                .extracting(SubmissionLogService.Row::office)
                .containsExactly(null, "DH001 - Delhi", "DH001 - Delhi");
        assertThat(rows)
                .extracting(SubmissionLogService.Row::approvalId)
                .containsExactly(null, 41L, null);
        assertThat(rows.get(0).reference()).isEqualTo("it_declaration #7");
    }
}
