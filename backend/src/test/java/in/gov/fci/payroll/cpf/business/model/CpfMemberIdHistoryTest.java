package in.gov.fci.payroll.cpf.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * SRS-6-59 (the CPF Member ID comes from HRMS; DDL cpf_member_id_history): the identifier a ledger
 * posting carries is the one ACTIVE on the posting day, its first and last day included.
 */
class CpfMemberIdHistoryTest {

    private static CpfMemberIdHistory id(String status, LocalDate from, LocalDate to) {
        CpfMemberIdHistory h = new CpfMemberIdHistory();
        ReflectionTestUtils.setField(h, "idStatus", status);
        ReflectionTestUtils.setField(h, "effectiveFrom", from);
        ReflectionTestUtils.setField(h, "effectiveTo", to);
        return h;
    }

    // SC-07-0107: one row per EPS / CPF Member ID held, with its start and end.
    @Test
    void SRS_6_59_anIdentifierIsInUseFromItsFirstToItsLastDayWhileActive() {
        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to = LocalDate.of(2027, 3, 31);
        CpfMemberIdHistory closed = id("ACTIVE", from, to);
        assertThat(closed.activeOn(from)).isTrue();
        assertThat(closed.activeOn(to)).isTrue();
        assertThat(closed.activeOn(from.minusDays(1))).isFalse();
        assertThat(closed.activeOn(to.plusDays(1))).isFalse();
        // Open-ended: in use from its first day on.
        CpfMemberIdHistory open = id("ACTIVE", from, null);
        assertThat(open.activeOn(LocalDate.of(2040, 1, 1))).isTrue();
        assertThat(open.activeOn(from.minusDays(1))).isFalse();
        // A superseded identifier is never in use, even inside its dates.
        assertThat(id("SUPERSEDED", from, null).activeOn(from)).isFalse();
    }
}
