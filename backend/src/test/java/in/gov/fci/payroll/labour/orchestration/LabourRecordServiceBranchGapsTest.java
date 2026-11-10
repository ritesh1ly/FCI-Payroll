package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.LabourPersons;
import in.gov.fci.payroll.labour.orchestration.LabourRecordService.Document;
import in.gov.fci.payroll.labour.orchestration.LabourRecordService.Reinstatement;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-02 reinstatement (NWNP Reinstate Details, BR-FNF-014; D22-7): an order without a reference
 * number is attached with none, and the Payroll Checker's rejection with a reason audits that
 * reason.
 */
class LabourRecordServiceBranchGapsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final LabourPersons persons = mock(LabourPersons.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccessContext context = mock(AccessContext.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final LabourRecordService service =
            new LabourRecordService(
                    jdbc,
                    mock(Authorization.class),
                    org,
                    approvals,
                    persons,
                    documents,
                    parameters,
                    audit,
                    JsonMapper.builder().build());

    @BeforeEach
    void wire() {
        when(parameters.today()).thenReturn(TODAY);
        Map<String, Object> labour = new HashMap<>();
        labour.put("LABOUR_NUMBER", "L30704");
        labour.put("DEPOT_LOCATION_ID", 7L);
        labour.put("LABOUR_STATUS", "SEPARATED");
        labour.put("EFFECTIVE_TO", Date.valueOf(TODAY.minusDays(10)));
        when(jdbc.queryForList(contains("FROM labour_profile p"), eq(33L)))
                .thenReturn(List.of(labour));
        OrgLocation office = mock(OrgLocation.class);
        when(office.getId()).thenReturn(3L);
        when(org.cycleUnitOf(7L)).thenReturn(Optional.of(office));
    }

    @Test
    void reinstateDetails_anOrderWithoutAReferenceNumberIsAttachedWithNone() {
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(61L, ApprovalStatus.PENDING_APPROVAL, null));
        SubmittedApproval item =
                service.reinstate(
                        context,
                        33L,
                        new Reinstatement(TODAY, null, TODAY, "AM", "Appellate order"),
                        new Document("order.pdf", new byte[] {1}));
        assertThat(item.id()).isEqualTo(61L);
        ArgumentCaptor<DocumentStore.NewAttachment> attached =
                ArgumentCaptor.forClass(DocumentStore.NewAttachment.class);
        verify(documents).attach(any(), attached.capture());
        assertThat(attached.getValue().referenceNumber()).isNull();
        assertThat(attached.getValue().issuingAuthority()).isEqualTo("AM");
    }

    @Test
    void reinstateDetails_aRejectionWithTheCheckersReasonAuditsThatReason() {
        service.on(
                new ApprovalDecided(
                        51L,
                        "M-02",
                        LabourRecordService.REINSTATEMENT,
                        "33",
                        ApprovalStatus.REJECTED,
                        "Order not attested",
                        "{}",
                        "ddo.delhi",
                        Instant.now(),
                        null));
        verify(persons, never()).reinstate(anyLong(), any(), any());
        verify(audit)
                .record(
                        eq("ddo.delhi"),
                        argThat(
                                e ->
                                        "SEPARATED".equals(e.newValue())
                                                && "Order not attested".equals(e.reason())));
    }
}
