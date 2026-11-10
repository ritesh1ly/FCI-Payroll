package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.configuration.api.MasterLabels;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.OperationMaster;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * M-27 Operation Master list and approval edge cases (SRS-G-41, SRS-G-39, SRS-G-38): a search of
 * only spaces lists every operation; without the M-26 names the search reads code and standard name
 * only and no local name is shown; a local name equal to the standard name is not shown twice; an
 * approved successor end-dates the version in force and the audit carries that version as before.
 */
class OperationServiceBranchGapsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 29);

    private final ConfigurationRepositories.Operations operations =
            mock(ConfigurationRepositories.Operations.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final SystemParameters settings = mock(SystemParameters.class);
    private final MasterAudit audit = mock(MasterAudit.class);
    private final MasterLabels names = mock(MasterLabels.class);
    private final AccessContext context = mock(AccessContext.class);
    private final Map<Long, ApprovalStatus> decided = new HashMap<>();

    OperationServiceBranchGapsTest() {
        when(settings.today()).thenReturn(DAY);
        when(context.locationId()).thenReturn(41L);
        when(statuses.of(any())).thenReturn(decided);
        when(operations.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private OperationService service(MasterLabels labels) {
        return new OperationService(
                operations,
                mock(ConfigurationRepositories.PayComponents.class),
                mock(Circulars.class),
                statuses,
                mock(MakerChecker.class),
                mock(Authorization.class),
                settings,
                audit,
                labels);
    }

    private OperationMaster version(String code, String name, int no, Long approval) {
        OperationMaster o = mock(OperationMaster.class);
        when(o.getOperationCode()).thenReturn(code);
        when(o.getOperationName()).thenReturn(name);
        when(o.getVersionNo()).thenReturn(no);
        when(o.getApprovalId()).thenReturn(approval);
        when(o.getEffectiveFrom()).thenReturn(LocalDate.of(2026, 4, no));
        when(o.coversDay(any())).thenCallRealMethod();
        decided.put(approval, ApprovalStatus.APPROVED);
        return o;
    }

    @Test
    void SRS_G_41_withoutTheM26NamesTheSearchReadsCodeAndStandardNameOnly() {
        OperationMaster wagon = version("OP1", "Wagon to Platform", 1, 11L);
        OperationMaster truck = version("OP2", "Truck to Stack", 1, 12L);
        when(operations.findAllByOrderByOperationCodeAscVersionNoDesc())
                .thenReturn(List.of(wagon, truck));
        OperationService plain = service(null);
        assertThat(plain.list(context, "wagon", "HI"))
                .extracting(OperationService.OperationRow::code)
                .containsExactly("OP1");
        assertThat(plain.list(context, "op2", "HI"))
                .singleElement()
                .satisfies(r -> assertThat(r.shownName()).isNull());
        // A search of only spaces is no search: every operation is listed.
        assertThat(plain.list(context, "   ", "HI")).hasSize(2);
        assertThat(plain.list(context)).hasSize(2);
    }

    @Test
    void SRS_G_39_aLocalNameEqualToTheStandardNameIsNotShownTwice() {
        OperationMaster wagon = version("OP1", "Wagon to Platform", 1, 11L);
        OperationMaster truck = version("OP2", "Truck to Stack", 1, 12L);
        when(operations.findAllByOrderByOperationCodeAscVersionNoDesc())
                .thenReturn(List.of(wagon, truck));
        when(names.shown(MasterLabels.OPERATION, "HI", 41L))
                .thenReturn(Map.of("OP1", "Wagon to Platform", "OP2", "Truck se Stack"));
        when(names.matching(eq(MasterLabels.OPERATION), anyString())).thenReturn(Set.of());
        List<OperationService.OperationRow> rows = service(names).list(context, null, "HI");
        assertThat(rows)
                .extracting(OperationService.OperationRow::shownName)
                .containsExactly(null, "Truck se Stack");
        verify(names, never()).matching(anyString(), anyString());
    }

    @Test
    void SRS_G_38_anApprovedSuccessorEndDatesTheVersionInForceAndAuditsItAsBefore() {
        OperationMaster open = version("OP1", "Wagon to Platform", 1, 11L);
        OperationMaster successor = version("OP1", "Wagon to Platform", 2, 9L);
        when(operations.findByApprovalId(9L)).thenReturn(Optional.of(successor));
        when(operations.findByOperationCodeOrderByVersionNoDesc("OP1"))
                .thenReturn(List.of(successor, open));
        service(null)
                .on(
                        new ApprovalDecided(
                                9L,
                                "M-27",
                                OperationService.ENTITY,
                                "OP1",
                                ApprovalStatus.APPROVED,
                                null,
                                null,
                                "rule.approver@x",
                                Instant.parse("2026-10-29T06:00:00Z")));
        verify(open).endBefore(LocalDate.of(2026, 4, 2));
        verify(operations).save(open);
        verify(audit)
                .applied(
                        any(),
                        eq(OperationService.ENTITY),
                        eq("OP1"),
                        anyString(),
                        any(),
                        notNull(),
                        any(),
                        any(),
                        isNull());
    }
}
