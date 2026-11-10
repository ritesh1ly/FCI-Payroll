package in.gov.fci.payroll.iam.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * SRS-G-37, SRS-G-49; screens A-01 and A-04: every administrator action that ends or revokes
 * something carries a reason that is not blank, at most 500 characters and printable ASCII
 * (R-ASCII); the constraint is on the request record, so the API refuses before the service runs.
 */
class AccessAdminRequestsValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void open() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private static <T> Set<?> violations(T request) {
        return validator.validate(request);
    }

    @Test
    void SRS_G_37_aRevokeReasonIsRequiredBoundedAndPrintableAscii() {
        assertThat(violations(new AccessAdminController.RevokeRequest("Left the office")))
                .isEmpty();
        assertThat(violations(new AccessAdminController.RevokeRequest("x".repeat(500)))).isEmpty();
        assertThat(violations(new AccessAdminController.RevokeRequest("x".repeat(501)))).hasSize(1);
        assertThat(violations(new AccessAdminController.RevokeRequest(null))).hasSize(1);
        assertThat(violations(new AccessAdminController.RevokeRequest(""))).isNotEmpty();
        assertThat(violations(new AccessAdminController.RevokeRequest("   "))).isNotEmpty();
        assertThat(violations(new AccessAdminController.RevokeRequest("café"))).isNotEmpty();
    }

    @Test
    void SRS_G_37_theRecordKeepsItsReasonAndComparesByValue() {
        AccessAdminController.RevokeRequest request =
                new AccessAdminController.RevokeRequest("Transferred");
        assertThat(request.reason()).isEqualTo("Transferred");
        assertThat(request).isEqualTo(new AccessAdminController.RevokeRequest("Transferred"));
        assertThat(request).isNotEqualTo(new AccessAdminController.RevokeRequest("Other"));
    }

    @Test
    void SRS_G_49_unlockAndTerminateReasonsFollowTheSameRule() {
        assertThat(violations(new AccessAdminController.UnlockRequest("Verified by phone")))
                .isEmpty();
        assertThat(violations(new AccessAdminController.UnlockRequest(" "))).isNotEmpty();
        assertThat(violations(new AccessAdminController.TerminateRequest("Incident 12"))).isEmpty();
        assertThat(violations(new AccessAdminController.TerminateRequest("y".repeat(501))))
                .isNotEmpty();
    }
}
