package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.PreparedStatement;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * Module 08 phase 08a: the income-tax and output-definition tables of target DDL v4.0 (V039_1) hold
 * their own invariants, so a defect in a service or a direct write never breaks them. The service
 * checks first; the database is the last line. Each test writes far-future, unique rows of its own.
 */
class TaxSchemaIT extends ApiTestSupport {

    private long employee;

    @BeforeEach
    void fixtures() {
        employee = jdbc.queryForObject("SELECT MIN(employee_id) FROM employee", Long.class);
    }

    /** SRS-7-1, BR-TAX-001: one regime election per member and financial year, Old or New only. */
    @Test
    void BR_TAX_001_oneRegimeElectionPerMemberAndYearAndOnlyOldOrNew() {
        jdbc.update(
                "INSERT INTO it_regime_detail (employee_id, fin_year, regime_code) VALUES (?,"
                        + " '2091-92', 'N')",
                employee);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO it_regime_detail (employee_id, fin_year,"
                                                + " regime_code) VALUES (?, '2091-92', 'O')",
                                        employee))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO it_regime_detail (employee_id, fin_year,"
                                                + " regime_code) VALUES (?, '2092-93', 'X')",
                                        employee))
                .isInstanceOf(DataIntegrityViolationException.class);
        // The election starts unverified and unlocked, made in eSeva (BR-TAX-014).
        assertThat(
                        jdbc.queryForMap(
                                "SELECT verify_status, locked_flag, elected_via FROM"
                                        + " it_regime_detail WHERE employee_id = ? AND fin_year ="
                                        + " '2091-92'",
                                employee))
                .containsValues("SUBMITTED", "N", "ESS");
    }

    /**
     * SRS-7-16, BR-TAX-002: the allowed amount never exceeds the declared one; one line per section
     * and kind in a submission, so a resubmission keeps the earlier evidence.
     */
    @Test
    void BR_TAX_002_theAllowedAmountNeverExceedsTheDeclaredAndALineIsUniquePerSubmission() {
        long first = submission("2093-94", 1);
        long second = submission("2093-94", 2);
        declaration(first, "80C", "150000.00", "150000.00");
        assertThatThrownBy(() -> declaration(first, "80D", "25000.00", "25000.01"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> declaration(first, "80C", "100.00", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        // The same section in the next submission is kept beside the first (Form 12BB evidence).
        declaration(second, "80C", "160000.00", "150000.00");
        // Migrated lines carry no submission and are unique among themselves too.
        declaration(null, "80C", "1.00", null);
        assertThatThrownBy(() -> declaration(null, "80C", "2.00", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** SRS-7-9, BR-TAX-010: a Form 16 never reaches RELEASED without the merge of Part A. */
    @Test
    void BR_TAX_010_aForm16IsNeverReleasedWithoutTheMergedPartA() {
        jdbc.update(
                "INSERT INTO form16 (employee_id, fin_year, gross_salary, tax_deducted,"
                        + " release_status) VALUES (?, '2094-95', 100.00, 10.00, 'PENDING_PART_A')",
                employee);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE form16 SET release_status = 'RELEASED' WHERE"
                                                + " employee_id = ? AND fin_year = '2094-95'",
                                        employee))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(
                "UPDATE form16 SET merged_at = CURRENT_TIMESTAMP, release_status = 'RELEASED'"
                        + " WHERE employee_id = ? AND fin_year = '2094-95'",
                employee);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT release_status FROM form16 WHERE employee_id = ? AND"
                                        + " fin_year = '2094-95'",
                                String.class,
                                employee))
                .isEqualTo("RELEASED");
    }

    /**
     * SRS-7-7: one original Form 24Q per year, quarter and office, also when no office is named; a
     * correction return is kept beside the return it corrects.
     */
    @Test
    void SRS_7_7_oneOriginalReturnPerQuarterAndOfficeAndCorrectionsBesideIt() {
        long original = statutoryReturn("FORM_24Q", "N", null);
        assertThatThrownBy(() -> statutoryReturn("FORM_24Q", "N", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        long correction = statutoryReturn("FORM_24Q_CORRECTION", "Y", original);
        assertThat(correction).isNotEqualTo(original);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE statutory_return SET return_status = 'SENT' WHERE"
                                                + " return_id = ?",
                                        original))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * BR-TAX-013, SRS-19-2, BR-MIS-005, SRS-G-12, BR-MIS-007, BR-CYC-007: an output definition
     * field binds to the component catalogue or the field-source model; a blocked version states
     * why; a simulation output is never released and a provisional one never issued.
     */
    @Test
    void BR_TAX_013_aBindingMustResolveABlockedVersionStatesWhyAndASimulationIsNeverReleased() {
        long definition = definition("SCHEMA_IT_OUTPUT");
        assertThatThrownBy(() -> version(definition, 1, "Y", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        long version = version(definition, 1, "Y", "Layout not supplied (OQ-23)");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO report_field_binding (report_version_id,"
                                                + " field_order, field_label, binding_type) VALUES"
                                                + " (?, 1, 'Basic', 'COMPONENT')",
                                        version))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(
                "INSERT INTO report_field_binding (report_version_id, field_order, field_label,"
                        + " binding_type) VALUES (?, 1, 'Total', 'AGGREGATE')",
                version);
        assertThatThrownBy(() -> output(version, "SIMULATION", "N", "RELEASED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> output(version, "ACTUAL", "Y", "ISSUED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        output(version, "SIMULATION", "N", "GENERATED");
        output(version, "ACTUAL", "N", "ISSUED");
    }

    private long submission(String finYear, int number) {
        return insert(
                "INSERT INTO it_declaration_submission (employee_id, fin_year, submission_no)"
                        + " VALUES (?, ?, ?)",
                "submission_id",
                employee,
                finYear,
                number);
    }

    private void declaration(Long submission, String section, String declared, String allowed) {
        jdbc.update(
                "INSERT INTO it_declaration (employee_id, submission_id, fin_year, section_code,"
                        + " declared_amount, allowed_amount) VALUES (?, ?, '2093-94', ?, ?, ?)",
                employee,
                submission,
                section,
                new java.math.BigDecimal(declared),
                allowed == null ? null : new java.math.BigDecimal(allowed));
    }

    private long statutoryReturn(String type, String correction, Long corrects) {
        return insert(
                "INSERT INTO statutory_return (return_type, fin_year, period_code, is_correction,"
                        + " corrects_return_id) VALUES (?, '2095-96', 'Q1', ?, ?)",
                "return_id",
                type,
                correction,
                corrects);
    }

    private long definition(String key) {
        return insert(
                "INSERT INTO report_definition (report_key, report_name, report_family,"
                        + " catalogue_section, producing_screen_code, output_domain) VALUES (?,"
                        + " 'Schema IT output', 'Statutory & Tax', 'A.4.2', 'M-17',"
                        + " 'STATUTORY_OUTPUT')",
                "report_def_id",
                key);
    }

    private long version(long definition, int number, String blocked, String reason) {
        return insert(
                "INSERT INTO report_definition_version (report_def_id, version_no, effective_from,"
                        + " is_blocked, blocked_reason, created_by) VALUES (?, ?, DATE"
                        + " '2096-04-01', ?, ?, 'schema.it')",
                "report_version_id",
                definition,
                number,
                blocked,
                reason);
    }

    private void output(long version, String runType, String provisional, String status) {
        jdbc.update(
                "INSERT INTO report_output (report_version_id, report_key, requested_by, run_type,"
                        + " is_provisional, output_status) VALUES (?, 'SCHEMA_IT_OUTPUT',"
                        + " 'schema.it', ?, ?, ?)",
                version,
                runType,
                provisional,
                status);
    }

    private long insert(String sql, String key, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement statement =
                            connection.prepareStatement(sql, new String[] {key});
                    for (int i = 0; i < args.length; i++) {
                        statement.setObject(i + 1, args[i]);
                    }
                    return statement;
                },
                keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
}
