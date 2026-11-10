package in.gov.fci.payroll.iam.web;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.audit.web.RetentionController;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.orchestration.UserAdminService;
import in.gov.fci.payroll.workflow.api.Otp;
import org.junit.jupiter.api.Test;

/**
 * R-PII (OWASP ASVS V7.1.1, CWE-532): a request that carries a password or a one-time code never
 * prints it, whoever logs the object (an exception message, a debug line, a failed validation). The
 * login id, the role and the challenge id are not secrets and stay visible for diagnosis.
 */
class RedactedToStringTest {

    private static final String PASSWORD = "Fake-Passw0rd-482913!";
    private static final String CODE = "482913";

    private static void keepsSecret(Object request, String... secrets) {
        String text = request.toString();
        assertThat(text).isNotBlank();
        for (String secret : secrets) {
            assertThat(text).doesNotContain(secret);
        }
    }

    @Test
    void R_PII_aLoginRequestNeverPrintsThePasswordOrTheLoginId() {
        var request =
                new AuthController.LoginRequest(
                        "maker.delhi", PASSWORD, "PAYROLL_MAKER", "DH001", "USER_ID");
        keepsSecret(request, PASSWORD, "maker.delhi");
        assertThat(request.toString())
                .isEqualTo("LoginRequest{role=PAYROLL_MAKER, location=DH001, idType=USER_ID}");
    }

    @Test
    void R_PII_aLoginRequestWithNoScopePrintsNullsNotAnError() {
        var request = new AuthController.LoginRequest("maker.delhi", PASSWORD, null, null, null);
        assertThat(request.toString())
                .isEqualTo("LoginRequest{role=null, location=null, idType=null}");
        keepsSecret(request, PASSWORD);
    }

    @Test
    void R_PII_aLoginOtpRequestPrintsNoCode() {
        var request = new AuthController.LoginOtpRequest(CODE);
        keepsSecret(request, CODE);
        assertThat(request.toString()).isEqualTo("LoginOtpRequest{}");
        assertThat(new AuthController.LoginOtpRequest(null).toString())
                .isEqualTo("LoginOtpRequest{}");
    }

    @Test
    void R_PII_aResetRequestPrintsNeitherTheCodeNorTheNewPasswordNorTheLoginId() {
        var request = new AuthController.ResetRequest("maker.delhi", CODE, PASSWORD);
        keepsSecret(request, CODE, PASSWORD, "maker.delhi");
        assertThat(request.toString()).isEqualTo("ResetRequest{}");
    }

    @Test
    void R_PII_aPasswordChangeRequestPrintsNeitherPasswordNorTheOtp() {
        var request =
                new MeController.ChangePasswordRequest(
                        "Old-Passw0rd-111111!", PASSWORD, new Otp(501L, CODE));
        keepsSecret(request, "Old-Passw0rd-111111!", PASSWORD, CODE, "501");
        assertThat(request.toString()).isEqualTo("ChangePasswordRequest{}");
    }

    @Test
    void R_PII_aNewUserRequestPrintsTheLoginIdButNeverTheTemporaryPassword() {
        var request =
                new AccessAdminController.NewUserRequest(
                        "checker.pune",
                        "Checker Pune",
                        AppUser.Category.values()[0],
                        "EIS-1001",
                        "9876543210",
                        PASSWORD);
        keepsSecret(request, PASSWORD, "9876543210", "Checker Pune");
        assertThat(request.toString()).isEqualTo("NewUserRequest{loginId=checker.pune}");
    }

    @Test
    void R_PII_aTemporaryPasswordRequestPrintsNothing() {
        var request = new AccessAdminController.TemporaryPasswordRequest(PASSWORD);
        keepsSecret(request, PASSWORD);
        assertThat(request.toString()).isEqualTo("TemporaryPasswordRequest{}");
    }

    @Test
    void R_PII_theServiceLevelNewUserPrintsLoginIdAndCategoryButNeverThePassword() {
        var user =
                new UserAdminService.NewUser(
                        "checker.pune",
                        "Checker Pune",
                        AppUser.Category.values()[0],
                        "EIS-1001",
                        "9876543210",
                        PASSWORD);
        keepsSecret(user, PASSWORD, "9876543210", "Checker Pune");
        assertThat(user.toString())
                .isEqualTo(
                        "NewUser{loginId=checker.pune, category="
                                + AppUser.Category.values()[0]
                                + "}");
    }

    @Test
    void R_OTP_anOtpPrintsItsChallengeIdButNeverTheCode() {
        keepsSecret(new Otp(501L, CODE), CODE);
        assertThat(new Otp(501L, CODE).toString()).isEqualTo("Otp{challengeId=501}");
        assertThat(new Otp(null, null).toString()).isEqualTo("Otp{challengeId=null}");
        assertThat(new RetentionController.OtpBody(502L, CODE).toString())
                .isEqualTo("OtpBody{challengeId=502}");
        keepsSecret(new RetentionController.OtpBody(502L, CODE), CODE);
    }

    @Test
    void R_PII_anOtpProofBuiltFromTheBodyKeepsTheCodeForVerificationButNullStaysNull() {
        assertThat(new Otp(501L, CODE).proof().challengeId()).isEqualTo(501L);
        assertThat(new Otp(501L, CODE).proof().code()).isEqualTo(CODE);
        assertThat(Otp.proof(null)).isNull();
    }
}
