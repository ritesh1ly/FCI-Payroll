package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The rules of target DDL v4.0 the database holds for labour and integration requirements whose
 * computation or interface waits (group g5 requirement matrix, 2026-11-03; the playbook's
 * "requirement matrix" row): the constraint is the built part, proved here; the computation stays
 * with its PENDING row (LBR-20d-2, LBR-20d-3, LBR-20e-4, API-23-1, API-23-3). Each test writes its
 * rows in a transaction that is rolled back, so no other suite sees them.
 */
class LabourIntegrationSchemaIT extends ApiTestSupport {

    @Autowired private PlatformTransactionManager transactions;

    private long employee;
    private long zone;
    private long location;

    /** Runs the checks in a transaction that is rolled back (no row stays for other suites). */
    private void rolledBack(Runnable checks) {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        s -> {
                            s.setRollbackOnly();
                            checks.run();
                        });
    }

    @BeforeEach
    void fixtures() {
        employee = jdbc.queryForObject("SELECT MIN(employee_id) FROM employee", Long.class);
        zone = jdbc.queryForObject("SELECT MIN(zone_id) FROM org_zone", Long.class);
        location = jdbc.queryForObject("SELECT MIN(location_id) FROM org_location", Long.class);
    }

    private int overtime(String start, String end) {
        return jdbc.update(
                "INSERT INTO labour_overtime (employee_id, depot_location_id, ot_date,"
                    + " operation_id, start_at, end_at, created_by, period_date) SELECT ?, ?, DATE"
                    + " '2097-03-14', MIN(operation_id), TIMESTAMP '"
                        + start
                        + "', TIMESTAMP '"
                        + end
                        + "', 'it', DATE '2097-03-01' FROM operation_master",
                employee,
                location);
    }

    /**
     * BR-LABR-004 (no double OTA on a transfer day): a labour has one overtime row for a start time
     * in a month (DDL uk_labour_overtime), so the OTA of a transfer day is held once whichever gang
     * records it; the OTA computation waits for FCI (PENDING LBR-20c-1, LBR-20e-4).
     */
    @Test
    void BR_LABR_004_aLabourHasOneOvertimeRowPerStart() {
        rolledBack(
                () -> {
                    assertThat(overtime("2097-03-14 18:00:00", "2097-03-14 20:00:00")).isOne();
                    assertThatThrownBy(() -> overtime("2097-03-14 18:00:00", "2097-03-14 21:00:00"))
                            .as("the same start again, for another gang")
                            .isInstanceOf(DataAccessException.class);
                    assertThat(overtime("2097-03-14 20:00:00", "2097-03-14 22:00:00")).isOne();
                    assertThatThrownBy(() -> overtime("2097-03-14 23:00:00", "2097-03-14 22:00:00"))
                            .as("an end before its start")
                            .isInstanceOf(DataAccessException.class);
                });
    }

    private int iocpf(String number, String reason) {
        return jdbc.update(
                "INSERT INTO iocpf_register (iocpf_number, employee_id, iocpf_reason, from_zone_id,"
                        + " to_zone_id, amount_eps, total_amount) VALUES (?, ?, ?, ?, ?, 120, 120)",
                number,
                employee,
                reason,
                zone,
                zone);
    }

    /**
     * BR-LABR-006 (a labour's death in the month: the EPS portion of the employer contribution goes
     * to the Zonal Office by IOCPF): the IOCPF register takes the reason LABOUR_DEATH_EPS and no
     * unknown one; the split itself comes with the labour computation (PENDING LBR-20d-3).
     */
    @Test
    void BR_LABR_006_theIocpfRegisterTakesALabourDeathEpsReasonAndNoOther() {
        rolledBack(
                () -> {
                    assertThat(iocpf("IT-G5-IOCPF-1", "LABOUR_DEATH_EPS")).isOne();
                    assertThatThrownBy(() -> iocpf("IT-G5-IOCPF-2", "LABOUR_DEATH"))
                            .isInstanceOf(DataAccessException.class);
                    assertThatThrownBy(() -> iocpf("IT-G5-IOCPF-3", "PARENT_ZONE_CHANGE"))
                            .as("a parent-zone change needs its change record")
                            .isInstanceOf(DataAccessException.class);
                });
    }

    private int transfer(String flag) {
        return flag == null
                ? jdbc.update(
                        "INSERT INTO employee_transfer (employee_id, to_location_id, transfer_date)"
                                + " VALUES (?, ?, DATE '2097-04-01')",
                        employee,
                        location)
                : jdbc.update(
                        "INSERT INTO employee_transfer (employee_id, to_location_id, transfer_date,"
                                + " split_wage_flag) VALUES (?, ?, DATE '2097-04-02', ?)",
                        employee,
                        location,
                        flag);
    }

    /**
     * SRS-2-14 (the month's wage split between the releasing and receiving units by the period
     * served): a transfer carries its split flag, Y or N, N when not given; the split itself comes
     * with the labour computation (PENDING LBR-20e-4).
     */
    @Test
    void SRS_2_14_theTransferCarriesASplitWageFlagYOrNDefaultN() {
        rolledBack(
                () -> {
                    assertThat(transfer(null)).isOne();
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT split_wage_flag FROM employee_transfer WHERE"
                                                    + " transfer_date = DATE '2097-04-01' AND"
                                                    + " employee_id = ?",
                                            String.class,
                                            employee))
                            .isEqualTo("N");
                    assertThat(transfer("Y")).isOne();
                    assertThatThrownBy(() -> transfer("S")).isInstanceOf(DataAccessException.class);
                });
    }

    private int field(String name, String source, String editable) {
        return jdbc.update(
                "INSERT INTO field_source_registry (entity_name, field_name, screen_code,"
                        + " source_code, owning_system, is_editable, requires_effective_date,"
                        + " requires_reason, ownership_confirmed, description) VALUES"
                        + " ('it_g5_entity', ?, 'M-01', ?, ?, ?, 'N', 'N', 'Y', 'IT field')",
                name,
                source,
                "HRMS".equals(source) ? "HRMS" : "PAYROLL",
                editable);
    }

    /**
     * BR-INT-001 (one source per attribute; an HRMS value is never edited in Payroll) and
     * BR-INT-010 (no edit while the HRMS integration is active): an HRMS field cannot be registered
     * editable; a Payroll field can; one field has exactly one source; every I-21 field is HRMS and
     * read-only.
     */
    @Test
    void BR_INT_001_BR_INT_010_anHrmsFieldCannotBeMadeEditable() {
        rolledBack(
                () -> {
                    assertThatThrownBy(() -> field("hrms_editable", "HRMS", "Y"))
                            .isInstanceOf(DataAccessException.class);
                    assertThat(field("hrms_read_only", "HRMS", "N")).isOne();
                    assertThat(field("entered", "ENT", "Y")).isOne();
                    assertThatThrownBy(() -> field("entered", "CALC", "N"))
                            .as("one source per attribute")
                            .isInstanceOf(DataAccessException.class);
                    Integer editableHrms =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM field_source_registry WHERE source_code ="
                                            + " 'HRMS' AND is_editable <> 'N'",
                                    Integer.class);
                    assertThat(editableHrms).isZero();
                });
    }

    private int staged(String key) {
        return jdbc.update(
                "INSERT INTO intf_staging_txn (txn_type, request_id, payload) VALUES"
                        + " ('IT_G5_TXN', ?, '{}')",
                key);
    }

    /**
     * BR-INT-004 (idempotency: a record whose source key was already applied is rejected as a
     * duplicate, never applied twice) and BR-INT-003 (every record carries one status): the staging
     * table holds one record per interface type and source key, in a known status.
     */
    @Test
    void BR_INT_004_BR_INT_003_aRepeatedSourceKeyIsRefusedAndAStatusIsKnown() {
        rolledBack(
                () -> {
                    assertThat(staged("IT-G5-KEY-1")).isOne();
                    assertThatThrownBy(() -> staged("IT-G5-KEY-1"))
                            .isInstanceOf(DataAccessException.class);
                    assertThat(staged("IT-G5-KEY-2")).isOne();
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT staging_status FROM intf_staging_txn WHERE"
                                                    + " request_id = 'IT-G5-KEY-2'",
                                            String.class))
                            .isEqualTo("RECEIVED");
                    assertThatThrownBy(
                                    () ->
                                            jdbc.update(
                                                    "UPDATE intf_staging_txn SET staging_status ="
                                                            + " 'MERGED' WHERE request_id ="
                                                            + " 'IT-G5-KEY-2'"))
                            .isInstanceOf(DataAccessException.class);
                });
    }

    private int conflict(String authority, String resolution) {
        return jdbc.update(
                "INSERT INTO cpf_ssp_conflict (employee_id, field_name, payroll_value, ssp_value,"
                        + " authority, resolution) VALUES (?, 'it_g5_field', '1', '2', ?, ?)",
                employee,
                authority,
                resolution);
    }

    /**
     * SRS-INT-15 / BR-INT-007 (Payroll is the authority for contribution and ledger values, the SSP
     * for member-submitted request data; on a concurrent update the authority's value prevails and
     * the other is logged): a conflict records which side owns the field and a known resolution;
     * the SSP exchange waits for its transport (PENDING API-23-3, DB-7).
     */
    @Test
    void SRS_INT_15_BR_INT_007_aConflictRecordsItsAuthorityAndAKnownResolution() {
        rolledBack(
                () -> {
                    assertThat(conflict("PAYROLL", "PAYROLL_WINS")).isOne();
                    assertThat(conflict("SSP", "SSP_WINS")).isOne();
                    assertThatThrownBy(() -> conflict("HRMS", "PAYROLL_WINS"))
                            .isInstanceOf(DataAccessException.class);
                    assertThatThrownBy(() -> conflict("PAYROLL", "LATEST_WINS"))
                            .isInstanceOf(DataAccessException.class);
                });
    }

    /**
     * SRS-4-14 (a Present But No Work day, approved by the Depot Manager, counts towards the DPS
     * monthly MGW guarantee): the Depot Manager is a depot-scoped approver holding I-02 APPROVE
     * (and, since V077_31, M-09 APPROVE for the gang shortfall, OD-90); the count towards the MGW
     * comes with the C-04 run (PENDING LBR-20c-2, LBR-21d-1).
     */
    @Test
    void SRS_4_14_theDepotManagerIsADepotScopedApproverOfI02() {
        rolledBack(
                () -> {
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT is_approver FROM app_role WHERE role_code ="
                                                    + " 'DEPOT_MANAGER'",
                                            String.class))
                            .isEqualTo("Y");
                    Integer grants =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM app_role_permission rp JOIN app_role r ON"
                                        + " r.role_id = rp.role_id JOIN app_permission p ON"
                                        + " p.permission_id = rp.permission_id WHERE r.role_code ="
                                        + " 'DEPOT_MANAGER' AND p.action_code = 'APPROVE' AND"
                                        + " p.screen_code IN ('I-02', 'M-09')",
                                    Integer.class);
                    assertThat(grants).isEqualTo(2);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT allowed_location_types FROM login_persona_role"
                                                + " lp JOIN app_role r ON r.role_id = lp.role_id"
                                                + " WHERE r.role_code = 'DEPOT_MANAGER'",
                                            String.class))
                            .isEqualTo("DEPOT");
                });
    }
}
