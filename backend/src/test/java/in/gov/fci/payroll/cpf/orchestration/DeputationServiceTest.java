package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.cpf.business.CpfErrorCode;
import in.gov.fci.payroll.cpf.business.model.CpfDeputationRecovery;
import in.gov.fci.payroll.cpf.business.model.CpfDeputationRecovery.EntryType;
import in.gov.fci.payroll.cpf.business.model.CpfDeputationRecovery.Status;
import in.gov.fci.payroll.cpf.business.model.PfSanction;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * The AGM (CPF)'s decision on an I-09 entry (plan 07 phase 07g; SRS-6-28, SRS-6-29): another
 * entity's decision is ignored, a decided entry is not decided again, a rejection stays rejected,
 * an excess is recorded, an instalment that the approvals since its save have overtaken is refused
 * at approval. The flows are DeputationIT.
 */
class DeputationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T05:00:00Z");

    private final CpfRepositories.DeputationEntries entries =
            mock(CpfRepositories.DeputationEntries.class);
    private final CpfRepositories.Sanctions sanctions = mock(CpfRepositories.Sanctions.class);
    private final CpfLedgerService ledger = mock(CpfLedgerService.class);
    private final AuditService audit = mock(AuditService.class);
    private final DeputationService service =
            new DeputationService(
                    mock(CpfRepositories.BorrowingOrgs.class),
                    entries,
                    sanctions,
                    ledger,
                    mock(CpfMembers.class),
                    mock(EmployeeDirectory.class),
                    mock(DocumentStore.class),
                    mock(MakerChecker.class),
                    mock(Authorization.class),
                    audit,
                    mock(JdbcTemplate.class),
                    JsonMapper.builder().build(),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private static CpfDeputationRecovery pending(EntryType type, String instalment) {
        CpfDeputationRecovery r =
                CpfDeputationRecovery.keyed(
                        7L,
                        1L,
                        type,
                        type == EntryType.RECOVERY_INSTALMENT ? 5L : null,
                        LocalDate.of(2026, 7, 1),
                        null,
                        "202608",
                        new BigDecimal(instalment),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        ReflectionTestUtils.setField(r, "id", 3L);
        r.submitted(81L);
        return r;
    }

    private static ApprovalDecided decided(String entity, ApprovalStatus outcome) {
        return new ApprovalDecided(
                81L, "I-09", entity, "3", outcome, "IT reason", "{}", "agm", NOW);
    }

    @Test
    void SRS_6_27_anotherEntitysDecisionAndADecidedEntryAreLeftAlone() {
        service.on(decided("loan_request", ApprovalStatus.APPROVED));
        verifyNoInteractions(entries);
        CpfDeputationRecovery done = pending(EntryType.EXCESS_REFUND, "10");
        done.approved(null, null);
        when(entries.findByApprovalId(81L)).thenReturn(List.of(done));
        service.on(decided(DeputationService.ENTITY, ApprovalStatus.REJECTED));
        assertThat(done.status()).isEqualTo(Status.APPROVED);
        verify(entries, never()).save(any());
    }

    @Test
    void SRS_6_27_aRejectedEntryStaysRejectedAndAnExcessIsRecorded() {
        CpfDeputationRecovery rejected = pending(EntryType.EXCESS_REFUND, "10");
        when(entries.findByApprovalId(81L)).thenReturn(List.of(rejected));
        service.on(decided(DeputationService.ENTITY, ApprovalStatus.REJECTED));
        assertThat(rejected.status()).isEqualTo(Status.REJECTED);

        CpfDeputationRecovery excess = pending(EntryType.EXCESS_REFUND, "10");
        when(entries.findByApprovalId(81L)).thenReturn(List.of(excess));
        service.on(decided(DeputationService.ENTITY, ApprovalStatus.APPROVED));
        assertThat(excess.status()).isEqualTo(Status.APPROVED);
        verifyNoInteractions(ledger);
    }

    @Test
    void SRS_6_29_anInstalmentOvertakenSinceItsSaveIsRefusedAtApproval() {
        CpfDeputationRecovery instalment = pending(EntryType.RECOVERY_INSTALMENT, "6000");
        PfSanction advance = mock(PfSanction.class);
        when(advance.getSanctionId()).thenReturn(5L);
        when(advance.getEmployeeId()).thenReturn(7L);
        when(advance.getSanctionedAmount()).thenReturn(new BigDecimal("10000"));
        when(sanctions.findById(5L)).thenReturn(Optional.of(advance));
        CpfDeputationRecovery earlier = pending(EntryType.RECOVERY_INSTALMENT, "5000");
        ReflectionTestUtils.setField(earlier, "id", 2L);
        earlier.approved(new BigDecimal("10000"), new BigDecimal("5000"));
        when(entries.findByApprovalId(81L)).thenReturn(List.of(instalment));
        when(entries.findByEmployeeIdOrderByPayrollYymmDescIdDesc(7L))
                .thenReturn(List.of(instalment, earlier));
        assertThatThrownBy(
                        () ->
                                service.on(
                                        decided(DeputationService.ENTITY, ApprovalStatus.APPROVED)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CpfErrorCode.OVER_REMITTANCE));
        assertThat(instalment.status()).isEqualTo(Status.PENDING_CHECKER);
    }
}
