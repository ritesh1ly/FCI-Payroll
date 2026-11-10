package in.gov.fci.payroll.loans.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The optional parts of the I-04 sanction body (plan 09 phase 09b; SRS-12-1, SRS-12-8). */
class LoanControllerBodiesTest {

    @Test
    void SRS_12_1_noInterestInstalmentsMeansNoneAndNoReviewMeansNotReviewed() {
        LoanController.SanctionBody bare =
                new LoanController.SanctionBody(
                        "100041",
                        "FESTIVAL_ADVANCE",
                        new BigDecimal("20000"),
                        4,
                        new BigDecimal("5000"),
                        null,
                        null,
                        null,
                        null,
                        "202611",
                        null);
        assertThat(bare.proposal().interestInstalments()).isZero();
        assertThat(bare.proposal().reviewedOpenRequest()).isFalse();
        LoanController.SanctionBody full =
                new LoanController.SanctionBody(
                        "100041",
                        "PLI_ADVANCE",
                        new BigDecimal("20000"),
                        4,
                        new BigDecimal("5000"),
                        null,
                        new BigDecimal("500"),
                        2,
                        new BigDecimal("250"),
                        "202611",
                        true);
        assertThat(full.proposal().interestInstalments()).isEqualTo(2);
        assertThat(full.proposal().reviewedOpenRequest()).isTrue();
        assertThat(
                        new LoanController.RescheduleBody(
                                        4, new BigDecimal("1000"), null, null, "202611")
                                .schedule()
                                .interestInstalments())
                .isZero();
        assertThat(
                        new LoanController.RescheduleBody(
                                        4,
                                        new BigDecimal("1000"),
                                        1,
                                        new BigDecimal("300"),
                                        "202611")
                                .schedule()
                                .interestInstalments())
                .isEqualTo(1);
    }
}
