package in.gov.fci.payroll.general.business.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.general.business.GeneralErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The X-06 lifecycle as the entity enforces it (SRS-G-11; DDL chk_query_checker). */
class PayrollQueryTest {

    private static final Instant NOW = Instant.parse("2026-10-20T05:30:00Z");

    private static PayrollQuery open(boolean computed) {
        return PayrollQuery.raise(
                7L, 3L, 99L, PayrollQuery.Channel.STAFF, "Why?", computed, "maker", NOW);
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
    void SRS_G_11_raisedQueriesStartOpenAgainstTheirPayrollId() {
        PayrollQuery q = open(false);
        assertThat(q.getStatus()).isEqualTo(PayrollQuery.Status.OPEN);
        assertThat(q.getPayrollResultRef()).isEqualTo("99");
    }

    @Test
    void SRS_G_11_theLifecycleOnlyMovesForward() {
        PayrollQuery q = open(false);
        assertThat(code(() -> q.resolve("r", "maker", false, NOW)))
                .isEqualTo(GeneralErrorCode.QUERY_STATUS);
        assertThat(code(q::close)).isEqualTo(GeneralErrorCode.QUERY_STATUS);
        q.respond("first", false, "maker", NOW);
        q.respond(
                "second, better",
                false,
                "maker",
                NOW); // still IN_PROGRESS: the response may be refined
        assertThat(q.getMakerResponse()).isEqualTo("second, better");
        q.resolve("done", "maker", false, NOW);
        assertThat(q.getStatus()).isEqualTo(PayrollQuery.Status.RESOLVED);
        q.close();
        assertThat(q.getStatus()).isEqualTo(PayrollQuery.Status.CLOSED);
        assertThat(code(() -> q.respond("late", false, "maker", NOW)))
                .isEqualTo(GeneralErrorCode.QUERY_STATUS);
    }

    @Test
    void chk_query_checker_aComputedAmountNeedsACheckerOtherThanTheResponder() {
        PayrollQuery q = open(false);
        q.respond(
                "explained",
                true,
                "maker",
                NOW); // the Maker settles that it is about a computed amount
        assertThat(q.concernsComputedAmount()).isTrue();
        assertThat(code(() -> q.resolve("ok", "maker2", false, NOW)))
                .isEqualTo(GeneralErrorCode.QUERY_CHECKER_REQUIRED);
        assertThat(code(() -> q.resolve("ok", "maker", true, NOW)))
                .isEqualTo(GeneralErrorCode.QUERY_SAME_PERSON);
        q.resolve("confirmed", "checker", true, NOW);
        assertThat(q.getResolvedBy()).isEqualTo("checker");
    }

    @Test
    void SRS_22_5_theMakerNeverClearsTheComputedAmountFlagSoTheCheckerStillConfirms() {
        // Raised as about a computed amount: the Maker cannot answer it away from the Checker.
        PayrollQuery raised = open(true);
        assertThat(code(() -> raised.respond("explained", false, "maker", NOW)))
                .isEqualTo(GeneralErrorCode.QUERY_COMPUTED_FLAG);
        assertThat(raised.getStatus()).isEqualTo(PayrollQuery.Status.OPEN);
        assertThat(raised.concernsComputedAmount()).isTrue();
        // Set by the Maker on a first response: kept on a later one too.
        PayrollQuery marked = open(false);
        marked.respond("about the DA computed in July", true, "maker", NOW);
        assertThat(code(() -> marked.respond("on second thoughts", false, "maker", NOW)))
                .isEqualTo(GeneralErrorCode.QUERY_COMPUTED_FLAG);
        marked.respond("refined answer", true, "maker", NOW);
        assertThat(marked.getMakerResponse()).isEqualTo("refined answer");
        assertThat(code(() -> marked.resolve("ok", "maker", false, NOW)))
                .isEqualTo(GeneralErrorCode.QUERY_CHECKER_REQUIRED);
    }

    @Test
    void SRS_X06_aMakerResponseIsRequiredBeforeResolution() {
        PayrollQuery unanswered = open(false);
        unanswered.respond(null, false, "maker", NOW);
        assertThatThrownBy(() -> unanswered.resolve("r", "maker", false, NOW))
                .isInstanceOf(FciException.class);
        PayrollQuery q = open(false);
        q.respond(" ", false, "maker", NOW);
        assertThatThrownBy(() -> q.resolve("r", "maker", false, NOW))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(GeneralErrorCode.QUERY_RESPONSE_REQUIRED));
    }

    @Test
    void longLoginIdsAreCutToTheColumnWidth() {
        PayrollQuery q =
                PayrollQuery.raise(
                        1L, 1L, 1L, PayrollQuery.Channel.ESEVA, "t", false, "x".repeat(80), NOW);
        assertThat(q.getRaisedBy()).hasSize(50);
    }
}
