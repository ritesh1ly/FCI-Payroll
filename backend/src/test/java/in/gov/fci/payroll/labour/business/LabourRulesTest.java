package in.gov.fci.payroll.labour.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.labour.business.LabourRules.Entry;
import in.gov.fci.payroll.labour.business.LabourRules.GangRole;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Labour Master and gang rules (plan 20 phase 20a; SRS-2-1, SRS-2-30, SRS-2-46; BR-LABM-001;
 * D20-1): the entry's mandatory fields and formats, the labour code, the retirement date, and a
 * gang's Sardar and Mandal.
 */
class LabourRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    private static Entry entry(
            String type,
            String first,
            LocalDate birth,
            LocalDate joining,
            String pan,
            boolean pf,
            String cpf) {
        return new Entry(
                type,
                first,
                "Kumar",
                birth,
                joining,
                pan,
                "SBIN0001234",
                "12345678",
                "9876543210",
                pf,
                cpf);
    }

    private static Entry valid() {
        return entry(
                "DEPT_LABOUR",
                "Ram",
                LocalDate.of(1990, 5, 10),
                LocalDate.of(2010, 7, 1),
                "ABCPK1234D",
                true,
                "DL/12345");
    }

    @Test
    void SRS_2_30_aCompleteEntryHasNoDefect() {
        assertThat(LabourRules.defect(valid(), TODAY)).isEmpty();
        assertThat(
                        LabourRules.defect(
                                entry(
                                        "NWNP_LABOUR",
                                        "Ram",
                                        LocalDate.of(1990, 5, 10),
                                        TODAY,
                                        null,
                                        false,
                                        null),
                                TODAY))
                .isEmpty();
        assertThat(
                        LabourRules.defect(
                                new Entry(
                                        "DPS_LABOUR",
                                        "Ram",
                                        "",
                                        LocalDate.of(1990, 5, 10),
                                        TODAY,
                                        "",
                                        "SBIN0001234",
                                        "123456",
                                        "",
                                        false,
                                        null),
                                TODAY))
                .isEmpty();
    }

    @Test
    void SRS_2_30_eachMissingOrMalformedFieldIsNamed() {
        LocalDate birth = LocalDate.of(1990, 5, 10);
        LocalDate joining = LocalDate.of(2010, 7, 1);
        assertThat(LabourRules.defect(null, TODAY)).contains("PAYROLL_TYPE");
        assertThat(
                        LabourRules.defect(
                                entry("STAFF", "Ram", birth, joining, null, false, null), TODAY))
                .contains("PAYROLL_TYPE");
        assertThat(
                        LabourRules.defect(
                                entry("DEPT_LABOUR", null, birth, joining, null, false, null),
                                TODAY))
                .contains("NAME");
        assertThat(
                        LabourRules.defect(
                                entry("DEPT_LABOUR", "R4m", birth, joining, null, false, null),
                                TODAY))
                .contains("NAME");
        assertThat(
                        LabourRules.defect(
                                new Entry(
                                        "DEPT_LABOUR",
                                        "Ram",
                                        "K#",
                                        birth,
                                        joining,
                                        null,
                                        "SBIN0001234",
                                        "123456",
                                        null,
                                        false,
                                        null),
                                TODAY))
                .contains("NAME");
        assertThat(
                        LabourRules.defect(
                                entry("DEPT_LABOUR", "Ram", birth, null, null, false, null), TODAY))
                .contains("JOINING_DATE");
        assertThat(
                        LabourRules.defect(
                                entry(
                                        "DEPT_LABOUR",
                                        "Ram",
                                        birth,
                                        TODAY.plusDays(1),
                                        null,
                                        false,
                                        null),
                                TODAY))
                .contains("JOINING_DATE");
        assertThat(
                        LabourRules.defect(
                                entry("DEPT_LABOUR", "Ram", null, joining, null, false, null),
                                TODAY))
                .contains("BIRTH_DATE");
        assertThat(
                        LabourRules.defect(
                                entry(
                                        "DEPT_LABOUR",
                                        "Ram",
                                        joining.plusDays(1),
                                        joining,
                                        null,
                                        false,
                                        null),
                                TODAY))
                .contains("BIRTH_DATE");
        assertThat(
                        LabourRules.defect(
                                entry("DEPT_LABOUR", "Ram", birth, joining, "ABC123", false, null),
                                TODAY))
                .contains("PAN");
        assertThat(
                        LabourRules.defect(
                                new Entry(
                                        "DEPT_LABOUR",
                                        "Ram",
                                        null,
                                        birth,
                                        joining,
                                        null,
                                        "SBIN1001234",
                                        "123456",
                                        null,
                                        false,
                                        null),
                                TODAY))
                .contains("IFSC");
        assertThat(
                        LabourRules.defect(
                                new Entry(
                                        "DEPT_LABOUR",
                                        "Ram",
                                        null,
                                        birth,
                                        joining,
                                        null,
                                        null,
                                        "123456",
                                        null,
                                        false,
                                        null),
                                TODAY))
                .contains("IFSC");
        assertThat(
                        LabourRules.defect(
                                new Entry(
                                        "DEPT_LABOUR",
                                        "Ram",
                                        null,
                                        birth,
                                        joining,
                                        null,
                                        "SBIN0001234",
                                        "12345",
                                        null,
                                        false,
                                        null),
                                TODAY))
                .contains("BANK_ACCOUNT");
        assertThat(
                        LabourRules.defect(
                                new Entry(
                                        "DEPT_LABOUR",
                                        "Ram",
                                        null,
                                        birth,
                                        joining,
                                        null,
                                        "SBIN0001234",
                                        null,
                                        null,
                                        false,
                                        null),
                                TODAY))
                .contains("BANK_ACCOUNT");
        assertThat(
                        LabourRules.defect(
                                new Entry(
                                        "DEPT_LABOUR",
                                        "Ram",
                                        null,
                                        birth,
                                        joining,
                                        null,
                                        "SBIN0001234",
                                        "123456",
                                        "5876543210",
                                        false,
                                        null),
                                TODAY))
                .contains("MOBILE");
    }

    @Test
    void SRS_2_30_theCpfCodeIsMandatoryWherePfApplies() {
        LocalDate birth = LocalDate.of(1990, 5, 10);
        LocalDate joining = LocalDate.of(2010, 7, 1);
        assertThat(
                        LabourRules.defect(
                                entry("DEPT_LABOUR", "Ram", birth, joining, null, true, null),
                                TODAY))
                .contains("CPF_CODE");
        assertThat(
                        LabourRules.defect(
                                entry("DEPT_LABOUR", "Ram", birth, joining, null, true, "dl 1"),
                                TODAY))
                .contains("CPF_CODE");
        assertThat(
                        LabourRules.defect(
                                entry(
                                        "DEPT_LABOUR",
                                        "Ram",
                                        birth,
                                        joining,
                                        null,
                                        true,
                                        "A".repeat(21)),
                                TODAY))
                .contains("CPF_CODE");
        assertThat(
                        LabourRules.defect(
                                entry(
                                        "DEPT_LABOUR",
                                        "Ram",
                                        birth,
                                        joining,
                                        null,
                                        false,
                                        "junk code"),
                                TODAY))
                .isEmpty();
    }

    @Test
    void D20_1_theLabourCodeCarriesThePayrollTypePrefix() {
        assertThat(LabourRules.labourCode("DEPT_LABOUR", 1)).isEqualTo("DPT-000001");
        assertThat(LabourRules.labourCode("DPS_LABOUR", 42)).isEqualTo("DPS-000042");
        assertThat(LabourRules.labourCode("NWNP_LABOUR", 999_999)).isEqualTo("NWN-999999");
        assertThatThrownBy(() -> LabourRules.labourCode("STAFF", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LabourRules.labourCode("DEPT_LABOUR", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LabourRules.labourCode("DEPT_LABOUR", 1_000_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void D20_3_retirementIsTheLastDayOfTheMonthTheAgeIsAttained() {
        assertThat(LabourRules.retirementDate(LocalDate.of(1970, 5, 10), 60))
                .isEqualTo(LocalDate.of(2030, 5, 31));
        assertThat(LabourRules.retirementDate(LocalDate.of(1970, 5, 1), 60))
                .isEqualTo(LocalDate.of(2030, 4, 30));
        assertThat(LabourRules.retirementDate(LocalDate.of(1970, 1, 1), 60))
                .isEqualTo(LocalDate.of(2029, 12, 31));
        assertThat(LabourRules.retirementDate(LocalDate.of(1968, 2, 29), 60))
                .isEqualTo(LocalDate.of(2028, 2, 29));
        LocalDate retire = LocalDate.of(2030, 5, 31);
        assertThat(LabourRules.retired(retire, retire)).isFalse();
        assertThat(LabourRules.retired(retire, retire.plusDays(1))).isTrue();
        assertThat(LabourRules.retired(null, retire)).isFalse();
    }

    // SC-20-0131: a second Sardar or Mandal is rejected.
    @Test
    void SRS_2_1_aHandlingGangHasExactlyOneSardarAndOneMandal() {
        assertThat(
                        LabourRules.gangRoles(
                                List.of(GangRole.SARDAR, GangRole.MANDAL, GangRole.HANDLING),
                                false))
                .isEmpty();
        assertThat(LabourRules.gangRoles(List.of(GangRole.MANDAL, GangRole.HANDLING), false))
                .contains("SARDAR");
        assertThat(
                        LabourRules.gangRoles(
                                List.of(GangRole.SARDAR, GangRole.SARDAR, GangRole.MANDAL), false))
                .contains("SARDAR");
        assertThat(LabourRules.gangRoles(List.of(GangRole.SARDAR, GangRole.HANDLING), false))
                .contains("MANDAL");
        assertThat(
                        LabourRules.gangRoles(
                                List.of(GangRole.SARDAR, GangRole.MANDAL, GangRole.MANDAL), false))
                .contains("MANDAL");
    }

    @Test
    void SRS_2_46_theAncillaryPoolHasNoSardarOrMandal() {
        assertThat(LabourRules.gangRoles(List.of(GangRole.ANCILLARY), true)).isEmpty();
        assertThat(LabourRules.gangRoles(List.of(), true)).isEmpty();
        assertThat(LabourRules.gangRoles(List.of(GangRole.ANCILLARY, GangRole.SARDAR), true))
                .contains("POOL_ROLES");
        assertThat(LabourRules.gangRoles(List.of(GangRole.MANDAL), true)).contains("POOL_ROLES");
        // A Sardar and a Mandal together are two pool roles too, not cancelling each other.
        assertThat(
                        LabourRules.gangRoles(
                                List.of(GangRole.SARDAR, GangRole.MANDAL, GangRole.ANCILLARY),
                                true))
                .contains("POOL_ROLES");
    }

    @Test
    void SRS_2_59_theEpsCutOffIsTheDayTheMemberAttainsTheCutOffAge() {
        assertThat(LabourRules.epsCutoff(LocalDate.of(1970, 5, 10), 58))
                .isEqualTo(LocalDate.of(2028, 5, 10));
        // Born on the 1st: the birthday itself, unlike the retirement date.
        assertThat(LabourRules.epsCutoff(LocalDate.of(1970, 5, 1), 58))
                .isEqualTo(LocalDate.of(2028, 5, 1));
        // Born on 29 February: the cut-off falls on 28 February of a common year.
        assertThat(LabourRules.epsCutoff(LocalDate.of(1968, 2, 29), 58))
                .isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void SRS_2_1_aGangNameIsPrintableAndAtMost150Characters() {
        assertThat(LabourRules.gangName("Okhla Gang 1")).isTrue();
        assertThat(LabourRules.gangName("  Okhla  ")).isTrue();
        assertThat(LabourRules.gangName("G".repeat(150))).isTrue();
        assertThat(LabourRules.gangName("G".repeat(151))).isFalse();
        assertThat(LabourRules.gangName(null)).isFalse();
        assertThat(LabourRules.gangName("   ")).isFalse();
        assertThat(LabourRules.gangName("Gangअ")).isFalse();
    }

    // M-02 l.4449: Labour Name is Alphanumeric (150), shown as "first last" (SC-20-0116).
    @Test
    void SRS_2_30_theLabourNameIsAtMost150CharactersCombined() {
        assertThat(LabourRules.fullNameFits("A".repeat(150), null)).isTrue();
        assertThat(LabourRules.fullNameFits("A".repeat(150), " ")).isTrue();
        assertThat(LabourRules.fullNameFits("A".repeat(151), null)).isFalse();
        assertThat(LabourRules.fullNameFits("A".repeat(100), "B".repeat(49))).isTrue();
        assertThat(LabourRules.fullNameFits("A".repeat(100), "B".repeat(50))).isFalse();
        assertThat(LabourRules.fullNameFits(null, null)).isTrue();
        assertThat(LabourRules.fullNameFits(" Ram ", " Kumar ")).isTrue();
        LocalDate birth = LocalDate.of(1990, 5, 10);
        LocalDate joining = LocalDate.of(2010, 7, 1);
        assertThat(
                        LabourRules.defect(
                                entry(
                                        "DEPT_LABOUR",
                                        "A".repeat(145),
                                        birth,
                                        joining,
                                        null,
                                        false,
                                        null),
                                TODAY))
                .contains("NAME");
        assertThat(
                        LabourRules.defect(
                                entry(
                                        "DEPT_LABOUR",
                                        "A".repeat(144),
                                        birth,
                                        joining,
                                        null,
                                        false,
                                        null),
                                TODAY))
                .isEmpty();
    }

    // SRS-3-9 l.4236 (US-20-12 AC7): a work-slip carries at most one Sardar and one Mandal: its
    // participants are members of the gang that day, listed once each, and a gang never holds a
    // second Sardar or Mandal.
    @Test
    void SRS_3_9_aSlipCarriesAtMostOneSardarAndOneMandal() {
        assertThat(
                        LabourRules.gangRoles(
                                List.of(GangRole.SARDAR, GangRole.SARDAR, GangRole.MANDAL), false))
                .isPresent();
        assertThat(
                        LabourRules.gangRoles(
                                List.of(GangRole.SARDAR, GangRole.MANDAL, GangRole.MANDAL), false))
                .isPresent();
        assertThat(
                        LabourRules.gangRoles(
                                List.of(GangRole.SARDAR, GangRole.MANDAL, GangRole.HANDLING),
                                false))
                .isEmpty();
    }
}
