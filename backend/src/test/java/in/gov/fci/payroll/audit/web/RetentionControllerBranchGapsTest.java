package in.gov.fci.payroll.audit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.audit.orchestration.RetentionService;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import org.junit.jupiter.api.Test;

/**
 * A-07 retention classes (SRS-20-29 retention, R-OTP): the first call carries no OTP and is
 * answered 428 by the service; the repeat carries the challenge and its code.
 */
class RetentionControllerBranchGapsTest {

    private final RetentionService retention = mock(RetentionService.class);
    private final RetentionController controller =
            new RetentionController(mock(CurrentAccess.class), retention, mock(Paging.class));

    @Test
    void R_OTP_aRetentionChangeIsProposedWithoutAndThenWithItsOtp() {
        assertThat(
                        controller
                                .proposeRetention(
                                        "PAYROLL_RECORDS",
                                        new RetentionController.RetentionChangeRequest(
                                                120, false, "GFR 2017", "policy review", null))
                                .getStatusCode()
                                .value())
                .isEqualTo(202);
        verify(retention).propose(any(), any(), isNull());

        controller.proposeRetention(
                "PAYROLL_RECORDS",
                new RetentionController.RetentionChangeRequest(
                        120,
                        false,
                        "GFR 2017",
                        "policy review",
                        new RetentionController.OtpBody(9L, "123456")));
        verify(retention)
                .propose(
                        any(),
                        argThat(r -> r.code().equals("PAYROLL_RECORDS") && r.months() == 120),
                        eq(new in.gov.fci.payroll.platform.approval.OtpProof(9L, "123456")));
    }
}
