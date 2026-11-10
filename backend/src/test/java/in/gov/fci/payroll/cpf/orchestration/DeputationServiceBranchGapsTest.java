package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.cpf.business.model.CpfDeputationRecovery;
import in.gov.fci.payroll.cpf.business.model.CpfDeputationRecovery.EntryType;
import in.gov.fci.payroll.cpf.business.model.PfSanction;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * I-09 reading at its edges (SRS-6-27, SRS-6-28, SRS l.2167): a month counts as received only once
 * its receipt is approved (one waiting for the AGM (CPF) is still missing); an instalment shows its
 * advance by the sanction number, or by the request id while the advance has none.
 */
class DeputationServiceBranchGapsTest {

    private static final Instant NOW = Instant.parse("2026-10-28T06:00:00Z");

    private final CpfRepositories.DeputationEntries entries =
            mock(CpfRepositories.DeputationEntries.class);
    private final CpfRepositories.Sanctions sanctions = mock(CpfRepositories.Sanctions.class);
    private final EmployeeDirectory directory = mock(EmployeeDirectory.class);
    private final Authorization policy = mock(Authorization.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AccessContext context = mock(AccessContext.class);
    private final DeputationService service =
            new DeputationService(
                    mock(CpfRepositories.BorrowingOrgs.class),
                    entries,
                    sanctions,
                    mock(CpfLedgerService.class),
                    mock(CpfMembers.class),
                    directory,
                    mock(DocumentStore.class),
                    mock(MakerChecker.class),
                    policy,
                    mock(AuditService.class),
                    jdbc,
                    JsonMapper.builder().build(),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private static CpfDeputationRecovery entry(
            long id, EntryType type, Long sanction, String yymm) {
        CpfDeputationRecovery r =
                CpfDeputationRecovery.keyed(
                        9L,
                        4L,
                        type,
                        sanction,
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 9, 30),
                        yymm,
                        type == EntryType.RECOVERY_INSTALMENT ? new BigDecimal("1000") : null,
                        type == EntryType.CONTRIBUTION_RECEIPT
                                ? new BigDecimal("1800")
                                : BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    private static PfSanction advance(long id, String number) {
        PfSanction s = mock(PfSanction.class);
        when(s.getSanctionId()).thenReturn(id);
        when(s.getSystemSanctionNumber()).thenReturn(number);
        return s;
    }

    /** SRS l.2167: an instalment names its advance by number, by id while it has none. */
    @Test
    void SRS_6_28_anInstalmentNamesItsAdvanceByNumberOrByIdWhileItHasNone() {
        when(context.dataScope()).thenReturn(Set.of(10L));
        when(entries.inScope(Set.of(10L)))
                .thenReturn(
                        List.of(
                                entry(1L, EntryType.RECOVERY_INSTALMENT, 5L, "202608"),
                                entry(2L, EntryType.RECOVERY_INSTALMENT, 6L, "202608"),
                                entry(3L, EntryType.CONTRIBUTION_RECEIPT, null, "202608")));
        List<PfSanction> advances = List.of(advance(5L, null), advance(6L, "10/2026/NA11/00006"));
        when(sanctions.findBySanctionIdIn(any())).thenReturn(advances);
        assertThat(service.register(context))
                .extracting(DeputationService.EntryView::sanctionNumber)
                .containsExactly("5", "10/2026/NA11/00006", null);
    }

    /** SRS-6-27: a receipt waiting for the AGM (CPF) leaves its month without a receipt. */
    @Test
    @SuppressWarnings("unchecked")
    void SRS_6_27_aMonthIsReceivedOnlyOnceItsReceiptIsApproved() {
        when(directory.memberByEis("E1"))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.Member(9L, "E1", "M", 10L, "IN_SERVICE")));
        when(policy.canAt(any(), anyString(), anyString(), anyLong())).thenReturn(true);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(9L)))
                .thenAnswer(
                        a -> {
                            ResultSet rs = mock(ResultSet.class);
                            when(rs.getDate(1)).thenReturn(Date.valueOf("2026-07-01"));
                            when(rs.getDate(2)).thenReturn(Date.valueOf("2026-09-30"));
                            return List.of(((RowMapper<Object>) a.getArgument(1)).mapRow(rs, 0));
                        });
        CpfDeputationRecovery july = entry(1L, EntryType.CONTRIBUTION_RECEIPT, null, "202607");
        july.approved(null, null);
        CpfDeputationRecovery august = entry(2L, EntryType.CONTRIBUTION_RECEIPT, null, "202608");
        august.submitted(81L);
        when(entries.findByEmployeeIdOrderByPayrollYymmDescIdDesc(9L))
                .thenReturn(List.of(august, july));

        DeputationService.Member m = service.member(context, "E1");

        assertThat(m.monthsWithoutReceipt()).containsExactly("202608", "202609");
        assertThat(m.entries())
                .extracting(DeputationService.EntryView::status)
                .containsExactly("PENDING_CHECKER", "APPROVED");
    }
}
