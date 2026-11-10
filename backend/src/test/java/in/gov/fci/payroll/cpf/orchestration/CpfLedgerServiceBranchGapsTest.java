package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.cpf.business.CpfErrorCode;
import in.gov.fci.payroll.cpf.business.InterestRateRules.FinYear;
import in.gov.fci.payroll.cpf.business.LedgerBalances;
import in.gov.fci.payroll.cpf.business.model.EmployeePfDetail;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader.MemberStatus;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader.OpeningSource;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The CPF ledger at its edges (SRS-6-52, SRS-6-57, BR-CPF-025): a new year opens from the latest
 * earlier ledger that was not closed by a parent-zone change (its balance moved to the new Member
 * ID); a ledger closed by a parent-zone change takes no posting.
 */
class CpfLedgerServiceBranchGapsTest {

    private final CpfRepositories.Headers headers = mock(CpfRepositories.Headers.class);
    private final CpfRepositories.Details details = mock(CpfRepositories.Details.class);
    private final CpfMembers members = mock(CpfMembers.class);
    private final CpfLedgerService service =
            new CpfLedgerService(
                    headers,
                    details,
                    mock(CpfRepositories.MemberIds.class),
                    members,
                    mock(RuleEngine.class),
                    mock(CpfExceptions.class),
                    mock(AuditService.class),
                    Clock.fixed(Instant.parse("2026-10-23T05:00:00Z"), ZoneOffset.UTC));
    private final CpfMembers.Member member = new CpfMembers.Member(7L, "104882", "CPF/1", 11L, 3L);

    private static EmployeePfHeader header(String year, MemberStatus status, String employee) {
        return EmployeePfHeader.open(
                7L,
                year,
                "CPF",
                11L,
                null,
                new LedgerBalances(
                        new BigDecimal(employee), new BigDecimal("500"), BigDecimal.ZERO),
                OpeningSource.SYSTEM_COMPUTED,
                status,
                false);
    }

    /** SRS-6-52: a ledger closed by a parent-zone change is not carried into the new year. */
    @Test
    void SRS_6_52_aNewYearOpensFromTheLatestLedgerNotClosedByAZoneChange() {
        EmployeePfHeader moved = header("2025-26", MemberStatus.CLOSED_ZONE_CHANGE, "9000");
        EmployeePfHeader kept = header("2024-25", MemberStatus.ACTIVE, "1000");
        when(headers.idsOf(7L, "2026-27", "CPF")).thenReturn(List.of());
        when(headers.findByEmployeeIdAndFundTypeOrderByFinYearDescPfHeaderIdDesc(7L, "CPF"))
                .thenReturn(List.of(moved, kept));
        when(headers.save(any())).thenAnswer(i -> i.getArgument(0));

        service.ledger(member, FinYear.containing(LocalDate.of(2026, 10, 1)));

        ArgumentCaptor<EmployeePfHeader> opened = ArgumentCaptor.captor();
        verify(headers).save(opened.capture());
        assertThat(opened.getValue().getFinYear()).isEqualTo("2026-27");
        assertThat(opened.getValue().opening()).isEqualTo(kept.closing());
        assertThat(opened.getValue().getMemberStatus()).isEqualTo("ACTIVE");
    }

    /** SRS-6-57 / SRS-6-52: a ledger closed by a parent-zone change takes no posting. */
    @Test
    void SRS_6_57_aLedgerClosedByAZoneChangeTakesNoPosting() {
        EmployeePfHeader closed = header("2026-27", MemberStatus.CLOSED_ZONE_CHANGE, "9000");
        when(members.require(7L, "checker")).thenReturn(member);
        when(headers.idsOf(7L, "2026-27", "CPF")).thenReturn(List.of(1L));
        when(headers.lockById(1L)).thenReturn(Optional.of(closed));
        assertThatThrownBy(
                        () ->
                                service.post(
                                        new CpfLedgerService.Posting(
                                                7L,
                                                LocalDate.of(2026, 10, 1),
                                                EmployeePfDetail.PaymentType.SALARY,
                                                new LedgerBalances(
                                                        new BigDecimal("1800"),
                                                        new BigDecimal("1800"),
                                                        BigDecimal.ZERO),
                                                null,
                                                null,
                                                null,
                                                null,
                                                "checker",
                                                "October salary")))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(CpfErrorCode.LEDGER_CLOSED);
                            assertThat(e.getMessageArgs()).containsExactly("CLOSED_ZONE_CHANGE");
                        });
        verify(headers, never()).save(any());
        verify(details, never()).save(any());
    }
}
