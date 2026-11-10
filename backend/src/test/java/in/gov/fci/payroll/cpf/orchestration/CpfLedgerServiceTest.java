package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.cpf.business.LedgerBalances;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader.MemberStatus;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader.OpeningSource;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SRS-6-9: when the ex-member stop rule is not configured, the separation is still followed (the
 * member becomes an ex-member) and the missing stop is raised on X-05; nothing is guessed and the
 * separation's own approval is not failed.
 */
class CpfLedgerServiceTest {

    @Test
    void SRS_6_9_aStopRuleNotConfiguredIsRaisedOnX05AndNoStopIsGuessed() {
        CpfRepositories.Headers headers = mock(CpfRepositories.Headers.class);
        CpfMembers members = mock(CpfMembers.class);
        RuleEngine engine = mock(RuleEngine.class);
        CpfExceptions exceptions = mock(CpfExceptions.class);
        CpfLedgerService service =
                new CpfLedgerService(
                        headers,
                        mock(CpfRepositories.Details.class),
                        mock(CpfRepositories.MemberIds.class),
                        members,
                        engine,
                        exceptions,
                        mock(AuditService.class),
                        Clock.fixed(Instant.parse("2026-10-23T05:00:00Z"), ZoneOffset.UTC));
        when(members.require(7L, "checker"))
                .thenReturn(new CpfMembers.Member(7L, "104882", "CPF/1", 11L, 3L));
        EmployeePfHeader header =
                EmployeePfHeader.open(
                        7L,
                        "2026-27",
                        "CPF",
                        11L,
                        null,
                        LedgerBalances.ZERO,
                        OpeningSource.SYSTEM_COMPUTED,
                        MemberStatus.ACTIVE,
                        false);
        when(headers.idsOf(7L, "2026-27", "CPF")).thenReturn(List.of(1L));
        when(headers.findByEmployeeIdAndFundTypeOrderByFinYearDescPfHeaderIdDesc(7L, "CPF"))
                .thenReturn(List.of(header));
        when(engine.snapshot(any())).thenThrow(new FciException(ApiErrorCode.RULE_NOT_CONFIGURED));

        service.on(
                new EmployeeEvents.EmployeeLifecycleApplied(
                        7L, 1L, "SEPARATION", LocalDate.of(2026, 9, 30), null, "checker"));

        assertThat(header.getMemberStatus()).isEqualTo("EX_MEMBER");
        assertThat(header.getInterestEndDate()).isNull();
        ArgumentCaptor<ExceptionWorklist.NewEntry> raised =
                ArgumentCaptor.forClass(ExceptionWorklist.NewEntry.class);
        verify(exceptions).raise(eq("checker"), raised.capture());
        assertThat(raised.getValue().exceptionCode()).isEqualTo("CPF_EVENT_NOT_FOLLOWED");
        assertThat(raised.getValue().fieldName()).isEqualTo("interest_end_date");
        assertThat(raised.getValue().correctionScreen()).isEqualTo("M-19");
        verify(headers).save(header);
    }
}
