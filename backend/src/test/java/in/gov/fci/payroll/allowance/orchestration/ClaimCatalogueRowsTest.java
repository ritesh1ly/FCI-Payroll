package in.gov.fci.payroll.allowance.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.allowance.persistence.AllowanceRepositories;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * I-11 claim catalogue (plan 10 D10-1 … D10-3; SRS-11-1): a claim type without a pay code (not yet
 * configured in M-16) lists none; a member without a last name, retirement date or office reads
 * them as none.
 */
class ClaimCatalogueRowsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 29);

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final ClaimCatalogue catalogue =
            new ClaimCatalogue(
                    db.jdbc,
                    mock(AllowanceRepositories.Claims.class),
                    mock(in.gov.fci.payroll.platform.approval.ApprovalStatuses.class));

    @Test
    void SRS_11_1_aClaimTypeWithoutAPayCodeListsNone() {
        db.rows(
                "'CLAIM_TYPE'",
                cols("MEDICAL", "Medical", null),
                cols("TOUR", "Tour", new BigDecimal("941")));
        assertThat(catalogue.types("EMPLOYEE", DAY))
                .extracting(ClaimCatalogue.ClaimType::payCode)
                .containsExactly(null, 941);
        assertThat(catalogue.type("MEDICAL", DAY))
                .hasValueSatisfying(t -> assertThat(t.payCode()).isNull());
    }

    @Test
    void SRS_11_1_aMemberWithoutALastNameRetirementOrOfficeReadsThemAsNone() {
        db.rows(
                "FROM employee WHERE employee_id",
                cols(7L, "E7", "Asha", null, "STAFF", "IN_SERVICE", null, null));
        ClaimCatalogue.MemberFacts m = catalogue.member(7L);
        assertThat(m.name()).isEqualTo("Asha");
        assertThat(m.retirementDate()).isNull();
        assertThat(m.locationId()).isNull();
        db.rows(
                "FROM employee WHERE employee_id",
                cols(
                        8L,
                        "E8",
                        "Ravi",
                        "Kumar",
                        "STAFF",
                        "IN_SERVICE",
                        Date.valueOf(LocalDate.of(2050, 6, 30)),
                        40L));
        ClaimCatalogue.MemberFacts n = catalogue.member(8L);
        assertThat(n.name()).isEqualTo("Ravi Kumar");
        assertThat(n.locationId()).isEqualTo(40L);
    }
}
