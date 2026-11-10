package in.gov.fci.payroll.cpf.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The reads of the drawn CPF screens (OD-121; SRS-6-27, SRS-6-43, SRS-6-48, SRS-6-49, BR-CPF-020,
 * BR-CPF-021): each under its screen's VIEW, filtered to the data scope, and a figure no run stored
 * stays null (Q-072).
 */
class CpfScreenServiceTest {

    private static final PageSpec SPEC = new PageSpec(0, 20);
    private static final Date DAY = Date.valueOf("2026-04-01");

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final Authorization policy = mock(Authorization.class);
    private final CpfScreenService service = new CpfScreenService(db.named, policy);
    private final AccessContext user = context(Set.of(300L));
    private final AccessContext nowhere = context(Set.of());

    private static AccessContext context(Set<Long> scope) {
        return new AccessContext(
                3L,
                "maker@x",
                "User",
                "s",
                "P",
                300L,
                null,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of("PAYROLL_MAKER"),
                scope);
    }

    @Test
    void SRS_G_2_aRefusedViewReadsNothing() {
        for (String screen : List.of("I-09", "I-22", "I-12", "I-13")) {
            doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                    .when(policy)
                    .require(user, screen, "VIEW");
        }
        assertThatThrownBy(() -> service.deputationBoard(user, "202611"))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.deputedMembers(user, SPEC))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.correctionBoard(user)).isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.correctionLines(user, SPEC))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.supplementaryPayments(user, SPEC))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.zoneChanges(user, SPEC)).isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.zoneChange(user, "HRMS-PZC-1"))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.iocpfPending(user, SPEC)).isInstanceOf(FciException.class);
        assertThat(db.queries("")).isEmpty();
    }

    @Test
    void SRS_G_53_anEmptyScopeReadsNothingAndIsNoError() {
        assertThat(service.deputationBoard(nowhere, "202611").members()).isZero();
        assertThat(service.deputedMembers(nowhere, SPEC).items()).isEmpty();
        assertThat(service.correctionBoard(nowhere).interestQueued()).isNull();
        assertThat(service.correctionLines(nowhere, SPEC).items()).isEmpty();
        assertThat(service.supplementaryPayments(nowhere, SPEC).items()).isEmpty();
        assertThat(service.zoneChanges(nowhere, SPEC).items()).isEmpty();
        assertThat(service.iocpfPending(nowhere, SPEC).items()).isEmpty();
        assertThat(db.queries("")).isEmpty();
    }

    @Test
    void SRS_G_53_aChangeOutsideTheScopeAnswersAsNotFound() {
        assertThatThrownBy(() -> service.zoneChange(nowhere, "HRMS-PZC-1"))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.zoneChange(user, "HRMS-PZC-1"))
                .isInstanceOf(FciException.class);
    }

    @Test
    void SRS_6_27_theBoardAddsOnlyApprovedRemittancesOfTheCycle() {
        db.value("SELECT COUNT(*) FROM employee_lifecycle_event", 3L);
        db.value("SELECT SUM(r.instalment_remitted)", new BigDecimal("19200"));
        db.value("SELECT COUNT(DISTINCT r.borrowing_org_id)", 2L);
        var board = service.deputationBoard(user, "202611");
        assertThat(board.members()).isEqualTo(3);
        assertThat(board.recovered()).isEqualByComparingTo("19200");
        // Nothing approved this cycle reads as nil, never as an error.
        assertThat(board.contributions()).isEqualByComparingTo("0");
        assertThat(board.organisations()).isEqualTo(2);
        assertThat(db.queries("r.entry_status = 'APPROVED'")).hasSize(2);
    }

    @Test
    void BR_CPF_021_aMemberWithNoEntryIsAwaitingAdviceAndHoldsNoFigureItHasNotRemitted() {
        db.value("SELECT COUNT(*) FROM employee_lifecycle_event", 1L);
        db.rows(
                "SELECT e.emp_num, e.first_name",
                row(
                        "EMP_NUM", "871901",
                        "FIRST_NAME", "Kiran",
                        "LAST_NAME", "Bose",
                        "EVENT_DATE", DAY,
                        "END_DATE", null,
                        "ORG_NAME", null,
                        "OUTSTANDING", new BigDecimal("60000"),
                        "INSTALMENT_REMITTED", null,
                        "BALANCE_AFTER", null,
                        "CPF_CONTRIBUTION_RECEIVED", null,
                        "REC_STATUS", null,
                        "CON_STATUS", null));
        var page = service.deputedMembers(user, SPEC);
        assertThat(page.total()).isEqualTo(1);
        var r = page.items().getFirst();
        assertThat(r.name()).isEqualTo("K. Bose");
        assertThat(r.to()).isNull();
        assertThat(r.instalment()).isNull();
        assertThat(r.status()).isEqualTo("AWAITING_ADVICE");
    }

    @Test
    void BR_CPF_021_theMemberStatusFollowsWhatIsWaiting() {
        assertThat(CpfScreenService.deputationStatus("PENDING_CHECKER", "APPROVED"))
                .isEqualTo("PENDING_CHECKER");
        assertThat(CpfScreenService.deputationStatus("APPROVED", "PENDING_CHECKER"))
                .isEqualTo("PENDING_CHECKER");
        assertThat(CpfScreenService.deputationStatus("REJECTED", "APPROVED")).isEqualTo("REJECTED");
        assertThat(CpfScreenService.deputationStatus(null, "APPROVED")).isEqualTo("APPROVED");
        assertThat(CpfScreenService.deputationStatus(null, null)).isEqualTo("AWAITING_ADVICE");
    }

    @Test
    void SRS_6_43_theBoardCountsRequestsNotPartsAndHoldsNoInterestBeforeTheMethod() {
        db.value("SELECT COUNT(DISTINCT c.correction_ref)", 2L);
        db.value("SELECT SUM(c.interest_effect_amount)", ScriptedJdbc.NULL);
        var board = service.correctionBoard(user);
        assertThat(board.open()).isEqualTo(2);
        assertThat(board.pendingAgm()).isEqualTo(2);
        assertThat(board.interestQueued()).isNull();
    }

    @Test
    void SRS_6_43_aLineGivesItsDifferenceAndItsEvidence() {
        db.value("SELECT COUNT(*) FROM opening_balance_correction", 2L);
        db.rows(
                "SELECT c.correction_ref",
                row(
                        "CORRECTION_REF", "OB-1",
                        "EMP_NUM", "871903",
                        "FIRST_NAME", "Meena",
                        "LAST_NAME", "Verma",
                        "CPF_CODE", "CPF/871903",
                        "BALANCE_COMPONENT", "EMPLOYER",
                        "OLD_AMOUNT", new BigDecimal("396110"),
                        "NEW_AMOUNT", new BigDecimal("388640"),
                        "REASON_CODE", "CLERICAL_ERROR",
                        "INTEREST_EFFECT_AMOUNT", null,
                        "CORRECTION_STATUS", "REJECTED",
                        "ATTACHMENT_ID", 7L,
                        "DOCUMENT_REFERENCE_NUMBER", "SSP statement",
                        "FILE_NAME", "e.pdf",
                        "UPLOADED_BY", "m@x",
                        "UPLOADED_AT", Timestamp.from(Instant.parse("2026-11-01T05:00:00Z")),
                        "DOCUMENT_TYPE_NAME", "Sanction Order"),
                row(
                        "CORRECTION_REF", "OB-2",
                        "EMP_NUM", "889001",
                        "FIRST_NAME", null,
                        "LAST_NAME", "Historyman",
                        "CPF_CODE", null,
                        "BALANCE_COMPONENT", "VPF",
                        "OLD_AMOUNT", BigDecimal.ONE,
                        "NEW_AMOUNT", BigDecimal.TEN,
                        "REASON_CODE", "CLERICAL_ERROR",
                        "INTEREST_EFFECT_AMOUNT", null,
                        "CORRECTION_STATUS", "PENDING_AGM_CPF",
                        "ATTACHMENT_ID", null,
                        "DOCUMENT_REFERENCE_NUMBER", null,
                        "FILE_NAME", null,
                        "UPLOADED_BY", null,
                        "UPLOADED_AT", null,
                        "DOCUMENT_TYPE_NAME", null));
        var rows = service.correctionLines(user, SPEC).items();
        assertThat(rows.getFirst().difference()).isEqualByComparingTo("-7470");
        assertThat(rows.getFirst().evidenceId()).isEqualTo(7L);
        assertThat(rows.get(1).evidenceId()).isNull();
        assertThat(rows.get(1).name()).isEqualTo("Historyman");
    }

    @Test
    void SRS_6_48_anEntryCarriesItsMemberAndInvoice() {
        db.value("SELECT COUNT(*) FROM cpf_supplementary_payment", 1L);
        db.rows(
                "SELECT p.entry_number",
                row(
                        "ENTRY_NUMBER", "CSP-1",
                        "EMP_NUM", "871907",
                        "FIRST_NAME", "Prakash",
                        "LAST_NAME", "Barre",
                        "EMPLOYEE_TYPE", "STAFF",
                        "CPF_CODE", "CPF/871907",
                        "EMPLOYMENT_STATUS", "SEPARATED",
                        "DESIGNATION_NAME", "AM",
                        "CATEGORY_CODE", null,
                        "LOCATION_NAME", "Delhi",
                        "SYSTEM_SANCTION_NUMBER", "HQ-F-1",
                        "EMPLOYEE_CONTRIBUTION", new BigDecimal("149"),
                        "EMPLOYER_CONTRIBUTION", new BigDecimal("162"),
                        "VPF_CONTRIBUTION", BigDecimal.ZERO,
                        "SANCTIONED_AMOUNT", new BigDecimal("311"),
                        "SANCTION_ORDER_NO", "17(49)",
                        "SANCTION_ORDER_DATE", DAY,
                        "PAYMENT_STATUS", "RELEASED",
                        "INVOICE_NUMBER", "201506303540",
                        "POSTING_STATUS", "SENT",
                        "SENT_TO_APPS_AT", Timestamp.from(Instant.parse("2015-06-30T05:30:00Z")),
                        "APPS_CANCEL_STATUS", "NOT_REQUESTED"));
        var r = service.supplementaryPayments(user, SPEC).items().getFirst();
        assertThat(r.sanctioned()).isEqualByComparingTo("311");
        assertThat(r.orderDate()).isEqualTo(LocalDate.parse("2026-04-01"));
        assertThat(r.invoiceStatus()).isEqualTo("SENT");
    }

    private void change(String status, String newId) {
        db.rows(
                "SELECT z.hrms_reference",
                row(
                        "HRMS_REFERENCE", "HRMS-PZC-0011",
                        "EMP_NUM", "871906",
                        "FIRST_NAME", "Kamal",
                        "LAST_NAME", "Das",
                        "CHANGE_REASON", "ZONE_SURRENDER",
                        "FROM_ZONE", "North",
                        "TO_ZONE", "East",
                        "HRMS_ORDER_REFERENCE", "O/1",
                        "OLD_CPF_MEMBER_ID", "CPF/1",
                        "NEW_CPF_MEMBER_ID", newId,
                        "EFFECTIVE_DATE", null,
                        "HRMS_DECISION", "REJECTED",
                        "PAYROLL_STATUS", status,
                        "TASK_NO", null));
    }

    @Test
    void BR_CPF_020_aChangeRejectedInHrmsCarriesNoNewId() {
        db.value("SELECT COUNT(*) FROM cpf_parent_zone_change", 1L);
        change("REJECTED_IN_HRMS", null);
        var r = service.zoneChanges(user, SPEC).items().getFirst();
        assertThat(r.status()).isEqualTo("REJECTED_IN_HRMS");
        assertThat(r.newId()).isNull();
    }

    @Test
    void SRS_6_49_theDetailHoldsTheMemberIdsAndNoComputedBalance() {
        change("APPLIED", "CPF/2");
        db.rows(
                "SELECT m.identifier_value",
                cols("CPF/1", "North", DAY, DAY, "SUPERSEDED", "ZONE_SURRENDER"),
                cols("CPF/2", "East", DAY, null, "ACTIVE", null));
        db.rows(
                "SELECT z.carried_employee",
                cols(null, null, null, null, "PENDING", null, null, null, null));
        var d = service.zoneChange(user, "HRMS-PZC-0011");
        assertThat(d.change().newId()).isEqualTo("CPF/2");
        assertThat(d.carriedEmployee()).isNull();
        assertThat(d.ids()).hasSize(2);
        assertThat(d.ids().get(1).to()).isNull();
    }

    @Test
    void SRS_6_52_theListHoldsOnlyWhatAppsHasNotAcknowledged() {
        db.value("SELECT COUNT(*) FROM iocpf_register", 1L);
        db.rows(
                "SELECT i.iocpf_number",
                cols(
                        "IOCPF-1",
                        "871905",
                        "S",
                        "Gupta",
                        "PARENT_ZONE_CHANGE",
                        BigDecimal.TEN,
                        "SENT",
                        null,
                        2));
        var r = service.iocpfPending(user, SPEC).items().getFirst();
        assertThat(r.retries()).isEqualTo(2);
        assertThat(r.sentAt()).isNull();
        assertThat(db.queries("i.apps_status IN ('PENDING', 'SENT')")).isNotEmpty();
    }

    @Test
    void SRS_G_54_aNameReadsAsDrawn() {
        assertThat(CpfScreenService.name("Ravi", "Sharma")).isEqualTo("R. Sharma");
        assertThat(CpfScreenService.name(" ", "Sharma")).isEqualTo("Sharma");
        assertThat(CpfScreenService.name(null, null)).isEmpty();
        assertThat(CpfScreenService.name("Ravi", null)).isEqualTo("Ravi");
    }
}
