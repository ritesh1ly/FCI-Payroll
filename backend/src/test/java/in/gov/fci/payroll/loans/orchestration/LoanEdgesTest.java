package in.gov.fci.payroll.loans.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.employee.api.HrmsTaskEffects;
import in.gov.fci.payroll.loans.business.LoanRules;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.payroll.api.StaffDeductions;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import tools.jackson.databind.ObjectMapper;

/**
 * The loan module's edge cases (plan 09 phases 09b … 09f): refusals and defensive paths of the
 * register, the ledger, the recovery source, the changes and the decision effects that the ITs do
 * not reach (SRS-12-3, SRS-12-5, SRS-12-21, SRS-12-22, SRS-12-26; R-RULE).
 */
class LoanEdgesTest {

    private static final Instant AT = Instant.parse("2026-10-26T06:00:00Z");

    private static AccessContext at(Long location) {
        return new AccessContext(
                3L, "maker@x", "Maker", "s", "P", location, null, List.of(), Map.of(), Set.of(),
                Set.of(), Set.of());
    }

    private static ApprovalDecided decision(String entity, ApprovalStatus outcome, String payload) {
        return new ApprovalDecided(
                9L, "I-04", entity, "1|X|t", outcome, null, payload, "checker", AT);
    }

    // ---------------------------------------------------------------- the register

    @Test
    @SuppressWarnings("unchecked")
    void SRS_12_26_theRegisterScopesTheUserAndRefusesABadRangeAndAFailingFile() throws Exception {
        NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
        OrgDirectory org = mock(OrgDirectory.class);
        Authorization policy = mock(Authorization.class);
        LoanQueryService q = new LoanQueryService(named, org, policy);
        assertThatThrownBy(() -> q.loans(at(null), null, null, new PageSpec(0, 20)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.ACCESS_DENIED));
        when(org.cycleUnitOf(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> q.loans(at(5L), null, null, new PageSpec(0, 20)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.CYCLE_NO_UNIT));
        when(policy.isAtHeadquarters(any())).thenReturn(true);
        for (String[] r :
                new String[][] {{null, "202610"}, {"202610", "2026"}, {"202611", "202610"}}) {
            assertThatThrownBy(() -> q.reportSize(at(1L), r[0], r[1]))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e ->
                                    assertThat(e.getErrorCode())
                                            .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
        }
        when(named.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(null);
        assertThat(q.reportSize(at(1L), "202610", "202610")).isZero();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(anyString())).thenReturn("1|PLI_ADVANCE|tok");
        when(rs.getString("last_name")).thenReturn(null);
        when(rs.getString("first_name")).thenReturn("Asha");
        doAnswer(i -> List.of(((RowMapper<Object>) i.getArgument(2)).mapRow(rs, 0)))
                .when(named)
                .query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        var requests = q.requests(at(1L), new PageSpec(0, 20));
        assertThat(requests.total()).isZero();
        assertThat(requests.items().get(0).loanType()).isEqualTo("PLI_ADVANCE");
        assertThat(requests.items().get(0).submittedAt()).isNull();
        when(rs.getString("entity_key")).thenReturn("1");
        assertThat(q.requests(at(1L), new PageSpec(0, 20)).items().get(0).loanType()).isNull();
        assertThat(q.loans(at(1L), " ", " ", new PageSpec(0, 20)).total()).isZero();

        ExportSink failing = mock(ExportSink.class);
        doAnswer(
                        i -> {
                            throw new IOException("disk full");
                        })
                .when(failing)
                .row(any(Object[].class));
        doAnswer(
                        i -> {
                            ((RowCallbackHandler) i.getArgument(2)).processRow(rs);
                            return null;
                        })
                .when(named)
                .query(
                        startsWith("SELECT l.location_name"),
                        any(SqlParameterSource.class),
                        any(RowCallbackHandler.class));
        assertThatThrownBy(() -> q.report(at(1L), "202610", "202610", failing))
                .isInstanceOf(IOException.class);
    }

    // ---------------------------------------------------------------- the ledger

    @Test
    void SRS_12_26_aTransferWithoutBothUnitsIsNotRecordedAndAnUnsetDateOrOrderIsAllowed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LoanLedgerEffect ledger =
                new LoanLedgerEffect(jdbc, mock(OrgDirectory.class), mock(LoanWriter.class));
        ledger.on(new EmployeeEvents.EmployeeTransferred(7L, 1L, null, 4L, null, null, "c"));
        ledger.on(new EmployeeEvents.EmployeeTransferred(7L, 1L, 3L, null, null, null, "c"));
        verifyNoInteractions(jdbc);
        Map<String, Object> loan = new HashMap<>();
        loan.put("LOAN_ID", 11L);
        loan.put("PRINCIPAL_OUTSTANDING", null);
        loan.put("INTEREST_OUTSTANDING", new BigDecimal("100"));
        when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of(loan));
        ledger.on(new EmployeeEvents.EmployeeTransferred(7L, 1L, 3L, 4L, null, null, "c"));
        verify(jdbc)
                .update(
                        startsWith("INSERT INTO loan_event"),
                        eq(11L),
                        any(),
                        eq(new BigDecimal("100")),
                        eq(3L),
                        eq(4L),
                        eq("IOGA: outstanding moved with the transfer"));
    }

    @Test
    void SRS_12_3_theLedgerSkipsAnotherUnitAndClosesOnlyWhenNothingIsOutstanding() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OrgDirectory org = mock(OrgDirectory.class);
        LoanWriter writer = mock(LoanWriter.class);
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(300L);
        when(org.cycleUnitOf(30L)).thenReturn(Optional.of(unit));
        when(org.cycleUnitOf(40L)).thenReturn(Optional.empty());
        Map<String, Object> other = row(1L, 40L, "1000", "0", "500", "0");
        Map<String, Object> noOffice = row(2L, null, "1000", "0", "500", "0");
        Map<String, Object> interestLeft = row(3L, 30L, "500", "200", "500", "100");
        interestLeft.put("BALANCE_INSTALMENTS", null);
        when(jdbc.queryForList(anyString(), eq(9L), eq("202610"), eq("202610")))
                .thenReturn(List.of(other, noOffice, interestLeft));
        new LoanLedgerEffect(jdbc, org, writer)
                .on(new CycleLocked(9L, 300L, "202610", "checker", AT));
        verify(writer)
                .status(
                        eq(3L),
                        any(),
                        any(),
                        eq("ACTIVE"),
                        eq(new BigDecimal("0")),
                        eq(new BigDecimal("100")),
                        anyString(),
                        eq("checker"),
                        any());
        verify(writer, never())
                .status(eq(1L), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static Map<String, Object> row(
            long id, Long location, String principal, String interest, String paidP, String paidI) {
        Map<String, Object> r = new HashMap<>();
        r.put("LOAN_ID", id);
        r.put("LOAN_NUMBER", "LN-" + id);
        r.put("LOAN_STATUS", "ACTIVE");
        r.put("EMPLOYEE_ID", 7L);
        r.put("PRINCIPAL_OUTSTANDING", new BigDecimal(principal));
        r.put("INTEREST_OUTSTANDING", new BigDecimal(interest));
        r.put("PAID_INSTALMENTS", 0);
        r.put("BALANCE_INSTALMENTS", 3);
        r.put("LOCATION_ID", location);
        r.put("SEPARATION_DATE", null);
        r.put("PRINCIPAL_AMOUNT", new BigDecimal(paidP));
        r.put("INTEREST_AMOUNT", new BigDecimal(paidI));
        r.put("COMPUTED", 1L);
        return r;
    }

    // ---------------------------------------------------------------- the recovery source

    @Test
    void SRS_12_3_aZeroInstalmentIsSkippedAndAnUnsetHeadRefuses() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(
                        i -> {
                            RowCallbackHandler h = i.getArgument(1);
                            ResultSet rs = mock(ResultSet.class);
                            when(rs.getString(1)).thenReturn("FESTIVAL_ADVANCE");
                            when(rs.getBigDecimal(2)).thenReturn(BigDecimal.valueOf(910));
                            h.processRow(rs);
                            return null;
                        })
                .when(jdbc)
                .query(
                        startsWith("SELECT v.value_code"),
                        any(RowCallbackHandler.class),
                        any(Object[].class));
        doAnswer(
                        i -> {
                            RowCallbackHandler h = i.getArgument(1);
                            for (Object[] due :
                                    new Object[][] {
                                        {"FESTIVAL_ADVANCE", "0"}, {"PLI_ADVANCE", "500"}
                                    }) {
                                ResultSet rs = mock(ResultSet.class);
                                when(rs.getString(1)).thenReturn((String) due[0]);
                                when(rs.getBigDecimal(2))
                                        .thenReturn(new BigDecimal((String) due[1]));
                                h.processRow(rs);
                            }
                            return null;
                        })
                .when(jdbc)
                .query(
                        startsWith("SELECT t.loan_type_code"),
                        any(RowCallbackHandler.class),
                        any(Object[].class));
        LoanRecoveryDeduction source = new LoanRecoveryDeduction(jdbc);
        assertThat(source.order()).isEqualTo(50);
        StaffDeductions.Run run = source.open(LocalDate.of(2026, 10, 1));
        assertThat(run.versions()).contains("FESTIVAL_ADVANCE=910");
        StaffDeductions.Member member =
                new StaffDeductions.Member(
                        7L, 3L, "IDA", YearMonth.of(2026, 10), List.of(), BigDecimal.TEN);
        assertThatThrownBy(() -> run.deductions(member))
                .isInstanceOf(StaffDeductions.NotComputable.class)
                .hasMessageContaining("PLI_ADVANCE");
    }

    // ---------------------------------------------------------------- the changes

    @Test
    void SRS_12_21_aChangeNeedsAnOpenLoanOfThisUnitWithoutAnotherChangeAndItsDocument() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LoanChanges changes = mock(LoanChanges.class);
        OrgDirectory org = mock(OrgDirectory.class);
        PayrollRecords payroll = mock(PayrollRecords.class);
        LoanChangeService service =
                new LoanChangeService(
                        jdbc,
                        changes,
                        org,
                        payroll,
                        mock(Authorization.class),
                        mock(MakerChecker.class),
                        mock(DocumentStore.class),
                        new ObjectMapper(),
                        Clock.fixed(AT, ZoneOffset.UTC),
                        mock(in.gov.fci.payroll.audit.api.AuditService.class));
        LoanChangeService.Document none = null;
        when(changes.openLoan(5L)).thenReturn(null);
        assertThatThrownBy(() -> service.foreclose(at(1L), 5L, none, none))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
        Map<String, Object> loan = new HashMap<>();
        loan.put("LOAN_ID", 5L);
        loan.put("LOAN_NUMBER", "LN-5");
        loan.put("LOAN_SOURCE", "LABOUR_ENT");
        loan.put("EMPLOYEE_ID", 7L);
        loan.put("PRINCIPAL_OUTSTANDING", new BigDecimal("4000"));
        loan.put("INTEREST_OUTSTANDING", BigDecimal.ZERO);
        when(changes.openLoan(5L)).thenReturn(loan);
        when(jdbc.queryForObject(
                        startsWith("SELECT location_id"), eq(Long.class), any(Object[].class)))
                .thenReturn(30L);
        when(jdbc.queryForObject(
                        startsWith("SELECT COUNT(*) FROM approval_queue"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(1L);
        assertThatThrownBy(() -> service.foreclose(at(1L), 5L, none, none))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.LOAN_OPEN_REQUEST));
        when(jdbc.queryForObject(
                        startsWith("SELECT COUNT(*) FROM approval_queue"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(null);
        assertThatThrownBy(() -> service.foreclose(at(1L), 5L, none, none))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("RECEIPT_MISSING"));
        LoanChangeService.Document empty =
                new LoanChangeService.Document(
                        "SANCTION_ORDER",
                        null,
                        LocalDate.of(2026, 10, 1),
                        "AGM",
                        "r.pdf",
                        new byte[0]);
        assertThatThrownBy(() -> service.foreclose(at(1L), 5L, empty, empty))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("RECEIPT_MISSING"));
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(300L);
        when(org.cycleUnitOf(30L)).thenReturn(Optional.of(unit));
        when(payroll.cycle(300L, "202610"))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(1L, 300L, "202610", "LOCKED")));
        LoanChangeService.NewSchedule october =
                new LoanChangeService.NewSchedule(4, new BigDecimal("1000"), 0, null, "202610");
        assertThatThrownBy(() -> service.reschedule(at(1L), 5L, october, empty, empty))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getMessageArgs())
                                        .containsExactly("START_BEFORE_OPEN_PERIOD"));
        LoanChangeService.NewSchedule november =
                new LoanChangeService.NewSchedule(4, new BigDecimal("1000"), 0, null, "202611");
        assertThatThrownBy(() -> service.reschedule(at(1L), 5L, november, empty, empty))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getMessageArgs())
                                        .containsExactly("SIGNED_REQUEST_MISSING"));
        // US-GAP-202 (SRS-12-18): with the receipt or the signed request but no order, blocked.
        LoanChangeService.Document file =
                new LoanChangeService.Document(
                        "SANCTION_ORDER",
                        "R-1",
                        LocalDate.of(2026, 10, 1),
                        "AGM",
                        "r.pdf",
                        new byte[] {1});
        assertThatThrownBy(() -> service.foreclose(at(1L), 5L, null, file))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("ORDER_MISSING"));
        assertThatThrownBy(() -> service.foreclose(at(1L), 5L, empty, file))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("ORDER_MISSING"));
        assertThatThrownBy(() -> service.reschedule(at(1L), 5L, november, null, file))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("ORDER_MISSING"));
        // Wave D review 4 (SRS-G-30): an order without its document type or its issue date is
        // refused, never stored as a system file without checks.
        for (LoanChangeService.Document partial :
                new LoanChangeService.Document[] {
                    new LoanChangeService.Document(
                            null, "O-1", LocalDate.of(2026, 10, 1), "AGM", "o.pdf", new byte[] {1}),
                    new LoanChangeService.Document(
                            " ", "O-1", LocalDate.of(2026, 10, 1), "AGM", "o.pdf", new byte[] {1}),
                    new LoanChangeService.Document(
                            "SANCTION_ORDER", "O-1", null, "AGM", "o.pdf", new byte[] {1})
                }) {
            assertThatThrownBy(() -> service.foreclose(at(1L), 5L, partial, file))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e ->
                                    assertThat(e.getMessageArgs())
                                            .containsExactly("ORDER_PARTICULARS"));
            assertThatThrownBy(() -> service.reschedule(at(1L), 5L, november, partial, file))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e ->
                                    assertThat(e.getMessageArgs())
                                            .containsExactly("ORDER_PARTICULARS"));
        }
        loan.put("LOAN_SOURCE", "HRMS");
        assertThatThrownBy(() -> service.foreclose(at(1L), 5L, empty, empty))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("HRMS_LOAN"));
    }

    @Test
    void SRS_12_4_theRemainingInterestAndTheOpenMonthAreRead() {
        Map<String, Object> loan = new HashMap<>();
        loan.put("PRINCIPAL_OUTSTANDING", new BigDecimal("4000"));
        loan.put("INTEREST_OUTSTANDING", new BigDecimal("300"));
        assertThat(
                        LoanChanges.remaining(
                                        loan,
                                        4,
                                        new BigDecimal("1000"),
                                        1,
                                        new BigDecimal("300"),
                                        "202611")
                                .interestAmount())
                .isEqualByComparingTo("300");
        loan.put("INTEREST_OUTSTANDING", null);
        assertThat(
                        LoanChanges.remaining(loan, 4, new BigDecimal("1000"), 0, null, "202611")
                                .interestAmount())
                .isNull();

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OrgDirectory org = mock(OrgDirectory.class);
        PayrollRecords payroll = mock(PayrollRecords.class);
        LoanChanges changes = new LoanChanges(jdbc, mock(LoanWriter.class), org, payroll);
        List<LoanRules.Line> lines =
                List.of(new LoanRules.Line(1, "202609", new BigDecimal("1000"), BigDecimal.ZERO));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L))).thenReturn(null);
        assertThat(changes.open(7L, lines, LocalDate.of(2026, 10, 26)).get(0).payrollYymm())
                .as("no office: from this month")
                .isEqualTo("202610");
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L))).thenReturn(30L);
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(300L);
        when(org.cycleUnitOf(30L)).thenReturn(Optional.of(unit));
        when(payroll.cycle(300L, "202610"))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(1L, 300L, "202610", "LOCKED")));
        assertThat(changes.open(7L, lines, LocalDate.of(2026, 10, 26)).get(0).payrollYymm())
                .isEqualTo("202611");
        List<LoanRules.Line> later =
                List.of(new LoanRules.Line(1, "202612", new BigDecimal("1000"), BigDecimal.ZERO));
        assertThat(changes.open(7L, later, LocalDate.of(2026, 10, 26))).isSameAs(later);
    }

    // ---------------------------------------------------------------- the decisions

    // SRS-12-17 (l.2670): a change takes effect only on the Checker's approval; nothing (no change,
    // no event) on a rejection or another entity's decision. BMAD TR-022: the SRS-12-10 member
    // notice of an approved I-04 foreclosure / reschedule is proved end to end in LoanChangeIT.
    @Test
    void SRS_12_17_aDecisionTakesEffectOnlyWhenApprovedReadableAndTheLoanIsOpen() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LoanChanges changes = mock(LoanChanges.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        LoanChangeEffect effect = new LoanChangeEffect(jdbc, changes, new ObjectMapper(), events);
        when(changes.openLoan(5L)).thenReturn(null);
        effect.on(decision(LoanChangeService.ENTITY, ApprovalStatus.REJECTED, "{}"));
        effect.on(decision("other", ApprovalStatus.APPROVED, "{}"));
        verifyNoInteractions(changes, events);
        assertThatThrownBy(
                        () ->
                                effect.on(
                                        decision(
                                                LoanChangeService.ENTITY,
                                                ApprovalStatus.APPROVED,
                                                "not json")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                effect.on(
                                        decision(
                                                LoanChangeService.ENTITY,
                                                ApprovalStatus.APPROVED,
                                                "{\"loanId\":5}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer open");

        LoanSanctionEffect sanction =
                new LoanSanctionEffect(
                        jdbc,
                        mock(LoanWriter.class),
                        new ObjectMapper(),
                        mock(in.gov.fci.payroll.employee.api.EmployeeDirectory.class),
                        mock(in.gov.fci.payroll.organisation.api.OrgDirectory.class),
                        mock(ApplicationEventPublisher.class));
        sanction.on(decision(LoanSanctionService.ENTITY, ApprovalStatus.RETURNED, "{}"));
        sanction.on(decision(LoanSanctionService.ENTITY, ApprovalStatus.REJECTED, "{}"));
        sanction.on(decision("other_entity", ApprovalStatus.APPROVED, "{}"));
        assertThatThrownBy(
                        () ->
                                sanction.on(
                                        decision(
                                                LoanSanctionService.ENTITY,
                                                ApprovalStatus.APPROVED,
                                                "not json")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(LoanSanctionEffect.decimal(new ObjectMapper().readTree("{\"a\":null}"), "a"))
                .isNull();
    }

    @Test
    void SRS_12_17_anHrmsRecordThatIsNotOneOrOfAnotherFieldIsRefused() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LoanHrmsEffects effects =
                new LoanHrmsEffects(
                        jdbc,
                        mock(LoanWriter.class),
                        mock(LoanChanges.class),
                        new ObjectMapper(),
                        inService(7L));
        for (String bad : new String[] {null, "[1]", "\"text\""}) {
            assertThatThrownBy(() -> effects.check("LOAN_SANCTION", 7L, bad))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e -> assertThat(e.getMessageArgs()).containsExactly("NOT_A_RECORD"));
        }
        assertThatThrownBy(() -> effects.check("PAN", 7L, "{}"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("FIELD"));
        when(jdbc.queryForObject(anyString(), eq(String.class), anyLong())).thenReturn("maker");
        assertThatThrownBy(
                        () ->
                                effects.apply(
                                        new HrmsTaskEffects.Task(
                                                1L, "HCA-1", 7L, "PAN", "{}", null, null),
                                        "checker",
                                        9L,
                                        AT))
                .isInstanceOf(FciException.class);
        when(jdbc.queryForList(anyString(), eq("LOAN_TYPE_X"), any(), any())).thenReturn(List.of());
        assertThatThrownBy(
                        () -> effects.check("LOAN_SANCTION", 7L, "{\"loanType\":\"LOAN_TYPE_X\"}"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("TYPE"));
        when(jdbc.queryForList(anyString(), eq("PLI_ADVANCE"), any(), any()))
                .thenReturn(List.of(Map.of("LOAN_TYPE_ID", 2L, "LOAN_TYPE_CODE", "PLI_ADVANCE")));
        assertThatThrownBy(
                        () ->
                                effects.check(
                                        "LOAN_SANCTION",
                                        7L,
                                        "{\"loanType\":\"PLI_ADVANCE\",\"sanctioned\":0}"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("AMOUNT"));
        // I-04 field table (L2713, SRS-12-1): the HRMS record is held to the type's interest.
        when(jdbc.queryForList(anyString(), eq("HBA"), any(), any()))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "LOAN_TYPE_ID",
                                        3L,
                                        "LOAN_TYPE_CODE",
                                        "HBA",
                                        "IS_INTEREST_BEARING",
                                        "Y")));
        String noInterest =
                "{\"loanType\":\"%s\",\"sanctioned\":20000,\"principalInstalments\":4,"
                        + "\"instalment\":5000,\"startYymm\":\"202611\"}";
        assertThatThrownBy(() -> effects.check("LOAN_SANCTION", 7L, noInterest.formatted("HBA")))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("INTEREST_REQUIRED"));
        String withInterest =
                "{\"loanType\":\"PLI_ADVANCE\",\"sanctioned\":20000,\"principalInstalments\":4,"
                        + "\"instalment\":5000,\"interestAmount\":500,\"interestInstalments\":1,"
                        + "\"interestInstalment\":500,\"startYymm\":\"202611\"}";
        assertThatThrownBy(() -> effects.check("LOAN_SANCTION", 7L, withInterest))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getMessageArgs())
                                        .as("PLI_ADVANCE bears no interest")
                                        .containsExactly("INTEREST_NOT_BORNE"));
        effects.check("LOAN_SANCTION", 7L, noInterest.formatted("PLI_ADVANCE"));
        effects.check("LOAN_SANCTION", 7L, withInterest.replace("PLI_ADVANCE", "HBA"));
        assertThat(effects.route("LOAN_SANCTION")).isEmpty();
        HrmsTaskEffects plain =
                new HrmsTaskEffects() {
                    @Override
                    public Set<String> fields() {
                        return Set.of();
                    }

                    @Override
                    public void check(String fieldCode, long employeeId, String newValue) {}

                    @Override
                    public void apply(
                            Task task, String checker, long approvalId, Instant decidedAt) {}
                };
        assertThat(plain.route("X")).as("the standard route by default").isEmpty();
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    @Test
    void SRS_12_3_aLedgerRowOfAMemberNotComputedIsNotWritten() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OrgDirectory org = mock(OrgDirectory.class);
        LoanWriter writer = mock(LoanWriter.class);
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(300L);
        when(org.cycleUnitOf(30L)).thenReturn(Optional.of(unit));
        Map<String, Object> notComputed = row(4L, 30L, "1000", "0", "500", "0");
        notComputed.put("COMPUTED", 0L);
        Map<String, Object> separatedLater = row(5L, 30L, "1000", "0", "500", "0");
        separatedLater.put("SEPARATION_DATE", Date.valueOf("2026-10-20"));
        when(jdbc.queryForList(anyString(), eq(9L), eq("202610"), eq("202610")))
                .thenReturn(List.of(notComputed, separatedLater));
        when(jdbc.queryForList(startsWith("SELECT o.location_name"), eq(5L))).thenReturn(List.of());
        new LoanLedgerEffect(jdbc, org, writer)
                .on(new CycleLocked(9L, 300L, "202610", "checker", AT));
        verify(writer, never())
                .status(eq(4L), any(), any(), any(), any(), any(), any(), any(), any());
        verify(writer)
                .status(
                        eq(5L),
                        any(),
                        any(),
                        eq("ACTIVE"),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any());
        verify(jdbc, never()).batchUpdate(anyString(), anyList());
    }

    private static in.gov.fci.payroll.employee.api.EmployeeDirectory inService(long id) {
        in.gov.fci.payroll.employee.api.EmployeeDirectory employees =
                mock(in.gov.fci.payroll.employee.api.EmployeeDirectory.class);
        when(employees.member(id))
                .thenReturn(
                        java.util.Optional.of(
                                new in.gov.fci.payroll.employee.api.EmployeeDirectory.Member(
                                        id, "100041", "A B", 30L, "IN_SERVICE")));
        return employees;
    }

    // US-GAP-199 (use case precondition l.2684): an HRMS sanction for a member who is not an
    // active, payable member is refused at intake, as on I-04; a foreclosure is not refused for it.
    @Test
    void SRS_12_17_anHrmsSanctionForAMemberNotPayableIsRefusedAtIntake() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        in.gov.fci.payroll.employee.api.EmployeeDirectory employees =
                mock(in.gov.fci.payroll.employee.api.EmployeeDirectory.class);
        LoanHrmsEffects effects =
                new LoanHrmsEffects(
                        jdbc,
                        mock(LoanWriter.class),
                        mock(LoanChanges.class),
                        new ObjectMapper(),
                        employees);
        String sanction =
                "{\"loanType\":\"PLI_ADVANCE\",\"sanctioned\":12000,\"principalInstalments\":4,"
                        + "\"instalment\":3000,\"startYymm\":\"203001\"}";
        for (String status : new String[] {"SEPARATED", "RETIRED", "SUSPENDED"}) {
            when(employees.member(7L))
                    .thenReturn(
                            java.util.Optional.of(
                                    new in.gov.fci.payroll.employee.api.EmployeeDirectory.Member(
                                            7L, "100041", "A B", 30L, status)));
            assertThatThrownBy(() -> effects.check("LOAN_SANCTION", 7L, sanction))
                    .as(status)
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e -> assertThat(e.getMessageArgs()).containsExactly("NOT_PAYABLE"));
        }
        when(employees.member(7L)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> effects.check("LOAN_SANCTION", 7L, sanction))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("NOT_PAYABLE"));
        when(employees.member(7L))
                .thenReturn(
                        java.util.Optional.of(
                                new in.gov.fci.payroll.employee.api.EmployeeDirectory.Member(
                                        7L, "100041", "A B", 30L, "ON_DEPUTATION")));
        when(jdbc.queryForList(anyString(), eq("PLI_ADVANCE"), any(), any()))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "LOAN_TYPE_ID",
                                        2L,
                                        "LOAN_TYPE_CODE",
                                        "PLI_ADVANCE",
                                        "IS_INTEREST_BEARING",
                                        "N")));
        org.assertj.core.api.Assertions.assertThatCode(
                        () -> effects.check("LOAN_SANCTION", 7L, sanction))
                .as("on deputation is payable")
                .doesNotThrowAnyException();
        when(employees.member(7L))
                .thenReturn(
                        java.util.Optional.of(
                                new in.gov.fci.payroll.employee.api.EmployeeDirectory.Member(
                                        7L, "100041", "A B", 30L, "SEPARATED")));
        when(jdbc.queryForList(anyString(), eq(7L), eq("HR-1")))
                .thenReturn(List.of(Map.of("LOAN_ID", 5L)));
        org.assertj.core.api.Assertions.assertThatCode(
                        () -> effects.check("LOAN_FORECLOSURE", 7L, "{\"loanReference\":\"HR-1\"}"))
                .as("a separated member's open loan is still foreclosed")
                .doesNotThrowAnyException();
    }

    // US-GAP-198 (SRS-12-7): a returned sanction request is told to the Makers of the member's
    // unit with the reason; a member without a unit tells no one, and nothing is written.
    @Test
    void SRS_12_7_aReturnedRequestIsToldToTheMakersOfTheMembersUnitWithTheReason() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LoanWriter writer = mock(LoanWriter.class);
        in.gov.fci.payroll.employee.api.EmployeeDirectory employees =
                mock(in.gov.fci.payroll.employee.api.EmployeeDirectory.class);
        in.gov.fci.payroll.organisation.api.OrgDirectory orgs =
                mock(in.gov.fci.payroll.organisation.api.OrgDirectory.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        LoanSanctionEffect sanction =
                new LoanSanctionEffect(jdbc, writer, new ObjectMapper(), employees, orgs, events);
        in.gov.fci.payroll.organisation.business.model.OrgLocation unit =
                mock(in.gov.fci.payroll.organisation.business.model.OrgLocation.class);
        when(unit.getId()).thenReturn(3L);
        when(employees.locationOf(8L)).thenReturn(java.util.Optional.of(30L));
        when(orgs.cycleUnitOf(30L)).thenReturn(java.util.Optional.of(unit));
        String payload = "{\"employeeId\":8,\"eis\":\"100042\",\"loanType\":\"FESTIVAL_ADVANCE\"}";
        sanction.on(
                new in.gov.fci.payroll.platform.approval.ApprovalDecided(
                        44L,
                        "I-04",
                        LoanSanctionService.ENTITY,
                        "8|FESTIVAL_ADVANCE|ab",
                        ApprovalStatus.RETURNED,
                        "Order date does not match",
                        payload,
                        "ddo@x",
                        AT));
        verify(events)
                .publishEvent(
                        new in.gov.fci.payroll.loans.api.LoanRequestReturned(
                                3L,
                                44L,
                                "100042",
                                "FESTIVAL_ADVANCE",
                                "Order date does not match",
                                "ddo@x"));
        org.mockito.Mockito.verifyNoInteractions(writer);

        when(employees.locationOf(8L)).thenReturn(java.util.Optional.empty());
        sanction.on(
                new in.gov.fci.payroll.platform.approval.ApprovalDecided(
                        45L,
                        "I-04",
                        LoanSanctionService.ENTITY,
                        "8|FESTIVAL_ADVANCE|cd",
                        ApprovalStatus.RETURNED,
                        "Wrong",
                        payload,
                        "ddo@x",
                        AT));
        verify(events, org.mockito.Mockito.times(1)).publishEvent(any(Object.class));
    }

    // ---------------------------------------------------------------- branch gaps

    /**
     * SRS-12-22: a member without an office has no locked cycle, so the open month is this month; a
     * signed request without its content is missing (SRS-20-27).
     */
    @Test
    void SRS_12_22_aMemberWithoutAnOfficeReschedulesFromThisMonthAndAnEmptyFileIsMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LoanChanges changes = mock(LoanChanges.class);
        OrgDirectory org = mock(OrgDirectory.class);
        LoanChangeService service =
                new LoanChangeService(
                        jdbc,
                        changes,
                        org,
                        mock(PayrollRecords.class),
                        mock(Authorization.class),
                        mock(MakerChecker.class),
                        mock(DocumentStore.class),
                        new ObjectMapper(),
                        Clock.fixed(AT, ZoneOffset.UTC),
                        mock(in.gov.fci.payroll.audit.api.AuditService.class));
        Map<String, Object> loan = new HashMap<>();
        loan.put("LOAN_ID", 5L);
        loan.put("LOAN_NUMBER", "LN-5");
        loan.put("LOAN_SOURCE", "LABOUR_ENT");
        loan.put("EMPLOYEE_ID", 7L);
        loan.put("PRINCIPAL_OUTSTANDING", new BigDecimal("4000"));
        loan.put("INTEREST_OUTSTANDING", BigDecimal.ZERO);
        when(changes.openLoan(5L)).thenReturn(loan);
        LoanChangeService.Document noContent =
                new LoanChangeService.Document(
                        "SANCTION_ORDER", "R-1", LocalDate.of(2026, 10, 1), "AGM", "r.pdf", null);
        LoanChangeService.NewSchedule october =
                new LoanChangeService.NewSchedule(4, new BigDecimal("1000"), 0, null, "202610");
        assertThatThrownBy(() -> service.reschedule(at(1L), 5L, october, noContent, noContent))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getMessageArgs())
                                        .containsExactly("SIGNED_REQUEST_MISSING"));
        verify(org, never()).cycleUnitOf(any());
    }

    /** SRS-12-26: a register range without its end is refused; a member's name joins its parts. */
    @Test
    @SuppressWarnings("unchecked")
    void SRS_12_26_aRangeWithoutAnEndIsRefusedAndANameWithoutASurnameIsTheFirstName()
            throws Exception {
        NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
        Authorization policy = mock(Authorization.class);
        when(policy.isAtHeadquarters(any())).thenReturn(true);
        LoanQueryService q = new LoanQueryService(named, mock(OrgDirectory.class), policy);
        assertThatThrownBy(() -> q.reportSize(at(1L), "202610", null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("entity_key")).thenReturn("1|PLI_ADVANCE|tok");
        when(rs.getString("first_name")).thenReturn("Asha");
        doAnswer(i -> List.of(((RowMapper<Object>) i.getArgument(2)).mapRow(rs, 0)))
                .when(named)
                .query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        when(rs.getString("last_name")).thenReturn("  ");
        assertThat(q.requests(at(1L), new PageSpec(0, 20)).items().get(0).employee())
                .isEqualTo("Asha");
        when(rs.getString("last_name")).thenReturn("Rao");
        assertThat(q.requests(at(1L), new PageSpec(0, 20)).items().get(0).employee())
                .isEqualTo("Asha Rao");
    }

    /**
     * SRS-12-17: a field the HRMS record sends as null is as absent (no raised first instalment).
     */
    @Test
    void SRS_12_17_anHrmsFieldSentAsNullIsAbsent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LoanHrmsEffects effects =
                new LoanHrmsEffects(
                        jdbc,
                        mock(LoanWriter.class),
                        mock(LoanChanges.class),
                        new ObjectMapper(),
                        inService(7L));
        when(jdbc.queryForList(anyString(), eq("PLI_ADVANCE"), any(), any()))
                .thenReturn(List.of(Map.of("LOAN_TYPE_ID", 2L, "LOAN_TYPE_CODE", "PLI_ADVANCE")));
        effects.check(
                "LOAN_SANCTION",
                7L,
                "{\"loanType\":\"PLI_ADVANCE\",\"sanctioned\":20000,\"principalInstalments\":4,"
                        + "\"instalment\":5000,\"firstInstalment\":null,\"startYymm\":\"202611\"}");
        ObjectMapper json = new ObjectMapper();
        assertThat(LoanSanctionEffect.decimal(json.readTree("{\"a\":null,\"b\":1.5}"), "a"))
                .isNull();
        assertThat(LoanSanctionEffect.decimal(json.readTree("{\"b\":1.5}"), "a")).isNull();
        assertThat(LoanSanctionEffect.decimal(json.readTree("{\"b\":1.5}"), "b"))
                .isEqualByComparingTo("1.5");
    }

    /** SRS-12-1: a loan without interest stores a zero interest amount and no interest tenure. */
    @Test
    void SRS_12_1_aLoanWithoutInterestStoresZeroInterestAndNoInterestTenure() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        java.sql.Connection connection = mock(java.sql.Connection.class);
        java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
        when(connection.prepareStatement(anyString(), any(String[].class))).thenReturn(ps);
        doAnswer(
                        i -> {
                            ((org.springframework.jdbc.core.PreparedStatementCreator)
                                            i.getArgument(0))
                                    .createPreparedStatement(connection);
                            ((org.springframework.jdbc.support.GeneratedKeyHolder) i.getArgument(1))
                                    .getKeyList()
                                    .add(Map.of("ID", 41L));
                            return 1;
                        })
                .when(jdbc)
                .update(
                        any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                        any(org.springframework.jdbc.support.KeyHolder.class));
        LoanWriter writer =
                new LoanWriter(jdbc, mock(in.gov.fci.payroll.audit.api.AuditService.class));
        LoanRules.Terms plain =
                new LoanRules.Terms(
                        new BigDecimal("4000"),
                        4,
                        new BigDecimal("1000"),
                        null,
                        null,
                        0,
                        null,
                        "202611");
        List<LoanRules.Line> lines =
                List.of(new LoanRules.Line(1, "202611", new BigDecimal("1000"), BigDecimal.ZERO));
        LoanWriter.Written written =
                writer.create(
                        7L,
                        2L,
                        "FESTIVAL_ADVANCE",
                        plain,
                        lines,
                        LocalDate.of(2026, 10, 26),
                        LoanWriter.Source.LABOUR_ENT,
                        null,
                        null,
                        9L,
                        "maker",
                        "checker",
                        AT);
        assertThat(written.loanNumber()).isEqualTo("LN-2026-000041");
        verify(ps).setObject(11, null, java.sql.Types.INTEGER);
        verify(ps).setBigDecimal(12, BigDecimal.ZERO);

        LoanRules.Terms interest =
                new LoanRules.Terms(
                        new BigDecimal("4000"),
                        4,
                        new BigDecimal("1000"),
                        null,
                        new BigDecimal("300"),
                        2,
                        new BigDecimal("150"),
                        "202611");
        writer.create(
                7L,
                3L,
                "HBA",
                interest,
                lines,
                LocalDate.of(2026, 10, 26),
                LoanWriter.Source.LABOUR_ENT,
                null,
                null,
                null,
                "maker",
                "checker",
                AT);
        verify(ps).setObject(11, 2, java.sql.Types.INTEGER);
        verify(ps).setBigDecimal(12, new BigDecimal("300"));
    }

    /**
     * SRS-12-4: an HRMS reschedule is checked at intake on the balance remaining; a schedule that
     * does not add up, has no instalments or starts in no month is refused with its defect, and an
     * open loan the record does not name is unknown.
     */
    @Test
    void SRS_12_4_anHrmsRescheduleThatDoesNotFitTheBalanceRemainingIsRefusedWithItsDefect() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LoanHrmsEffects effects =
                new LoanHrmsEffects(
                        jdbc,
                        mock(LoanWriter.class),
                        mock(LoanChanges.class),
                        new ObjectMapper(),
                        inService(7L));
        when(jdbc.queryForList(anyString(), eq(7L), eq("HR-1")))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "LOAN_ID",
                                        5L,
                                        "PRINCIPAL_OUTSTANDING",
                                        new BigDecimal("9000.00"),
                                        "INTEREST_OUTSTANDING",
                                        BigDecimal.ZERO)));
        String head = "{\"loanReference\":\"HR-1\",";
        Map<String, String> refused =
                Map.of(
                        head
                                + "\"principalInstalments\":3,\"instalment\":2000,"
                                + "\"startYymm\":\"203001\"}",
                        "NOT_RECONCILED",
                        head
                                + "\"principalInstalments\":0,\"instalment\":3000,"
                                + "\"startYymm\":\"203001\"}",
                        "INSTALMENTS",
                        head
                                + "\"principalInstalments\":3,\"instalment\":3000,\"startYymm\":\"2030\"}",
                        "START",
                        head
                                + "\"principalInstalments\":3,\"instalment\":0,"
                                + "\"startYymm\":\"203001\"}",
                        "INSTALMENT");
        refused.forEach(
                (payload, defect) ->
                        assertThatThrownBy(() -> effects.check("LOAN_RESCHEDULE", 7L, payload))
                                .as(defect)
                                .isInstanceOfSatisfying(
                                        FciException.class,
                                        e ->
                                                assertThat(e.getMessageArgs())
                                                        .containsExactly(defect)));

        org.assertj.core.api.Assertions.assertThatCode(
                        () ->
                                effects.check(
                                        "LOAN_RESCHEDULE",
                                        7L,
                                        head
                                                + "\"principalInstalments\":3,\"instalment\":3000,"
                                                + "\"startYymm\":\"203001\"}"))
                .as("three of 3,000 clear 9,000")
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                effects.check(
                                        "LOAN_RESCHEDULE",
                                        7L,
                                        "{\"loanReference\":\"NO-SUCH\",\"principalInstalments\":3,"
                                                + "\"instalment\":3000,\"startYymm\":\"203001\"}"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("UNKNOWN_LOAN"));
    }
}
