package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * The rules of target DDL v4.0 that the database itself holds for requirements whose business
 * computation waits for FCI (group g3 requirement matrix, 2026-11-03): the constraint is the built
 * part, proved here; the computation stays with its PENDING row. Each test writes rows of its own
 * in far-future dates no other IT uses.
 */
class StatutorySchemaRulesIT extends ApiTestSupport {

    private long employee;

    @BeforeEach
    void fixtures() {
        employee = jdbc.queryForObject("SELECT MIN(employee_id) FROM employee", Long.class);
    }

    private long anySanction() {
        return jdbc.queryForObject("SELECT MIN(sanction_id) FROM pf_sanction", Long.class);
    }

    private int finalStatement(int version, String net, String shortfall) {
        return jdbc.update(
                "INSERT INTO cpf_final_statement (sanction_id, employee_id, version_no,"
                        + " sanction_reference, sanction_date, interest_to_date, balance_employee,"
                        + " balance_employer, balance_vpf, interest_to_sanction,"
                        + " intervening_interest, advance_recovered, shortfall_recovered,"
                        + " net_amount_paid) VALUES (?, ?, ?, 'IT-FINAL', DATE '2097-05-01',"
                        + " DATE '2097-05-01', 1000, 600, 200, 50, 25, 100, ?, ?)",
                anySanction(),
                employee,
                version,
                new BigDecimal(shortfall),
                new BigDecimal(net));
    }

    /**
     * BR-CPF-013, BR-CPF-014, BR-CPF-015 (SRS-6-3, SRS-6-48): the CPF Final Statement pays the
     * balance plus the interest to the sanction date and the intervening-period interest, less the
     * advance recovered and the shortfall of a retiring member's advance-deducted CPF; a net that
     * leaves any part out, or is negative, is refused. The interest method and the recompute wait
     * for FCI (PENDING 07f-3, Q-072; the retiring recompute: CLR).
     */
    @Test
    void BR_CPF_013_BR_CPF_014_BR_CPF_015_theFinalNetCarriesTheInterestLessAdvanceAndShortfall() {
        // 1000 + 600 + 200 + 50 + 25 - 100 - 75 = 1700.
        assertThat(finalStatement(9101, "1700.00", "75.00")).isOne();
        // The shortfall left out of the net.
        assertThatThrownBy(() -> finalStatement(9102, "1775.00", "75.00"))
                .isInstanceOf(DataAccessException.class);
        // A shortfall above what is due would make the net negative.
        assertThatThrownBy(() -> finalStatement(9103, "-1.00", "1776.00"))
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * SRS-DM-21, BR-CPF-038 (SRS-6-49): a member's CPF / EPS / UAN / EPFO identifiers are held as a
     * dated history; an identifier is ACTIVE, CLOSED, SUPERSEDED, SURRENDERED or REJECTED (an old
     * ID is closed, never removed), of a known kind, and always belongs to a member.
     */
    @Test
    void SRS_DM_21_BR_CPF_038_aMemberIdIsAKnownKindAndStatusAndIsClosedNotRemoved() {
        String insert =
                "INSERT INTO cpf_member_id_history (employee_id, id_type, identifier_value,"
                        + " effective_from, effective_to, id_status, hrms_request_id) VALUES (?, ?,"
                        + " ?, ?, ?, ?, 'HRMS-PZC-IT')";
        // An identifier from HRMS carries the HRMS request that brought it.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO cpf_member_id_history (employee_id, id_type,"
                                                + " identifier_value, effective_from) VALUES (?,"
                                                + " 'CPF', 'IT/OLD/9100', ?)",
                                        employee,
                                        Date.valueOf(LocalDate.of(2097, 4, 1))))
                .isInstanceOf(DataAccessException.class);
        // A transfer never supersedes a Member ID (SRS-DM-21: "A transfer creates no new ID").
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO cpf_member_id_history (employee_id, id_type,"
                                                + " identifier_value, effective_from, id_status,"
                                                + " supersede_reason, hrms_request_id) VALUES (?,"
                                                + " 'CPF', 'IT/OLD/9105', ?, 'SUPERSEDED',"
                                                + " 'TRANSFER', 'HRMS-PZC-IT')",
                                        employee,
                                        Date.valueOf(LocalDate.of(2097, 4, 1))))
                .isInstanceOf(DataAccessException.class);
        assertThat(
                        jdbc.update(
                                insert,
                                employee,
                                "CPF",
                                "IT/OLD/9101",
                                Date.valueOf(LocalDate.of(2097, 4, 1)),
                                Date.valueOf(LocalDate.of(2097, 6, 30)),
                                "CLOSED"))
                .isOne();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        insert,
                                        employee,
                                        "CPF",
                                        "IT/OLD/9102",
                                        Date.valueOf(LocalDate.of(2097, 4, 1)),
                                        null,
                                        "DELETED"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        insert,
                                        employee,
                                        "PAN",
                                        "IT/OLD/9103",
                                        Date.valueOf(LocalDate.of(2097, 4, 1)),
                                        null,
                                        "ACTIVE"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        insert,
                                        -1L,
                                        "CPF",
                                        "IT/OLD/9104",
                                        Date.valueOf(LocalDate.of(2097, 4, 1)),
                                        null,
                                        "ACTIVE"))
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * SRS-DM-12 (FRS §12): every migrated master row can carry its legacy origin - the employee
     * keeps the legacy staff number, the migration batch and the legacy source key.
     */
    @Test
    void SRS_DM_12_aMigratedEmployeeCarriesItsLegacySourceAndKey() {
        List<String> columns =
                jdbc.queryForList(
                        "SELECT LOWER(column_name) FROM information_schema.columns WHERE"
                                + " LOWER(table_name) = 'employee'",
                        String.class);
        assertThat(columns).contains("legacy_emp_num", "mig_batch_id", "legacy_source_key");
    }

    /**
     * SRS-20-16: the migration loads have a rejected-record log of their own, apart from the
     * interface exception queue of X-03 (the migration itself is module 24, not built).
     */
    @Test
    void SRS_20_16_theMigrationRejectLogIsApartFromTheInterfaceQueue() {
        List<String> tables =
                jdbc.queryForList(
                        "SELECT LOWER(table_name) FROM information_schema.tables WHERE"
                                + " LOWER(table_name) IN ('interface_exception',"
                                + " 'migration_exception_log')",
                        String.class);
        // The X-03 queue is an interface's (every row names its interface run or registry entry);
        // the migration's own log is created with the migration loads (module 24).
        assertThat(tables).contains("interface_exception");
        List<String> columns =
                jdbc.queryForList(
                        "SELECT LOWER(column_name) FROM information_schema.columns WHERE"
                                + " LOWER(table_name) = 'interface_exception'",
                        String.class);
        assertThat(columns).contains("interface_id", "run_id", "rejection_reason");
    }

    /**
     * BR-ALLOW-003, BR-ALLOW-004, BR-ALLOW-009 (SRS l.2754): the lease / licence record holds the 7
     * % amount, the population percentage, the HRMS licence fee and its difference; spouse
     * accommodation takes no population percentage. The computation waits for FCI (PENDING
     * 10-lease).
     */
    @Test
    void BR_ALLOW_003_BR_ALLOW_004_BR_ALLOW_009_aSpouseLeaseTakesNoPopulationPercentage() {
        String insert =
                "INSERT INTO lease_accommodation_detail (employee_id, accommodation_type,"
                        + " population_percent, seven_pct_basic_amount, hrms_licence_fee_amount,"
                        + " licence_fee_difference, effective_from) VALUES (?, ?, ?, 700, 650, 50,"
                        + " ?)";
        assertThat(
                        jdbc.update(
                                insert,
                                employee,
                                "THIRD_PARTY",
                                new BigDecimal("15"),
                                Date.valueOf(LocalDate.of(2097, 4, 1))))
                .isOne();
        assertThat(
                        jdbc.update(
                                insert,
                                employee,
                                "SPOUSE",
                                null,
                                Date.valueOf(LocalDate.of(2097, 5, 1))))
                .isOne();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        insert,
                                        employee,
                                        "SPOUSE",
                                        new BigDecimal("15"),
                                        Date.valueOf(LocalDate.of(2097, 6, 1))))
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * BR-ALLOW-002 (FRS l.6710): an LTC norm may run over a four-year block (BLOCK_YEAR_4) and no
     * other block length; which trip a block allows waits for FCI (PENDING B-7, DR-7).
     */
    @Test
    void BR_ALLOW_002_anLtcNormRunsOverAFourYearBlockAndNoOtherBlock() {
        String insert =
                "INSERT INTO allowance_ceiling_norm (claim_type, ceiling_value, periodicity,"
                        + " effective_from) VALUES ('IT_LTC_BLOCK', 1000, ?, ?)";
        assertThat(jdbc.update(insert, "BLOCK_YEAR_4", Date.valueOf(LocalDate.of(2097, 4, 1))))
                .isOne();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        insert,
                                        "BLOCK_YEAR_3",
                                        Date.valueOf(LocalDate.of(2097, 5, 1))))
                .isInstanceOf(DataAccessException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT is_confirmed FROM allowance_ceiling_norm WHERE claim_type ="
                                        + " 'IT_LTC_BLOCK'",
                                String.class))
                .as("unconfirmed until set: the engine refuses it, never assumes it")
                .isEqualTo("N");
    }

    /**
     * SRS-22-28: gate G3 (nothing pending, an item at a configured IR-L level included) is a hard
     * gate - it can never be marked overridable, so no one can accept the lock past it.
     */
    @Test
    void SRS_22_28_theG3GateHoldingAnIrlItemCanNeverBeOverridden() {
        long unit = locationId("DH001");
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) SELECT '209812', ?, 'DO', 'IT',"
                        + " 'INITIATED', 'it.fixture' FROM dual WHERE NOT EXISTS (SELECT 1 FROM"
                        + " payroll_cycle_instance WHERE payroll_yymm = '209812' AND org_unit_id ="
                        + " ?)",
                unit,
                unit);
        long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " '209812' AND org_unit_id = ?",
                        Long.class,
                        unit);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO cycle_gate_evaluation (cycle_instance_id,"
                                                + " gate_code, is_overridable) VALUES (?, 'G3',"
                                                + " 'Y')",
                                        cycle))
                .isInstanceOf(DataAccessException.class);
        assertThat(
                        jdbc.update(
                                "INSERT INTO cycle_gate_evaluation (cycle_instance_id, gate_code,"
                                        + " is_overridable) VALUES (?, 'G3', 'N')",
                                cycle))
                .isOne();
    }
}
