package in.gov.fci.payroll.labour.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.labour.business.LabourRecordRules.Person;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Labour reinstatement and nominees (plan 22 phase 22c; D22-7, D22-8; BR-FNF-014): only a separated
 * member, after the last day; nominees' shares adding to 100, names and relationships well formed.
 */
class LabourRecordRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final Set<String> RELATIONS = Set.of("SPOUSE", "SON");

    private static Person nominee(String share) {
        return new Person(
                "Sita", "SPOUSE", null, share == null ? null : new BigDecimal(share), true);
    }

    // SC-22-0040
    @Test
    void reinstateDetails_onlyASeparatedMemberAfterTheLastDay() {
        LocalDate last = TODAY.minusDays(10);
        assertThat(LabourRecordRules.reinstatementDefect("SEPARATED", last, TODAY, "Order"))
                .isEmpty();
        assertThat(LabourRecordRules.reinstatementDefect("SEPARATED", null, TODAY, "Order"))
                .isEmpty();
        assertThat(LabourRecordRules.reinstatementDefect("ACTIVE", last, TODAY, "Order"))
                .contains("STATUS");
        assertThat(LabourRecordRules.reinstatementDefect("RETIRED", last, TODAY, "Order"))
                .as("a retirement is final")
                .contains("STATUS");
        assertThat(LabourRecordRules.reinstatementDefect("SEPARATED", last, last, "Order"))
                .contains("DATE");
        assertThat(LabourRecordRules.reinstatementDefect("SEPARATED", last, null, "Order"))
                .contains("DATE");
        assertThat(LabourRecordRules.reinstatementDefect("SEPARATED", last, last.plusDays(1), " "))
                .contains("REASON");
        assertThat(LabourRecordRules.reinstatementDefect("SEPARATED", last, last.plusDays(1), null))
                .contains("REASON");
        assertThat(
                        LabourRecordRules.reinstatementDefect(
                                "SEPARATED", last, last.plusDays(1), "r".repeat(501)))
                .contains("REASON");
    }

    // SC-22-0040: the reason and a person's name at exactly their column length are accepted.
    @Test
    void reinstateDetails_aReasonOfExactly500AndANameOf150CharactersAreAccepted() {
        LocalDate last = TODAY.minusDays(10);
        assertThat(
                        LabourRecordRules.reinstatementDefect(
                                "SEPARATED", last, last.plusDays(1), "r".repeat(500)))
                .isEmpty();
        // Surrounding spaces are not counted: 500 characters once stripped.
        assertThat(
                        LabourRecordRules.reinstatementDefect(
                                "SEPARATED", last, last.plusDays(1), " " + "r".repeat(500) + " "))
                .isEmpty();
        Person longName = new Person("n".repeat(150), "SPOUSE", null, new BigDecimal("100"), true);
        assertThat(
                        LabourRecordRules.nominationDefect(
                                List.of(longName), List.of(), RELATIONS, TODAY, "Nominated"))
                .isEmpty();
        Person tooLong = new Person("n".repeat(151), "SPOUSE", null, new BigDecimal("100"), true);
        assertThat(
                        LabourRecordRules.nominationDefect(
                                List.of(tooLong), List.of(), RELATIONS, TODAY, "Nominated"))
                .contains("NAME");
    }

    // SC-22-0042
    @Test
    void nomineeDetails_sharesAddTo100AndEachPersonIsWellFormed() {
        Person dependent = new Person("Kiran", "SON", TODAY.minusYears(5), null, false);
        assertThat(
                        LabourRecordRules.nominationDefect(
                                List.of(nominee("60"), nominee("40")),
                                List.of(dependent),
                                RELATIONS,
                                TODAY,
                                "Form 2"))
                .isEmpty();
        assertThat(
                        LabourRecordRules.nominationDefect(
                                List.of(), List.of(dependent), RELATIONS, TODAY, "r"))
                .as("dependents alone")
                .isEmpty();
        assertThat(LabourRecordRules.nominationDefect(null, null, RELATIONS, TODAY, "r"))
                .contains("EMPTY");
        assertThat(
                        LabourRecordRules.nominationDefect(
                                List.of(nominee("60"), nominee("30")), null, RELATIONS, TODAY, "r"))
                .contains("SHARE_TOTAL");
        assertThat(
                        LabourRecordRules.nominationDefect(
                                List.of(nominee(null)), null, RELATIONS, TODAY, "r"))
                .contains("SHARE");
        assertThat(
                        LabourRecordRules.nominationDefect(
                                List.of(nominee("0")), null, RELATIONS, TODAY, "r"))
                .contains("SHARE");
        assertThat(
                        LabourRecordRules.nominationDefect(
                                List.of(nominee("100.01")), null, RELATIONS, TODAY, "r"))
                .contains("SHARE");
        assertThat(
                        LabourRecordRules.nominationDefect(
                                List.of(nominee("33.333"), nominee("66.667")),
                                null,
                                RELATIONS,
                                TODAY,
                                "r"))
                .contains("SHARE");
        assertThat(
                        LabourRecordRules.nominationDefect(
                                List.of(nominee("33.33"), nominee("66.67")),
                                null,
                                RELATIONS,
                                TODAY,
                                "r"))
                .isEmpty();
        assertThat(
                        LabourRecordRules.nominationDefect(
                                Arrays.asList((Person) null), null, RELATIONS, TODAY, "r"))
                .contains("NAME");
        assertThat(
                        LabourRecordRules.nominationDefect(
                                List.of(new Person(" ", "SPOUSE", null, BigDecimal.TEN, true)),
                                null,
                                RELATIONS,
                                TODAY,
                                "r"))
                .contains("NAME");
        assertThat(
                        LabourRecordRules.nominationDefect(
                                List.of(new Person("सीता", "SPOUSE", null, BigDecimal.TEN, true)),
                                null,
                                RELATIONS,
                                TODAY,
                                "r"))
                .contains("NAME");
        assertThat(
                        LabourRecordRules.nominationDefect(
                                List.of(
                                        new Person(
                                                "n".repeat(151),
                                                "SPOUSE",
                                                null,
                                                BigDecimal.TEN,
                                                true)),
                                null,
                                RELATIONS,
                                TODAY,
                                "r"))
                .contains("NAME");
        assertThat(
                        LabourRecordRules.nominationDefect(
                                List.of(
                                        new Person(
                                                "Sita",
                                                "COUSIN",
                                                null,
                                                new BigDecimal("100"),
                                                true)),
                                null,
                                RELATIONS,
                                TODAY,
                                "r"))
                .contains("RELATIONSHIP");
        assertThat(
                        LabourRecordRules.nominationDefect(
                                List.of(
                                        new Person(
                                                "Sita", null, null, new BigDecimal("100"), true)),
                                null,
                                RELATIONS,
                                TODAY,
                                "r"))
                .contains("RELATIONSHIP");
        assertThat(
                        LabourRecordRules.nominationDefect(
                                List.of(
                                        new Person(
                                                "Sita",
                                                "SPOUSE",
                                                TODAY.plusDays(1),
                                                new BigDecimal("100"),
                                                true)),
                                null,
                                RELATIONS,
                                TODAY,
                                "r"))
                .contains("BIRTH");
        assertThat(
                        LabourRecordRules.nominationDefect(
                                null,
                                List.of(new Person("Kiran", "UNCLE", null, null, false)),
                                RELATIONS,
                                TODAY,
                                "r"))
                .contains("RELATIONSHIP");
        assertThat(
                        LabourRecordRules.nominationDefect(
                                List.of(nominee("100")), null, RELATIONS, TODAY, ""))
                .contains("REASON");
    }

    /**
     * D22-8: a nominee sent without a name is refused as NAME, never passed on to the next check.
     */
    @Test
    void D22_8_aNomineeWithoutANameIsRefused() {
        assertThat(
                        LabourRecordRules.nominationDefect(
                                List.of(
                                        new Person(
                                                null, "SPOUSE", null, new BigDecimal("100"), true)),
                                null,
                                RELATIONS,
                                TODAY,
                                "r"))
                .contains("NAME");
    }

    /** D22-7 / R-ASCII: a reinstatement reason entered in a text area keeps its line breaks. */
    @Test
    void D22_7_aReasonOverSeveralLinesIsAccepted() {
        assertThat(
                        LabourRecordRules.reinstatementDefect(
                                "SEPARATED", null, TODAY, "Order\r\nof 1 May"))
                .isEmpty();
        assertThat(LabourRecordRules.reinstatementDefect("SEPARATED", null, TODAY, "Order\u0000"))
                .contains("REASON");
    }
}
