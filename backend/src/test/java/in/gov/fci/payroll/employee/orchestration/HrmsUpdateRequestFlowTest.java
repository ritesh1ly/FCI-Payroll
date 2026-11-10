package in.gov.fci.payroll.employee.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.employee.api.HrmsRecords;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * SRS-1-70 (plan 16 D16-5): the member's HRMS update request with HRMS readable: a pay-effect field
 * needs its effective date; a fetched value that differs opens the I-21 task under the HRMS
 * reference, or the request number when none was given, and a task HRMS had already opened is
 * linked; a member without a last name is listed by the first name.
 */
class HrmsUpdateRequestFlowTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final HrmsChangeService changes = mock(HrmsChangeService.class);
    private final HrmsChangeApplier applier = mock(HrmsChangeApplier.class);
    private final HrmsRecords records = mock(HrmsRecords.class);
    private final AccessContext member =
            new AccessContext(
                    2L,
                    "member@x",
                    "Member",
                    "s",
                    "E",
                    300L,
                    9L,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of());

    @SuppressWarnings("unchecked")
    private final HrmsUpdateRequestService service = service(mock(ObjectProvider.class));

    private HrmsUpdateRequestService service(ObjectProvider<HrmsRecords> hrms) {
        when(hrms.getIfAvailable()).thenReturn(records);
        return new HrmsUpdateRequestService(
                db.jdbc,
                db.named,
                mock(OrgDirectory.class),
                mock(Authorization.class),
                mock(AuditService.class),
                changes,
                applier,
                hrms,
                mock(ApplicationEventPublisher.class),
                Clock.fixed(Instant.parse("2026-10-26T06:00:00Z"), ZoneOffset.UTC));
    }

    HrmsUpdateRequestFlowTest() {
        Map<String, Object> field =
                Map.of(
                        "PAY_EFFECT_FIELD_ID",
                        3L,
                        "FIELD_CODE",
                        "EPS_NUMBER",
                        "HAS_PAY_EFFECT",
                        "Y");
        when(changes.field("EPS_NUMBER")).thenReturn(field);
        when(applier.current(any(), anyLong())).thenReturn("EPS/OLD");
        when(records.current(9L, "EPS_NUMBER"))
                .thenReturn(Optional.of(new HrmsRecords.Fetched("EPS/NEW", FROM)));
        db.rows(
                "WHERE r.hrms_update_request_id = :id",
                row(
                        "hrms_update_request_id", 1000L,
                        "request_no", "HUR-2026-1000",
                        "emp_num", "104882",
                        "first_name", "Asha",
                        "last_name", null,
                        "field_code", "EPS_NUMBER",
                        "field_label", "EPS number",
                        "raised_channel", "ESEVA",
                        "raised_by", "member@x",
                        "raised_at", Timestamp.from(Instant.parse("2026-10-26T06:00:00Z")),
                        "hrms_reference", null,
                        "claimed_effective_date", null,
                        "fetch_result", "DIFFERS",
                        "request_status", "LINKED_EXISTING",
                        "closure_message_code", null,
                        "task_no", "T-1"));
    }

    @Test
    void SRS_1_70_aPayEffectFieldNeedsItsEffectiveDate() {
        assertThatThrownBy(
                        () ->
                                service.raiseMine(
                                        member,
                                        new HrmsUpdateRequestService.NewRequest(
                                                null, "EPS_NUMBER", null, null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(ApiErrorCode.I21_REQUEST);
                            assertThat(ex.getMessageArgs()).containsExactly("NO_DATE");
                        });
    }

    @Test
    void SRS_1_70_aDifferingValueOpensTheTaskUnderTheRequestNumberAndATaskAlreadyOpenIsLinked() {
        when(changes.receive(any())).thenReturn(new HrmsChanges.Received(7L, "T-7", "OPEN", true));
        HrmsUpdateRequestService.RequestView view =
                service.raiseMine(
                        member,
                        new HrmsUpdateRequestService.NewRequest(null, "EPS_NUMBER", null, FROM));
        ArgumentCaptor<HrmsChanges.Change> change = ArgumentCaptor.captor();
        verify(changes).receive(change.capture());
        assertThat(change.getValue().hrmsRequestId()).startsWith("HUR-");
        assertThat(change.getValue().newValue()).isEqualTo("EPS/NEW");
        assertThat(db.updates("SET request_status = ?, fetch_result"))
                .singleElement()
                .satisfies(u -> assertThat(u.args().get(0)).isEqualTo("LINKED_EXISTING"));
        // The member without a last name is listed by the first name.
        assertThat(view.employee()).isEqualTo("Asha");
    }

    @Test
    void SRS_1_70_aDifferingValueOpensTheTaskUnderTheHrmsReference() {
        when(changes.receive(any())).thenReturn(new HrmsChanges.Received(8L, "T-8", "OPEN", false));
        service.raiseMine(
                member,
                new HrmsUpdateRequestService.NewRequest(null, "EPS_NUMBER", " HR/2026/41 ", FROM));
        ArgumentCaptor<HrmsChanges.Change> change = ArgumentCaptor.captor();
        verify(changes).receive(change.capture());
        assertThat(change.getValue().hrmsRequestId()).isEqualTo("HR/2026/41");
        assertThat(db.updates("SET request_status = ?, fetch_result"))
                .singleElement()
                .satisfies(u -> assertThat(u.args().get(0)).isEqualTo("TASK_OPENED"));
    }
}
