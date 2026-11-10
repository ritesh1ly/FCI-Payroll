package in.gov.fci.payroll.general.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.general.business.GeneralErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** X-05 entry rules (SRS-G-9, SRS-EH-4; DDL chk_exception_accept, chk_exception_hrms_ro). */
class ExceptionEntryTest {

    private static final Instant NOW = Instant.parse("2026-10-20T05:30:00Z");

    private static ExceptionEntry raise(
            ExceptionEntry.Severity severity,
            String gate,
            ExceptionEntry.FieldSource source,
            Long task) {
        return ExceptionEntry.raise(
                1L,
                7L,
                "C-02",
                "MISSING_BANK",
                severity,
                gate,
                task,
                "employee",
                "bank_account",
                source,
                "M-01",
                "No bank account",
                NOW);
    }

    private static GeneralErrorCode code(Runnable call) {
        try {
            call.run();
        } catch (FciException ex) {
            return (GeneralErrorCode) ex.getErrorCode();
        }
        throw new AssertionError("expected an FciException");
    }

    @Test
    void SRS_G_9_aPayrollOwnedExceptionOpensResolvableInPayroll() {
        ExceptionEntry e =
                raise(ExceptionEntry.Severity.WARNING, "G2", ExceptionEntry.FieldSource.ENT, null);
        assertThat(e.getStatus()).isEqualTo(ExceptionEntry.Status.OPEN);
        assertThat(e.isResolvableInPayroll()).isTrue();
        assertThat(e.getOwnerRoleCode()).isNull();
    }

    @Test
    void SRS_EH_4_anHrmsOrInterfaceValueIsRoutedUpstreamAndNotResolvableInPayroll() {
        ExceptionEntry hrms =
                raise(
                        ExceptionEntry.Severity.BLOCKING,
                        "G2",
                        ExceptionEntry.FieldSource.HRMS,
                        null);
        assertThat(hrms.getStatus()).isEqualTo(ExceptionEntry.Status.ROUTED_UPSTREAM);
        assertThat(hrms.isResolvableInPayroll()).isFalse();
        assertThat(hrms.getOwnerRoleCode()).isEqualTo("HRMS");
        ExceptionEntry intf =
                raise(ExceptionEntry.Severity.WARNING, null, ExceptionEntry.FieldSource.INTF, null);
        assertThat(intf.isResolvableInPayroll()).isFalse();
        assertThat(intf.getOwnerRoleCode()).isEqualTo("SYSTEM_ADMINISTRATOR");
    }

    @Test
    void chk_exception_hrms_ro_anHrmsValueUnderAnI21TaskIsDecidedInPayroll() {
        ExceptionEntry e =
                raise(ExceptionEntry.Severity.WARNING, "G2", ExceptionEntry.FieldSource.HRMS, 55L);
        assertThat(e.isResolvableInPayroll()).isTrue();
        assertThat(e.getStatus()).isEqualTo(ExceptionEntry.Status.OPEN);
    }

    @Test
    void SRS_G_9_onlyANonBlockingG2ItemIsAcceptedAndOnlyWithAReason() {
        assertThat(
                        code(
                                () ->
                                        raise(
                                                        ExceptionEntry.Severity.BLOCKING,
                                                        "G2",
                                                        ExceptionEntry.FieldSource.ENT,
                                                        null)
                                                .accept("checker", "ok")))
                .isEqualTo(GeneralErrorCode.EXCEPTION_NOT_ACCEPTABLE);
        assertThat(
                        code(
                                () ->
                                        raise(
                                                        ExceptionEntry.Severity.WARNING,
                                                        null,
                                                        ExceptionEntry.FieldSource.ENT,
                                                        null)
                                                .accept("checker", "ok")))
                .isEqualTo(GeneralErrorCode.EXCEPTION_NOT_ACCEPTABLE);
        ExceptionEntry e =
                raise(ExceptionEntry.Severity.WARNING, "G2", ExceptionEntry.FieldSource.ENT, null);
        assertThat(code(() -> e.accept("checker", " ")))
                .isEqualTo(GeneralErrorCode.EXCEPTION_REASON);
        assertThat(code(() -> e.accept("checker", null)))
                .isEqualTo(GeneralErrorCode.EXCEPTION_REASON);
        e.accept("checker", "Incentive withheld this month");
        assertThat(e.getStatus()).isEqualTo(ExceptionEntry.Status.ACCEPTED);
        assertThat(e.getAcceptedBy()).isEqualTo("checker");
        assertThat(e.isUnsettled()).isFalse();
    }

    @Test
    void SRS_G_9_anItemRoutedUpstreamStaysOpenAndIsNeverAccepted() {
        // SRS X-05: "The exception stays open until the corrected value arrives."
        ExceptionEntry upstream =
                raise(ExceptionEntry.Severity.WARNING, "G2", ExceptionEntry.FieldSource.HRMS, null);
        assertThat(code(() -> upstream.accept("checker", "HRMS will correct it")))
                .isEqualTo(GeneralErrorCode.EXCEPTION_NOT_ACCEPTABLE);
        assertThat(upstream.getStatus()).isEqualTo(ExceptionEntry.Status.ROUTED_UPSTREAM);
        assertThat(upstream.isUnsettled()).isTrue();
        // The corrected value arriving clears it.
        upstream.clear(NOW);
        assertThat(upstream.getStatus()).isEqualTo(ExceptionEntry.Status.RESOLVED);
    }

    @Test
    void assignMovesAnOpenItemInProgressAndOnlyToAKnownOwner() {
        ExceptionEntry e =
                raise(ExceptionEntry.Severity.WARNING, "G2", ExceptionEntry.FieldSource.ENT, null);
        assertThat(code(() -> e.assign("SOMEONE"))).isEqualTo(GeneralErrorCode.EXCEPTION_OWNER);
        e.assign("PAYROLL_MAKER");
        assertThat(e.getStatus()).isEqualTo(ExceptionEntry.Status.IN_PROGRESS);
        assertThat(e.getOwnerRoleCode()).isEqualTo("PAYROLL_MAKER");
    }

    @Test
    void aSettledItemCannotChangeAgain() {
        ExceptionEntry e =
                raise(ExceptionEntry.Severity.WARNING, "G2", ExceptionEntry.FieldSource.ENT, null);
        e.clear(NOW);
        assertThat(e.getStatus()).isEqualTo(ExceptionEntry.Status.RESOLVED);
        assertThat(e.getResolvedAt()).isEqualTo(NOW);
        assertThat(code(() -> e.assign("PAYROLL_MAKER")))
                .isEqualTo(GeneralErrorCode.EXCEPTION_STATUS);
        assertThat(code(() -> e.clear(NOW))).isEqualTo(GeneralErrorCode.EXCEPTION_STATUS);
        assertThat(code(() -> e.accept("checker", "late")))
                .isEqualTo(GeneralErrorCode.EXCEPTION_STATUS);
        assertThat(ExceptionEntry.unsettledStatuses())
                .containsExactlyInAnyOrder(
                        ExceptionEntry.Status.OPEN,
                        ExceptionEntry.Status.IN_PROGRESS,
                        ExceptionEntry.Status.ROUTED_UPSTREAM);
    }

    /**
     * SRS-EH-1: every system-raised condition is Blocking, Warning, Informational or Security, and
     * the severity is held on the entry (the message), whatever screen raised it; only a Warning
     * may proceed once acknowledged.
     */
    @Test
    void SRS_EH_1_aConditionCarriesOneOfTheFourSeveritiesOnItsOwnEntry() {
        assertThat(ExceptionEntry.Severity.values())
                .containsExactly(
                        ExceptionEntry.Severity.BLOCKING,
                        ExceptionEntry.Severity.WARNING,
                        ExceptionEntry.Severity.INFORMATIONAL,
                        ExceptionEntry.Severity.SECURITY);
        for (ExceptionEntry.Severity severity : ExceptionEntry.Severity.values()) {
            assertThat(raise(severity, "G2", ExceptionEntry.FieldSource.ENT, null).getSeverity())
                    .isEqualTo(severity);
        }
        ExceptionEntry warning =
                raise(ExceptionEntry.Severity.WARNING, "G2", ExceptionEntry.FieldSource.ENT, null);
        warning.accept("checker", "Acknowledged");
        assertThat(warning.getStatus()).isEqualTo(ExceptionEntry.Status.ACCEPTED);
        assertThat(
                        code(
                                () ->
                                        raise(
                                                        ExceptionEntry.Severity.BLOCKING,
                                                        "G2",
                                                        ExceptionEntry.FieldSource.ENT,
                                                        null)
                                                .accept("checker", "Acknowledged")))
                .isEqualTo(GeneralErrorCode.EXCEPTION_NOT_ACCEPTABLE);
    }
}
