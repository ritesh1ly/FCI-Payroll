package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * M-27's own guards, behind the API's validation (SRS-G-41): an operation code is one to ten
 * capitals, digits, '-' or '_', starting with a capital or digit; a proposal names its start, an
 * end-date names its last day, and an approved end-date whose proposal cannot be read fails the
 * decision rather than ending anything.
 */
class OperationServiceGuardsTest {

    private final ConfigurationRepositories.Operations operations =
            mock(ConfigurationRepositories.Operations.class);
    private final OperationService service =
            new OperationService(
                    operations,
                    mock(ConfigurationRepositories.PayComponents.class),
                    mock(Circulars.class),
                    mock(ApprovalStatuses.class),
                    mock(MakerChecker.class),
                    mock(Authorization.class),
                    mock(SystemParameters.class),
                    mock(MasterAudit.class));

    private final AccessContext admin =
            new AccessContext(
                    1L,
                    "rule.admin@x",
                    "Admin",
                    "s",
                    "P",
                    1L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(1L));

    private static OperationService.ProposedOperation proposal(LocalDate from) {
        return new OperationService.ProposedOperation(
                from,
                "Wagon to Platform",
                true,
                false,
                false,
                false,
                null,
                "TIME_BASED",
                false,
                null,
                false,
                1L,
                "New norm");
    }

    private static void refused(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    @Test
    void SRS_G_41_aCodeIsUpToTenCapitalsDigitsOrDashesAndAProposalNamesItsStart() {
        for (String bad : Arrays.asList(null, "", "op1", "-OP1", "OP 1", "OPERATION01")) {
            assertThatThrownBy(
                            () ->
                                    service.propose(
                                            admin, bad, proposal(LocalDate.of(2091, 1, 1)), null))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e -> {
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.MASTER_INVALID_TEXT);
                                assertThat(e.getMessageArgs()).containsExactly("m27.code", 10);
                            });
        }
        refused(() -> service.propose(admin, "OP1", null, null), CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> service.propose(admin, "OP1", proposal(null), null),
                CommonErrorCode.VALIDATION_FAILED);
        verify(operations, never()).save(any());
    }

    @Test
    void SRS_G_41_anEndDateNamesItsLastDay() {
        refused(
                () -> service.endDate(admin, "OP1", null, "Retired", null),
                CommonErrorCode.VALIDATION_FAILED);
        verify(operations, never()).findByOperationCodeOrderByVersionNoDesc(any());
    }

    @Test
    void SRS_G_41_anApprovedEndDateWhoseProposalCannotBeReadEndsNothing() {
        for (String payload : Arrays.asList(null, "{}", "{\"lastDay\":\"not-a-date\"}")) {
            ApprovalDecided decided =
                    new ApprovalDecided(
                            9L,
                            "M-27",
                            OperationService.END_DATE,
                            "OP1",
                            ApprovalStatus.APPROVED,
                            null,
                            payload,
                            "rule.approver@x",
                            Instant.parse("2026-10-23T06:00:00Z"));
            assertThatThrownBy(() -> service.on(decided))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("unreadable end-date proposal");
        }
        verify(operations, never()).save(any());
    }
}
