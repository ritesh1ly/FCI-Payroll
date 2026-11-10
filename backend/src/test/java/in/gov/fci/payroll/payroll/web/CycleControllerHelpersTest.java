package in.gov.fci.payroll.payroll.web;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.workflow.api.Otp;
import org.junit.jupiter.api.Test;

/** SRS-15-11, SRS-G-21: the optional bodies of C-02 freeze and lock requests. */
class CycleControllerHelpersTest {

    @Test
    void SRS_15_11_aLockWithoutAReasonIsCalledCycleLockAndAFreezeMayCarryNoOtp() {
        assertThat(CycleController.lockReason(null)).isEqualTo("Cycle lock");
        assertThat(CycleController.lockReason(new CycleController.LockRequest(null, null)))
                .isEqualTo("Cycle lock");
        assertThat(CycleController.lockReason(new CycleController.LockRequest(" ", null)))
                .isEqualTo("Cycle lock");
        assertThat(CycleController.lockReason(new CycleController.LockRequest("Month end", null)))
                .isEqualTo("Month end");
        assertThat(CycleController.otp(null)).isEqualTo(Otp.proof(null));
        assertThat(CycleController.otp(new CycleController.OtpRequest(null)))
                .isEqualTo(Otp.proof(null));
        assertThat(CycleController.lockOtp(null)).isEqualTo(Otp.proof(null));
        assertThat(CycleController.lockOtp(new CycleController.LockRequest("Month end", null)))
                .isEqualTo(Otp.proof(null));
    }
}
