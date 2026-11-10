package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * Module 07 phase 07a: the CPF tables of target DDL v4.0 (V034_1) hold their own invariants, so a
 * defect in a service or a direct write never breaks them. The service checks first; the database
 * is the last line (plan 07 D-8). Each test writes far-future rows of its own, in years no other IT
 * touches for the first seeded employee (CpfLedgerIT posts 2090-91 and 2091-92 for member 104882,
 * the same record as MIN(employee_id): in one fork the two classes share one database).
 */
class CpfSchemaIT extends ApiTestSupport {

    private long employee;
    private long zoneA;
    private long zoneB;

    @BeforeEach
    void fixtures() {
        employee = jdbc.queryForObject("SELECT MIN(employee_id) FROM employee", Long.class);
        zoneA = jdbc.queryForObject("SELECT MIN(zone_id) FROM org_zone", Long.class);
        zoneB = jdbc.queryForObject("SELECT MAX(zone_id) FROM org_zone", Long.class);
    }

    /**
     * SRS-22-21 (BR-APPR-008), plan 07 D-2: each CPF actor signs in at its office in its own role,
     * and only in a role it holds.
     */
    @Test
    void SRS_22_21_eachCpfActorSignsInAtItsOfficeInItsOwnRole() throws Exception {
        login("cpf.unit.delhi@fci-demo.test", "UNIT_CPF_ADMIN", "DH001");
        login("cpf.north@fci-demo.test", "CPF_DIVISION", "NA11");
        login("cpf.manager.north@fci-demo.test", "CPF_MANAGER", "NA11");
        login("agm.cpf.north@fci-demo.test", "AGM_CPF", "NA11");
        login("cpf.sanction.north@fci-demo.test", "CPF_SANCTIONING_AUTHORITY", "NA11");
        login("cpf.approver.hq@fci-demo.test", "HQ_CPF_APPROVER", "HQ001");
        // A role the user does not hold, or an office outside its scope, is refused.
        assertThat(
                        loginRaw(
                                        "cpf.manager.north@fci-demo.test",
                                        DEMO_PASSWORD,
                                        "AGM_CPF",
                                        "NA11")
                                .getResponse()
                                .getStatus())
                .isNotEqualTo(200);
        assertThat(
                        loginRaw(
                                        "cpf.approver.hq@fci-demo.test",
                                        DEMO_PASSWORD,
                                        "HQ_CPF_APPROVER",
                                        "NA11")
                                .getResponse()
                                .getStatus())
                .isNotEqualTo(200);
    }

    /** SRS-6-57, BR-CPF-025: no part of a CPF balance is ever negative (chk_pf_balance_nonneg). */
    @Test
    void SRS_6_57_aClosingBalanceBelowZeroIsRefusedOnEveryPart() {
        insertHeader("2098-99", new BigDecimal("0.00"), "N", null);
        for (String part : new String[] {"employee", "employer", "vpf"}) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE employee_pf_header SET closing_balance_"
                                                    + part
                                                    + " = -0.01 WHERE employee_id = ? AND"
                                                    + " fin_year = '2098-99'",
                                            employee))
                    .as(part)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    /** SRS-6-9: the ex-member three-year interest stop never applies to a deputed member. */
    @Test
    void SRS_6_9_theThreeYearStopNeverAppliesToADeputedMember() {
        assertThatThrownBy(() -> insertHeader("2099-00", BigDecimal.ZERO, "Y", "EX_MEMBER_3_YEARS"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // The same stop on a member not on deputation is admitted.
        insertHeader("2099-00", BigDecimal.ZERO, "N", "EX_MEMBER_3_YEARS");
    }

    /** SRS-6-46: a CPF interest freeze is irreversible (trg_cpf_freeze_irreversible). */
    @Test
    void SRS_6_46_aFreezeIsNeverRemovedAndItsYearChangesOnlyWithAnApprovedAuthorisation() {
        long freeze =
                insert(
                        "INSERT INTO cpf_interest_freeze (fin_year, zone_id, frozen_by,"
                                + " preceding_run_count) VALUES ('2092-93', ?, 'it.cpf', 2)",
                        "freeze_id",
                        zoneA);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM cpf_interest_freeze WHERE freeze_id = ?",
                                        freeze))
                .hasMessageContaining("FCIPAY-CPF-001");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE cpf_interest_freeze SET fin_year = '2093-94'"
                                                + " WHERE freeze_id = ?",
                                        freeze))
                .hasMessageContaining("FCIPAY-CPF-002");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE cpf_interest_freeze SET zone_id = ? WHERE"
                                                + " freeze_id = ?",
                                        zoneB,
                                        freeze))
                .hasMessageContaining("FCIPAY-CPF-002");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE cpf_interest_freeze SET frozen_at = frozen_at +"
                                                + " INTERVAL '1' HOUR WHERE freeze_id = ?",
                                        freeze))
                .hasMessageContaining("FCIPAY-CPF-002");
        // A column outside the year, zone and time is not the freeze itself.
        assertThat(
                        jdbc.update(
                                "UPDATE cpf_interest_freeze SET preceding_run_count = 3 WHERE"
                                        + " freeze_id = ?",
                                freeze))
                .isEqualTo(1);
        // A pending authorisation is not enough; an approved one corrects the year.
        authorisation(freeze, "PENDING_APPROVAL");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE cpf_interest_freeze SET fin_year = '2093-94'"
                                                + " WHERE freeze_id = ?",
                                        freeze))
                .hasMessageContaining("FCIPAY-CPF-002");
        authorisation(freeze, "APPROVED");
        assertThat(
                        jdbc.update(
                                "UPDATE cpf_interest_freeze SET fin_year = '2093-94' WHERE"
                                        + " freeze_id = ?",
                                freeze))
                .isEqualTo(1);
        // One freeze per year and zone.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO cpf_interest_freeze (fin_year, zone_id,"
                                                + " frozen_by, preceding_run_count) VALUES"
                                                + " ('2093-94', ?, 'it.cpf', 0)",
                                        zoneA))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * SRS-6-4, UC-05: SANCTIONED needs an OTP-confirmed tier-3 action on the CURRENT pass
     * (trg_pf_sanction_tier3), and one user acts once per pass (uk_cpf_tier_actor).
     */
    @Test
    void SRS_6_4_aSanctionNeedsAnOtpConfirmedTier3ActionOnTheCurrentPass() {
        long sanction =
                insert(
                        "INSERT INTO pf_sanction (employee_id, sanction_type, requested_amount)"
                                + " VALUES (?, 'ADVANCE', 1000)",
                        "sanction_id",
                        employee);
        String sanctioned =
                "UPDATE pf_sanction SET workflow_stage = 'SANCTIONED' WHERE sanction_id = ?";
        assertThatThrownBy(() -> jdbc.update(sanctioned, sanction))
                .hasMessageContaining("FCIPAY-CPF-004");

        // Moving along the chain below SANCTIONED needs no tier-3 action.
        assertThat(
                        jdbc.update(
                                "UPDATE pf_sanction SET workflow_stage = 'ZONAL_HQ_VALIDATION'"
                                        + " WHERE sanction_id = ?",
                                sanction))
                .isEqualTo(1);
        tier(sanction, 1, 1, "UNIT_CPF_ADMIN", "FORWARDED", "it.tier1", null);
        // The same user cannot act twice on one pass.
        assertThatThrownBy(
                        () ->
                                tier(
                                        sanction,
                                        1,
                                        2,
                                        "ZONAL_HQ_CPF_DIVISION",
                                        "RECOMMENDED",
                                        "it.tier1",
                                        null))
                .isInstanceOf(DataIntegrityViolationException.class);
        tier(sanction, 1, 2, "ZONAL_HQ_CPF_DIVISION", "RECOMMENDED", "it.tier2", null);
        // A tier-3 sanction without its OTP is refused by chk_cpf_tier_sanction.
        assertThatThrownBy(
                        () ->
                                tier(
                                        sanction,
                                        1,
                                        3,
                                        "COMPETENT_AUTHORITY",
                                        "SANCTIONED",
                                        "it.tier3",
                                        null))
                .isInstanceOf(DataIntegrityViolationException.class);
        // A tier below 3 never sanctions, and tier 3 never forwards.
        assertThatThrownBy(
                        () ->
                                tier(
                                        sanction,
                                        9,
                                        2,
                                        "ZONAL_HQ_CPF_DIVISION",
                                        "SANCTIONED",
                                        "x",
                                        otp()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () -> tier(sanction, 9, 3, "COMPETENT_AUTHORITY", "FORWARDED", "x", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        tier(sanction, 1, 3, "COMPETENT_AUTHORITY", "SANCTIONED", "it.tier3", otp());

        // A return opens pass 2: the pass-1 sanction no longer counts.
        tier(sanction, 2, 1, "UNIT_CPF_ADMIN", "FORWARDED", "it.tier1", null);
        assertThatThrownBy(() -> jdbc.update(sanctioned, sanction))
                .hasMessageContaining("FCIPAY-CPF-004");
        tier(sanction, 2, 2, "ZONAL_HQ_CPF_DIVISION", "RECOMMENDED", "it.tier2", null);
        tier(sanction, 2, 3, "COMPETENT_AUTHORITY", "SANCTIONED", "it.tier3", otp());
        assertThat(jdbc.update(sanctioned, sanction)).isEqualTo(1);
        // Already sanctioned: a later update of the row is not a second sanction.
        assertThat(
                        jdbc.update(
                                "UPDATE pf_sanction SET sanction_order_ref = 'IT' WHERE"
                                        + " sanction_id = ?",
                                sanction))
                .isEqualTo(1);
    }

    /** SRS-6-2, SRS-6-26: sanctioned never exceeds the ceiling; a cap is a real reduction. */
    @Test
    void SRS_6_26_theSanctionedAmountNeverExceedsTheCeilingAndACapReducesTheRequest() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO pf_sanction (employee_id, sanction_type,"
                                                + " requested_amount, computed_ceiling_amount,"
                                                + " sanctioned_amount) VALUES (?, 'ADVANCE', 900,"
                                                + " 800, 800.01)",
                                        employee))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO pf_sanction (employee_id, sanction_type,"
                                                + " requested_amount, computed_ceiling_amount,"
                                                + " sanctioned_amount, was_capped) VALUES (?,"
                                                + " 'ADVANCE', 800, 800, 800, 'Y')",
                                        employee))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(
                        jdbc.update(
                                "INSERT INTO pf_sanction (employee_id, sanction_type,"
                                        + " requested_amount, computed_ceiling_amount,"
                                        + " sanctioned_amount, was_capped) VALUES (?, 'ADVANCE',"
                                        + " 900, 800, 800, 'Y')",
                                employee))
                .isEqualTo(1);
    }

    /**
     * SRS-6-49, SRS-6-52, BR-CPF-020: a change rejected in HRMS carries no new Member ID and is
     * never applied; no parent-zone change record is ever deleted (trg_cpf_pzc_no_delete).
     */
    @Test
    void BR_CPF_020_aChangeRejectedInHrmsChangesNothingAndNoRecordIsDeleted() {
        assertThatThrownBy(() -> pzc("HRMS-PZC-IT-1", "REJECTED", "REJECTED_IN_HRMS", "NEW-ID"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> pzc("HRMS-PZC-IT-2", "REJECTED", "RECEIVED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        long rejected = pzc("HRMS-PZC-IT-3", "REJECTED", "REJECTED_IN_HRMS", null);
        // APPLIED needs the HRMS approval, the new ID, the date, the I-21 task and the new row.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE cpf_parent_zone_change SET payroll_status ="
                                                + " 'APPLIED' WHERE pzc_id = ?",
                                        rejected))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM cpf_parent_zone_change WHERE pzc_id = ?",
                                        rejected))
                .hasMessageContaining("FCIPAY-CPF-003");
        // A change never moves a member to the zone it comes from.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO cpf_parent_zone_change (hrms_reference,"
                                                + " employee_id, change_reason, from_zone_id,"
                                                + " to_zone_id, hrms_decision,"
                                                + " old_cpf_member_id) VALUES ('HRMS-PZC-IT-4', ?,"
                                                + " 'ZONE_SURRENDER', ?, ?, 'APPROVED', 'OLD')",
                                        employee,
                                        zoneA,
                                        zoneA))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** SRS-6-58: a Member ID is never TRANSFER-superseded, and only a rejected row has no value. */
    @Test
    void SRS_6_58_aMemberIdIsNeverSupersededByATransfer() {
        String sql =
                "INSERT INTO cpf_member_id_history (employee_id, id_type, identifier_value,"
                        + " zone_id, effective_from, id_status, supersede_reason, id_source,"
                        + " hrms_request_id) VALUES (?, 'CPF', ?, ?, DATE '2090-04-01', ?, ?,"
                        + " 'HRMS', 'HRMS-IT')";
        assertThatThrownBy(
                        () -> jdbc.update(sql, employee, "IT-CPF-1", zoneA, "CLOSED", "TRANSFER"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, employee, null, zoneA, "ACTIVE", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(sql, employee, null, zoneA, "REJECTED", null)).isEqualTo(1);
        // An HRMS identifier always names its HRMS request.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO cpf_member_id_history (employee_id,"
                                                + " id_type, identifier_value, effective_from,"
                                                + " id_source) VALUES (?, 'CPF', 'IT-CPF-2', DATE"
                                                + " '2090-04-01', 'HRMS')",
                                        employee))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * SRS-6-43, SRS-6-44: a correction row changes its part, never below zero, and the maker,
     * checker and HQ approver are three different users; the difference is computed.
     */
    @Test
    void SRS_6_43_aCorrectionChangesAPartNeverBelowZeroByThreeDifferentUsers() {
        String sql =
                "INSERT INTO opening_balance_correction (correction_ref, employee_id,"
                    + " balance_domain, fin_year, as_at_date, balance_component, old_amount,"
                    + " new_amount, reason_code, reason, interest_effective_date, raised_by,"
                    + " checker_user) VALUES (?, ?, 'CPF', '2090-91', DATE '2090-04-01',"
                    + " 'EMPLOYEE', ?, ?, 'CLERICAL_ERROR', 'IT', DATE '2090-04-01', 'it.maker',"
                    + " ?)";
        assertThatThrownBy(() -> jdbc.update(sql, "OB-IT-1", employee, 100, 100, null))
                .as("no change")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, "OB-IT-2", employee, 100, -1, null))
                .as("negative")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, "OB-IT-3", employee, 100, 90, "it.maker"))
                .as("maker checks")
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(sql, "OB-IT-4", employee, 100, 90, null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT difference_amount FROM opening_balance_correction WHERE"
                                        + " correction_ref = 'OB-IT-4'",
                                BigDecimal.class))
                .isEqualByComparingTo("-10");
        // An approved correction needs the HQ approver's OTP.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE opening_balance_correction SET correction_status"
                                                + " = 'APPROVED', checker_user = 'it.checker',"
                                                + " otp_challenge_id = ?, hq_approver_user ="
                                                + " 'it.hq' WHERE correction_ref = 'OB-IT-4'",
                                        otp()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertHeader(
            String year, BigDecimal closingVpf, String deputation, String reason) {
        jdbc.update(
                "INSERT INTO employee_pf_header (employee_id, fin_year, zone_id,"
                        + " closing_balance_vpf, opening_balance_source, is_deputation,"
                        + " interest_end_date, interest_end_reason) VALUES (?, ?, ?, ?,"
                        + " 'MIGRATION', ?, ?, ?)",
                employee,
                year,
                zoneA,
                closingVpf,
                deputation,
                reason == null ? null : Date.valueOf("2093-03-31"),
                reason);
    }

    private long pzc(String reference, String decision, String status, String newId) {
        return insert(
                "INSERT INTO cpf_parent_zone_change (hrms_reference, employee_id, change_reason,"
                        + " from_zone_id, to_zone_id, hrms_decision, payroll_status,"
                        + " old_cpf_member_id, new_cpf_member_id) VALUES (?, ?,"
                        + " 'PARENT_ZONE_CHANGE', ?, ?, ?, ?, 'OLD-ID', ?)",
                "pzc_id",
                reference,
                employee,
                zoneA,
                zoneB,
                decision,
                status,
                newId);
    }

    private void tier(
            long sanction,
            int pass,
            int tier,
            String code,
            String action,
            String user,
            Long otpChallenge) {
        jdbc.update(
                "INSERT INTO cpf_sanction_tier (sanction_id, pass_no, tier_no, tier_code,"
                        + " tier_action, acted_by, otp_challenge_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                sanction,
                pass,
                tier,
                code,
                action,
                user,
                otpChallenge);
    }

    private long otp() {
        return insert(
                "INSERT INTO otp_challenge (user_id, code_hash, delivery_channel, gated_action,"
                        + " challenged_user, outcome) VALUES ((SELECT MIN(user_id) FROM app_user),"
                        + " 'it', 'EMAIL', 'CPF_SANCTION_FINAL_PAYMENT', 'it.tier3', 'SUCCESS')",
                "otp_challenge_id");
    }

    private void authorisation(long freeze, String status) {
        jdbc.update(
                "INSERT INTO approval_queue (entity_name, entity_key, action_type, payload,"
                        + " submitted_by, status) VALUES ('CPF_INTEREST_FREEZE', ?, 'UPDATE', '{}',"
                        + " 'it.cpf', ?)",
                Long.toString(freeze),
                status);
    }

    private long insert(String sql, String keyColumn, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(
                con -> {
                    PreparedStatement ps = con.prepareStatement(sql, new String[] {keyColumn});
                    for (int i = 0; i < args.length; i++) {
                        ps.setObject(i + 1, args[i]);
                    }
                    return ps;
                },
                keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
}
