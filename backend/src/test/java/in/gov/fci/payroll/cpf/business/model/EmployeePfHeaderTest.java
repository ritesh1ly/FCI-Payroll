package in.gov.fci.payroll.cpf.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.cpf.business.LedgerBalances;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader.MemberStatus;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader.OpeningSource;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** SRS-6-43: a correction carried into a later year moves its opening and closing balances. */
class EmployeePfHeaderTest {

    private static LedgerBalances b(String e, String r, String v) {
        return new LedgerBalances(new BigDecimal(e), new BigDecimal(r), new BigDecimal(v));
    }

    @Test
    void SRS_6_43_aCarriedCorrectionMovesTheOpeningAndTheClosing() {
        EmployeePfHeader h =
                EmployeePfHeader.open(
                        7L,
                        "2026-27",
                        "CPF",
                        11L,
                        null,
                        b("100", "200", "30"),
                        OpeningSource.SYSTEM_COMPUTED,
                        MemberStatus.ACTIVE,
                        false);
        assertThat(h.opening()).isEqualTo(b("100", "200", "30"));
        assertThat(h.closing()).isEqualTo(b("100", "200", "30"));
        h.carry(b("110", "205", "30"), b("160", "255", "40"));
        assertThat(h.opening()).isEqualTo(b("110", "205", "30"));
        assertThat(h.closing()).isEqualTo(b("160", "255", "40"));
    }
}
