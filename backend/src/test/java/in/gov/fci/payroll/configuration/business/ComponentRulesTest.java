package in.gov.fci.payroll.configuration.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** M-07 Component Catalogue rules (plan 06 phase 06d; SRS-G-42, FAP-14). */
class ComponentRulesTest {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);

    private static ComponentRules.Definition good() {
        return new ComponentRules.Definition(
                913,
                "Demo washing allowance",
                "EARNING",
                "ALLOWANCES",
                "DEMO.WASHING",
                "RULE",
                "NA-4101",
                "PARTLY",
                false,
                Set.of("STAFF"),
                FROM);
    }

    private static ComponentRules.Definition with(
            int code,
            String name,
            String type,
            String group,
            String basis,
            String source,
            String account,
            String taxable,
            Set<String> types,
            LocalDate from) {
        return new ComponentRules.Definition(
                code, name, type, group, basis, source, account, taxable, true, types, from);
    }

    @Test
    void SRS_G_42_aWellFormedDefinitionHasNoDefect() {
        assertThat(ComponentRules.defect(good())).isEmpty();
        assertThat(
                        ComponentRules.defect(
                                with(
                                        1,
                                        "A",
                                        "DEDUCTION",
                                        null,
                                        "DEMO.WASHING",
                                        "INTF",
                                        "A1",
                                        "N",
                                        Set.of("NWNP_LABOUR"),
                                        FROM)))
                .isEmpty();
        assertThat(
                        ComponentRules.defect(
                                with(
                                        999,
                                        "x".repeat(100),
                                        "EARNING",
                                        "A",
                                        "A",
                                        "ENT",
                                        "A" + "1".repeat(19),
                                        "Y",
                                        Set.of("STAFF", "DEPT_LABOUR", "DPS_LABOUR", "NWNP_LABOUR"),
                                        FROM)))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1000, -1})
    void SRS_G_42_aPayCodeIsOneToThreeDigits(int code) {
        assertThat(
                        ComponentRules.defect(
                                with(
                                        code,
                                        "A",
                                        "EARNING",
                                        null,
                                        null,
                                        "RULE",
                                        "NA-1",
                                        "Y",
                                        Set.of("STAFF"),
                                        FROM)))
                .contains("the pay code is a number of up to three digits");
    }

    @Test
    void SRS_G_42_eachBadFieldIsSaid() {
        Set<String> staff = Set.of("STAFF");
        assertThat(
                        ComponentRules.defect(
                                with(
                                        5,
                                        null,
                                        "EARNING",
                                        null,
                                        "DEMO.WASHING",
                                        "RULE",
                                        "NA-1",
                                        "Y",
                                        staff,
                                        FROM)))
                .contains("the component name is required, printable, at most 100 characters");
        assertThat(
                        ComponentRules.defect(
                                with(
                                        5,
                                        "  ",
                                        "EARNING",
                                        null,
                                        "DEMO.WASHING",
                                        "RULE",
                                        "NA-1",
                                        "Y",
                                        staff,
                                        FROM)))
                .contains("the component name is required, printable, at most 100 characters");
        assertThat(
                        ComponentRules.defect(
                                with(
                                        5,
                                        "x".repeat(101),
                                        "EARNING",
                                        null,
                                        "DEMO.WASHING",
                                        "RULE",
                                        "NA-1",
                                        "Y",
                                        staff,
                                        FROM)))
                .contains("the component name is required, printable, at most 100 characters");
        assertThat(
                        ComponentRules.defect(
                                with(
                                        5,
                                        "Bhatta भ",
                                        "EARNING",
                                        null,
                                        "DEMO.WASHING",
                                        "RULE",
                                        "NA-1",
                                        "Y",
                                        staff,
                                        FROM)))
                .contains("the component name is required, printable, at most 100 characters");
        assertThat(
                        ComponentRules.defect(
                                with(
                                        5, "A", "BONUS", null, null, "RULE", "NA-1", "Y", staff,
                                        FROM)))
                .contains("the component type is an earning or a deduction");
        assertThat(
                        ComponentRules.defect(
                                with(
                                        5, "A", "EARNING", "pay", null, "RULE", "NA-1", "Y", staff,
                                        FROM)))
                .contains("the payslip group is a code of capital letters");
        assertThat(
                        ComponentRules.defect(
                                with(
                                        5,
                                        "A",
                                        "EARNING",
                                        null,
                                        "da staff",
                                        "RULE",
                                        "NA-1",
                                        "Y",
                                        staff,
                                        FROM)))
                .contains("the basis is a rule key of the catalogue (M-08)");
        assertThat(
                        ComponentRules.defect(
                                with(
                                        5,
                                        "A",
                                        "EARNING",
                                        null,
                                        "DEMO.WASHING",
                                        "MANUAL",
                                        "NA-1",
                                        "Y",
                                        staff,
                                        FROM)))
                .contains("the source of value is one of ENT, CALC, HRMS, RULE, SYS, ESS, INTF");
        assertThat(
                        ComponentRules.defect(
                                with(
                                        5,
                                        "A",
                                        "EARNING",
                                        null,
                                        "DEMO.WASHING",
                                        "RULE",
                                        null,
                                        "Y",
                                        staff,
                                        FROM)))
                .contains("the natural account is required, capital letters, digits or '-'");
        assertThat(
                        ComponentRules.defect(
                                with(
                                        5,
                                        "A",
                                        "EARNING",
                                        null,
                                        "DEMO.WASHING",
                                        "RULE",
                                        "-1",
                                        "Y",
                                        staff,
                                        FROM)))
                .contains("the natural account is required, capital letters, digits or '-'");
        assertThat(
                        ComponentRules.defect(
                                with(
                                        5,
                                        "A",
                                        "EARNING",
                                        null,
                                        "DEMO.WASHING",
                                        "RULE",
                                        "NA-1",
                                        "YES",
                                        staff,
                                        FROM)))
                .contains("taxable is Y, N or PARTLY");
        assertThat(
                        ComponentRules.defect(
                                with(
                                        5,
                                        "A",
                                        "EARNING",
                                        null,
                                        "DEMO.WASHING",
                                        "RULE",
                                        "NA-1",
                                        "Y",
                                        null,
                                        FROM)))
                .contains("choose at least one payroll type of Employee, Departmental, DPS, NWNP");
        assertThat(
                        ComponentRules.defect(
                                with(
                                        5,
                                        "A",
                                        "EARNING",
                                        null,
                                        "DEMO.WASHING",
                                        "RULE",
                                        "NA-1",
                                        "Y",
                                        Set.of(),
                                        FROM)))
                .contains("choose at least one payroll type of Employee, Departmental, DPS, NWNP");
        assertThat(
                        ComponentRules.defect(
                                with(
                                        5,
                                        "A",
                                        "EARNING",
                                        null,
                                        "DEMO.WASHING",
                                        "RULE",
                                        "NA-1",
                                        "Y",
                                        Set.of("STAFF", "CASUAL"),
                                        FROM)))
                .contains("choose at least one payroll type of Employee, Departmental, DPS, NWNP");
        assertThat(
                        ComponentRules.defect(
                                with(
                                        5,
                                        "A",
                                        "EARNING",
                                        null,
                                        "DEMO.WASHING",
                                        "RULE",
                                        "NA-1",
                                        "Y",
                                        staff,
                                        null)))
                .contains("the effective-from date is required");
    }

    // SC-06-0038: a labour code is not used on the Employee / Staff engine.
    @Test
    void FAP_14_aCodeWithoutTheStaffTypeIsLabourOnly() {
        assertThat(ComponentRules.labourOnly(Set.of("DPS_LABOUR", "NWNP_LABOUR"))).isTrue();
        assertThat(ComponentRules.labourOnly(Set.of("STAFF", "DPS_LABOUR"))).isFalse();
    }

    /** SRS l.1907, l.1925, SC-06-0026: Driver / Basis is mandatory. */
    @Test
    void SRS_l1907_aDefinitionWithoutADriverOrBasisIsRefused() {
        assertThat(
                        ComponentRules.defect(
                                with(
                                        1,
                                        "A",
                                        "EARNING",
                                        null,
                                        null,
                                        "RULE",
                                        "A1",
                                        "N",
                                        Set.of("STAFF"),
                                        FROM)))
                .isPresent();
    }

    /** SC-06-0028: the Eligibility Flag is optional, a flag code when given. */
    @Test
    void SC_06_0028_anEligibilityFlagIsOptionalAndAFlagCode() {
        ComponentRules.Definition d = good();
        ComponentRules.Definition flagged =
                new ComponentRules.Definition(
                        d.payCode(),
                        d.name(),
                        d.type(),
                        d.payslipGroup(),
                        d.basisRuleKey(),
                        d.source(),
                        d.naturalAccount(),
                        d.taxable(),
                        d.inCpfBase(),
                        d.payrollTypes(),
                        d.effectiveFrom(),
                        false,
                        "SPL_PAY_*");
        assertThat(ComponentRules.defect(flagged)).isEmpty();
        assertThat(
                        ComponentRules.defect(
                                new ComponentRules.Definition(
                                        d.payCode(),
                                        d.name(),
                                        d.type(),
                                        d.payslipGroup(),
                                        d.basisRuleKey(),
                                        d.source(),
                                        d.naturalAccount(),
                                        d.taxable(),
                                        d.inCpfBase(),
                                        d.payrollTypes(),
                                        d.effectiveFrom(),
                                        false,
                                        "free text")))
                .isPresent();
    }
}
