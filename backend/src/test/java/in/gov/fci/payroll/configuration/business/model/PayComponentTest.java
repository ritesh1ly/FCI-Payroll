package in.gov.fci.payroll.configuration.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.configuration.business.ComponentRules;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** M-07 catalogue entries (SRS-19-1; plan 06 phase 06d, D-9): define, revise, retire. */
class PayComponentTest {

    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);

    private static ComponentRules.Definition definition(String name, Set<String> types) {
        return new ComponentRules.Definition(
                913, name, "EARNING", "ALLOW", null, "CALC", "4100", "Y", true, types, FROM);
    }

    @Test
    void SRS_19_1_aDefinedCodeIsVersionOneLiveAndLabourOnlyWhenOnlyLabourUsesIt() {
        PayComponent c = PayComponent.defined(definition("  Claim  ", Set.of("STAFF")));
        assertThat(c.getLegacyPayCode()).isEqualTo(913);
        assertThat(c.getVersionNo()).isEqualTo(1);
        assertThat(c.getComponentName()).isEqualTo("Claim");
        assertThat(c.getComponentType()).isEqualTo("EARNING");
        assertThat(c.getSourceCode()).isEqualTo("CALC");
        assertThat(c.getTaxableFlag()).isEqualTo("Y");
        assertThat(c.isInCpfBaseFlag()).isTrue();
        assertThat(c.isLabourSpecificFlag()).isFalse();
        assertThat(c.isSuppExcludedFlag()).isFalse();
        assertThat(c.isIsRetired()).isFalse();
        assertThat(c.getEffectiveFrom()).isEqualTo(FROM);
        c.revised(definition("Labour claim", Set.of("DEPT_LABOUR")));
        assertThat(c.getVersionNo()).isEqualTo(2);
        assertThat(c.getComponentName()).isEqualTo("Labour claim");
        assertThat(c.isLabourSpecificFlag()).isTrue();
        ReflectionTestUtils.setField(c, "versionNo", null);
        c.revised(definition("Claim", Set.of("STAFF")));
        assertThat(c.getVersionNo()).isEqualTo(1);
        c.retired(FROM.plusDays(5));
        assertThat(c.isIsRetired()).isTrue();
        assertThat(c.getEffectiveTo()).isEqualTo(FROM.plusDays(5));
    }

    /**
     * BR-CYC-011 (UI audit S7, Wave D review 3): a revision never moves the code's start later, so
     * the code stays in force for every day before the revision; the same day keeps it, an earlier
     * date brings it forward.
     */
    @Test
    void BR_CYC_011_aRevisionKeepsTheCodeInForceForEarlierDays() {
        PayComponent c = PayComponent.defined(definition("Claim", Set.of("STAFF")));
        c.revised(
                new ComponentRules.Definition(
                        913,
                        "Claim",
                        "EARNING",
                        "ALLOW",
                        null,
                        "CALC",
                        "4100",
                        "Y",
                        true,
                        Set.of("STAFF"),
                        FROM.plusMonths(6)));
        assertThat(c.getEffectiveFrom()).isEqualTo(FROM);
        assertThat(c.isActiveOn(FROM)).isTrue();
        assertThat(c.isActiveOn(FROM.plusMonths(3))).isTrue();
        assertThat(c.isActiveOn(FROM.minusDays(1))).isFalse();
        c.revised(definition("Claim", Set.of("STAFF")));
        assertThat(c.getEffectiveFrom()).isEqualTo(FROM);
        c.revised(
                new ComponentRules.Definition(
                        913,
                        "Claim",
                        "EARNING",
                        "ALLOW",
                        null,
                        "CALC",
                        "4100",
                        "Y",
                        true,
                        Set.of("STAFF"),
                        FROM.minusDays(1)));
        assertThat(c.getEffectiveFrom()).isEqualTo(FROM.minusDays(1));
        ReflectionTestUtils.setField(c, "effectiveFrom", null);
        c.revised(definition("Claim", Set.of("STAFF")));
        assertThat(c.getEffectiveFrom()).isEqualTo(FROM);
    }

    @Test
    void SRS_LV_15_theSubjectToLwpAttributeIsWhatTheDefinitionSays() {
        // US-GAP-298 (BR-LEAVE-007): marked on M-07; not named means not subject to LWP.
        PayComponent c = PayComponent.defined(definition("Basic", Set.of("STAFF")));
        assertThat(c.isLwpSubjectFlag()).isFalse();
        c.revised(definition("Basic", Set.of("STAFF")).withSubjectToLwp(true));
        assertThat(c.isLwpSubjectFlag()).isTrue();
        c.revised(definition("Basic", Set.of("STAFF")).withSubjectToLwp(false));
        assertThat(c.isLwpSubjectFlag()).isFalse();
        assertThat(definition("Basic", Set.of("STAFF")).subjectToLwp()).isNull();
    }
}
