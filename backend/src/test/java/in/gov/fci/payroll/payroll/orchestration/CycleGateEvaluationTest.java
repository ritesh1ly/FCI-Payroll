package in.gov.fci.payroll.payroll.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.NetPayFloors;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.orchestration.CycleGateService.CheckView;
import in.gov.fci.payroll.payroll.orchestration.CycleGateService.GateView;
import in.gov.fci.payroll.payroll.orchestration.CycleGateService.GatesView;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * C-01 gate and check evaluation (SRS-15-14 … 18, SRS-15-39) over a scripted database: the cases
 * the demo data of the integration tests never reaches (G2 acceptable, a lapsed acceptance, an
 * unknown eligible count, labour checks, a changed failure count, a member without a last name).
 */
class CycleGateEvaluationTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 4);
    private static final Instant NOW = Instant.parse("2026-05-02T06:30:00Z");

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final AccessContext context = mock(AccessContext.class);

    @SuppressWarnings("unchecked")
    private final CycleGateService service =
            new CycleGateService(
                    db.jdbc,
                    org(),
                    mock(Authorization.class),
                    mock(AuditService.class),
                    mock(NetPayFloors.class),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    mock(ObjectProvider.class));

    CycleGateEvaluationTest() {
        when(context.locationId()).thenReturn(41L);
        when(context.loginId()).thenReturn("ddo.kolkata");
        db.rows(
                        "initiated_by, frozen_by",
                        row(
                                "cycle_instance_id", 9L,
                                "payroll_yymm", "202604",
                                "cycle_status", "COMPUTED",
                                "initiated_by", "maker.kolkata",
                                "frozen_by", "maker.kolkata"))
                .rows(
                        "current_run_id",
                        row(
                                "cycle_instance_id",
                                9L,
                                "payroll_yymm",
                                "202604",
                                "cycle_status",
                                "COMPUTED",
                                "current_run_id",
                                null))
                .value("SELECT payroll_yymm FROM payroll_cycle_instance", "202604");
    }

    private static OrgDirectory org() {
        OrgDirectory org = mock(OrgDirectory.class);
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(40L);
        when(org.cycleUnitOf(41L)).thenReturn(Optional.of(unit));
        when(org.subtree(40L)).thenReturn(java.util.Set.of(40L, 41L));
        return org;
    }

    private static GateView gate(GatesView view, String code) {
        return view.gates().stream().filter(g -> g.code().equals(code)).findFirst().orElseThrow();
    }

    private static CheckView check(GatesView view, String code) {
        return view.checks().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow();
    }

    /** All four statutory processes complete; CPF's uncomputed count not reported (null). */
    private void statutoryComplete() {
        db.rows(
                "FROM cycle_statutory_status",
                cols("CPF_PF", "COMPLETE", null),
                cols("INCOME_TAX", "COMPLETE", 0L),
                cols("PROFESSIONAL_TAX", "COMPLETE", 0L),
                cols("NPS_DEDUCTION", "COMPLETE", 0L));
    }

    private static FciException refusal(Runnable call) {
        return (FciException) catchThrowable(call::run);
    }

    @Test
    void SRS_15_16_aMetGateIsNeverAcceptedAndAnUnreportedUncomputedCountIsComplete() {
        statutoryComplete();
        assertThat(gate(service.gates(context, PERIOD), "G5").met()).isTrue();
        assertThat(refusal(() -> service.accept(context, PERIOD, "G5", "x")).getErrorCode())
                .isEqualTo(ApiErrorCode.CYCLE_GATE_MET);
    }

    @Test
    void SRS_15_16_g2WithOnlyNonBlockingExceptionsIsAcceptableAndRegistered() {
        db.value("severity <> 'BLOCKING'", 2L);
        GateView g2 = gate(service.gates(context, PERIOD), "G2");
        assertThat(g2.met()).isFalse();
        assertThat(g2.acceptable()).isTrue();

        GatesView accepted =
                service.accept(context, PERIOD, "G2", "Two advisory exceptions reviewed");
        // The screen redraws from the answer: the gates as they stand after the acceptance.
        assertThat(accepted).isNotNull();
        assertThat(accepted.gates()).extracting(GateView::code).contains("G2", "G5");
        assertThat(db.updates("undo_reversal_register"))
                .singleElement()
                .satisfies(
                        u -> assertThat(u.args()).contains("G2", "maker.kolkata", "ddo.kolkata"));
        assertThat(db.updates("SET accepted_by = ?, accepted_at")).hasSize(1);
    }

    @Test
    void SRS_15_16_anAcceptanceLapsesOnceItsGateIsMetAndStandsWhileItIsNot() {
        statutoryComplete();
        Timestamp at = Timestamp.from(NOW.minusSeconds(3600));
        db.rows(
                "accepted_reason FROM cycle_gate_evaluation",
                row(
                        "gate_code",
                        "G5",
                        "accepted_by",
                        "ddo.kolkata",
                        "accepted_at",
                        at,
                        "accepted_reason",
                        "Excluded member"),
                row(
                        "gate_code",
                        "G2",
                        "accepted_by",
                        "ddo.kolkata",
                        "accepted_at",
                        at,
                        "accepted_reason",
                        "Advisory only"));
        db.value("severity <> 'BLOCKING'", 1L);
        GatesView view = service.gates(context, PERIOD);
        GateView g5 = gate(view, "G5");
        assertThat(g5.met()).isTrue();
        assertThat(g5.acceptedBy()).isNull();
        GateView g2 = gate(view, "G2");
        assertThat(g2.acceptedBy()).isEqualTo("ddo.kolkata");
        assertThat(g2.acceptedAt()).isEqualTo(at.toInstant());
        assertThat(g2.acceptable()).isFalse();
        assertThat(view.blocking()).doesNotContain("G2", "G5");
        // The lapsed acceptance is cleared on the stored row.
        assertThat(db.updates("UPDATE cycle_gate_evaluation SET is_met"))
                .filteredOn(u -> u.args().contains("G5"))
                .singleElement()
                .satisfies(u -> assertThat(u.args().get(4)).isNull());
    }

    @Test
    void SRS_15_14_g0ReadsTheRunAndAnUnknownEligibleCountIsNeverMet() {
        db.rows(
                        "current_run_id",
                        row(
                                "cycle_instance_id",
                                9L,
                                "payroll_yymm",
                                "202604",
                                "cycle_status",
                                "COMPUTED",
                                "current_run_id",
                                77L))
                .rows(
                        "FROM computation_run WHERE",
                        row("run_status", "COMPLETE", "result_count", 5L, "eligible_count", null))
                .value("FROM computation_work_unit WHERE run_id = ?", 3L);
        assertThat(gate(service.gates(context, PERIOD), "G0").met()).isFalse();
        db.rows(
                "FROM computation_run WHERE",
                row("run_status", "COMPLETE", "result_count", 5L, "eligible_count", 5L));
        assertThat(gate(service.gates(context, PERIOD), "G0").met()).isTrue();
    }

    @Test
    void SRS_15_39_aPassedWarnCheckIsNotAcknowledged() {
        db.rows(
                "check_result_id, severity_at_eval",
                row("check_result_id", 5L, "severity_at_eval", "WARN", "check_result", "PASS"));
        assertThatThrownBy(() -> service.acknowledge(context, PERIOD, 3L, "x"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ApiErrorCode.CYCLE_CHECK_NOT_ACKNOWLEDGEABLE));
    }

    private static java.util.Map<String, Object> preLock(long id, String code, String applies) {
        return row(
                "check_id", id,
                "check_code", code,
                "check_name", code,
                "applies_to", applies,
                "severity", "BLOCK",
                "evaluation_rule_key", code,
                "correction_screen", null);
    }

    @Test
    void SRS_15_39_labourChecksApplyWhereALabourEngineRanAndOnlyItsOwnTypes() {
        db.rows(
                        "FROM cycle_engine_status",
                        cols("STAFF", "COMPLETE"),
                        cols("DPS_LABOUR", "COMPLETE"))
                .rows(
                        "FROM pre_payroll_check WHERE",
                        preLock(1L, "ANY_LABOUR", "LABOUR"),
                        preLock(2L, "DPS_ONLY", "DPS_LABOUR"),
                        preLock(3L, "NWNP_ONLY", "NWNP_LABOUR"));
        assertThat(service.gates(context, PERIOD).checks())
                .extracting(CheckView::code)
                .containsExactly("ANY_LABOUR", "DPS_ONLY");
    }

    @Test
    void SRS_15_39_aChangedFailureCountIsANewEvaluationAndAnUnchangedOneKeepsItsAcknowledgement() {
        db.rows("FROM pre_payroll_check WHERE", preLock(1L, "NOT_BUILT", "ALL"))
                .rows(
                        "check_result, failure_count",
                        row(
                                "check_result",
                                "FAIL",
                                "failure_count",
                                2L,
                                "acknowledged_by",
                                "ddo.kolkata",
                                "acknowledged_at",
                                null,
                                "acknowledge_reason",
                                "r"));
        CheckView changed = check(service.gates(context, PERIOD), "NOT_BUILT");
        assertThat(changed.failures()).isEqualTo(1);
        assertThat(changed.acknowledgedBy()).isNull();
        assertThat(db.updates("INSERT INTO cycle_prepay_check_result")).hasSize(1);

        db.rows(
                "check_result, failure_count",
                row(
                        "check_result",
                        "FAIL",
                        "failure_count",
                        1L,
                        "acknowledged_by",
                        "ddo.kolkata",
                        "acknowledged_at",
                        null,
                        "acknowledge_reason",
                        "r"));
        CheckView same = check(service.gates(context, PERIOD), "NOT_BUILT");
        assertThat(same.acknowledgedBy()).isEqualTo("ddo.kolkata");
        assertThat(same.acknowledgeReason()).isEqualTo("r");
        assertThat(db.updates("INSERT INTO cycle_prepay_check_result")).hasSize(1);
    }

    @Test
    void SRS_15_13_aQuarantinedMemberWithoutALastNameIsListedByTheFirstName() {
        db.rows(
                "payroll_quarantine q JOIN",
                cols(
                        1L,
                        501L,
                        "E0501",
                        "Asha",
                        null,
                        "NEGATIVE_NET",
                        new java.math.BigDecimal("-10.00"),
                        "UNREVIEWED",
                        null,
                        null),
                cols(
                        2L,
                        502L,
                        "E0502",
                        "Ravi",
                        "Kumar",
                        "ZERO_NET",
                        java.math.BigDecimal.ZERO,
                        "UNREVIEWED",
                        null,
                        null));
        assertThat(service.quarantine(context, PERIOD, new PageSpec(0, 20)).items())
                .extracting(CycleGateService.QuarantineView::name)
                .containsExactly("Asha", "Ravi Kumar");
    }

    /**
     * BMAD TR-010 (SRS l.1524 "where a named member is legitimately excluded"): G5 is accepted for
     * named members only - blanks, repeats and missing names are dropped, the names are written
     * ahead of the reason; a name that is no staff member of the cycle's offices is refused
     * NOT_A_MEMBER, and fewer names than members left uncomputed UNNAMED_MEMBERS.
     */
    @Test
    void SRS_15_16_g5IsAcceptedForTheNamedMembersOfTheCyclesOffices() {
        db.rows(
                "FROM cycle_statutory_status",
                cols("CPF_PF", "COMPLETE", 2L),
                cols("INCOME_TAX", "COMPLETE", 0L),
                cols("PROFESSIONAL_TAX", "COMPLETE", 1L),
                cols("NPS_DEDUCTION", "COMPLETE", 0L));
        assertThat(gate(service.gates(context, PERIOD), "G5").met()).isFalse();
        db.rows("FROM employee WHERE emp_num", cols(41L));
        java.util.List<String> names = new java.util.ArrayList<>();
        names.add(" 105220 ");
        names.add("105220");
        names.add(null);
        names.add(" ");
        names.add("105461");
        assertThat(service.accept(context, PERIOD, "G5", "Both on HRMS hold", names)).isNotNull();
        assertThat(db.updates("SET accepted_by = ?, accepted_at"))
                .singleElement()
                .satisfies(
                        u ->
                                assertThat(u.args())
                                        .contains(
                                                "Excluded members 105220, 105461: Both on HRMS"
                                                        + " hold"));

        FciException fewer =
                refusal(
                        () ->
                                service.accept(
                                        context,
                                        PERIOD,
                                        "G5",
                                        "One only",
                                        java.util.List.of("105220")));
        assertThat(fewer.getErrorCode()).isEqualTo(ApiErrorCode.CYCLE_GATE_MEMBERS);
        assertThat(fewer.getMessageArgs()).containsExactly("G5", "UNNAMED_MEMBERS:2");

        db.rows("FROM employee WHERE emp_num", cols(99L));
        FciException outside =
                refusal(
                        () ->
                                service.accept(
                                        context,
                                        PERIOD,
                                        "G5",
                                        "Elsewhere",
                                        java.util.List.of("105220", "105461")));
        assertThat(outside.getErrorCode()).isEqualTo(ApiErrorCode.CYCLE_GATE_MEMBERS);
        assertThat(outside.getMessageArgs()).containsExactly("G5", "NOT_A_MEMBER:105220");
    }
}
