package in.gov.fci.payroll.reporting.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.FieldSources;
import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.reporting.business.ReportModelRules;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * M-12 Report Definition versions (plan 15; SRS-18-10, SRS-G-12): a proposal is whole, waits alone,
 * takes a free day; the decision applies to a pending version only and ends the one before.
 */
class ReportVersionServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccessContext context = mock(AccessContext.class);
    private final PayConfiguration pay = mock(PayConfiguration.class);
    private final FieldSources fieldSources = mock(FieldSources.class);
    private final RuleEngine rules = mock(RuleEngine.class);
    private final ReportVersionService service =
            new ReportVersionService(
                    jdbc,
                    mock(Authorization.class),
                    approvals,
                    audit,
                    pay,
                    fieldSources,
                    rules,
                    Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void wire() {
        when(jdbc.queryForList(
                        contains("FROM report_definition WHERE report_key"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of(4L));
        when(jdbc.queryForList(
                        contains("approval_state = 'PENDING_APPROVAL'"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of());
        SubmittedApproval item = mock(SubmittedApproval.class);
        when(item.id()).thenReturn(19L);
        when(approvals.submit(any(), any(Submission.class), any())).thenReturn(item);
    }

    private static ReportVersionService.Proposal proposal(
            boolean blocked, String reason, boolean lock) {
        return new ReportVersionService.Proposal(
                blocked ? null : "PDF",
                lock,
                blocked ? "REGENERATE" : "STORE",
                blocked,
                reason,
                TODAY.plusDays(3));
    }

    private static void defect(ThrowingCallable call, String expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(String.valueOf(e.getMessageArgs()[0])).isEqualTo(expected));
    }

    @Test
    void SRS_18_10_aProposalIsWholeWaitsAloneAndTakesAFreeDay() {
        defect(() -> service.propose(context, "PAYSLIP", null), "PROPOSAL");
        assertThatThrownBy(
                        () ->
                                service.propose(
                                        context,
                                        "PAYSLIP",
                                        new ReportVersionService.Proposal(
                                                "WORD", false, "STORE", false, null, TODAY)))
                .isInstanceOf(FciException.class);
        when(jdbc.queryForList(
                        contains("approval_state = 'PENDING_APPROVAL'"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of(8L));
        defect(() -> service.propose(context, "PAYSLIP", proposal(false, null, true)), "PENDING");
        wire();
        when(jdbc.queryForObject(
                        contains("AND effective_from = ?"), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);
        defect(
                () -> service.propose(context, "PAYSLIP", proposal(false, null, true)),
                "EFFECTIVE_TAKEN");
        when(jdbc.queryForObject(
                        contains("AND effective_from = ?"), eq(Integer.class), any(Object[].class)))
                .thenReturn(null);
        when(jdbc.queryForObject(
                        contains("AND version_no = ?"), eq(Long.class), any(Object[].class)))
                .thenReturn(null);
        assertThatThrownBy(() -> service.propose(context, "PAYSLIP", proposal(false, null, true)))
                .isInstanceOf(IllegalStateException.class);
        when(jdbc.queryForList(
                        contains("FROM report_definition WHERE report_key"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.propose(context, null, proposal(false, null, true)))
                .isInstanceOf(FciException.class);
    }

    @Test
    void SRS_18_10_aBlockedVersionKeepsItsReasonAndTheNumberFollowsTheLast() {
        when(jdbc.queryForObject(
                        contains("MAX(version_no)"), eq(Integer.class), any(Object[].class)))
                .thenReturn(2);
        when(jdbc.queryForObject(
                        contains("AND version_no = ?"), eq(Long.class), any(Object[].class)))
                .thenReturn(33L);
        service.propose(context, "PAYSLIP", proposal(true, " Layout awaited ", false));
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("INSERT INTO report_definition_version"), args.capture());
        assertThat(args.getValue()[1]).isEqualTo(3);
        // US-GAP-34: the rounding / presentation key follows the layout format.
        assertThat(args.getValue()[4]).isNull();
        assertThat(args.getValue()[5]).isEqualTo("N");
        assertThat(args.getValue()[7]).isEqualTo("Y");
        assertThat(args.getValue()[8]).isEqualTo("Layout awaited");
        verify(jdbc).update(contains("SET approval_id"), eq(19L), eq(33L));
    }

    private ApprovalDecided decided(String entity, ApprovalStatus outcome, String reason) {
        return new ApprovalDecided(
                19L, "M-12", entity, "33", outcome, reason, null, "approver", Instant.now(), null);
    }

    private void version(String state) {
        when(jdbc.queryForMap(
                        contains("FROM report_definition_version WHERE report_version_id"),
                        any(Object[].class)))
                .thenReturn(
                        Map.of(
                                "REPORT_DEF_ID",
                                4L,
                                "EFFECTIVE_FROM",
                                Date.valueOf(TODAY),
                                "APPROVAL_STATE",
                                state));
    }

    @Test
    void SRS_G_12_theDecisionAppliesToAPendingVersionAndEndsTheOneBefore() {
        service.on(decided("report_definition_version", ApprovalStatus.APPROVED, null));
        verify(jdbc, never()).queryForMap(any(String.class), any(Object[].class));
        version("APPROVED");
        service.on(decided(ReportVersionService.ENTITY, ApprovalStatus.APPROVED, null));
        verify(audit, never()).record(any(), any());
        version("PENDING_APPROVAL");
        service.on(decided(ReportVersionService.ENTITY, ApprovalStatus.REJECTED, "Wrong format"));
        verify(jdbc).update(contains("SET approval_state = ?"), eq("REJECTED"), eq(33L));
        when(jdbc.queryForList(
                        contains("effective_to IS NULL AND report_version_id <>"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of());
        service.on(decided(ReportVersionService.ENTITY, ApprovalStatus.APPROVED, null));
        verify(jdbc, never()).update(contains("SET effective_to = ?"), any(Object[].class));
        when(jdbc.queryForList(
                        contains("effective_to IS NULL AND report_version_id <>"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of(30L));
        service.on(decided(ReportVersionService.ENTITY, ApprovalStatus.APPROVED, null));
        verify(jdbc)
                .update(
                        contains("SET effective_to = ?"),
                        eq(Date.valueOf(TODAY.minusDays(1))),
                        eq(30L));
    }

    private ReportVersionService.Proposal modelled(
            List<ReportModelRules.Field> fields, List<ReportModelRules.Control> controls) {
        return new ReportVersionService.Proposal(
                "EXCEL",
                true,
                "STORE",
                false,
                null,
                TODAY.plusDays(3),
                "ROUND.RUPEE",
                List.of(
                        new ReportModelRules.Parameter(
                                "YYMM", "Month", "MONTH", true, false, " ", " A locked month ")),
                fields,
                controls,
                List.of(
                        new ReportModelRules.Distribution(
                                " Finance ", "INTERNAL_ROLE", "SCREEN", " ")));
    }

    /**
     * SRS-18-9 (US-GAP-34): the version is written with its parameter set, its field bindings
     * resolved to the component and field-source ids, its control totals and its routing.
     */
    @Test
    void SRS_18_9_theReportDefinitionModelIsWrittenWithTheVersion() throws Exception {
        when(jdbc.queryForObject(
                        contains("AND version_no = ?"), eq(Long.class), any(Object[].class)))
                .thenReturn(33L);
        when(pay.component(101))
                .thenReturn(
                        Optional.of(
                                new PayConfiguration.Component(501L, 101, "Basic", false, true)));
        when(fieldSources.field("employee", "pan_number"))
                .thenReturn(
                        Optional.of(new FieldSources.Field(61L, "employee", "pan_number", "HRMS")));
        service.propose(
                context,
                "PAYSLIP",
                modelled(
                        List.of(
                                new ReportModelRules.Field(
                                        "Basic", ReportModelRules.Binding.COMPONENT, "101", null),
                                new ReportModelRules.Field(
                                        "PAN",
                                        ReportModelRules.Binding.FIELD_SOURCE,
                                        "employee.pan_number",
                                        " "),
                                new ReportModelRules.Field(
                                        "Count", ReportModelRules.Binding.AGGREGATE, "COUNT", null),
                                new ReportModelRules.Field(
                                        "Note", ReportModelRules.Binding.LITERAL, "Final", null),
                                new ReportModelRules.Field(
                                        "Net", ReportModelRules.Binding.DERIVED, "BASIC", "#,##0")),
                        List.of(
                                new ReportModelRules.Control(
                                        "Gross", "POSTED_PAYROLL", BigDecimal.ONE, true))));
        ArgumentCaptor<Object[]> version = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("INSERT INTO report_definition_version"), version.capture());
        assertThat(version.getValue()[4]).isEqualTo("ROUND.RUPEE");
        verify(jdbc)
                .update(
                        contains("INSERT INTO report_parameter"),
                        eq(33L),
                        eq("YYMM"),
                        eq("Month"),
                        eq("MONTH"),
                        eq("Y"),
                        eq("N"),
                        eq(null),
                        eq("A locked month"),
                        eq(1));
        verify(jdbc)
                .update(
                        contains("INSERT INTO report_field_binding"),
                        eq(33L),
                        eq(1),
                        eq("Basic"),
                        eq("COMPONENT"),
                        eq(501L),
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(null));
        verify(jdbc)
                .update(
                        contains("INSERT INTO report_field_binding"),
                        eq(33L),
                        eq(2),
                        eq("PAN"),
                        eq("FIELD_SOURCE"),
                        eq(null),
                        eq(61L),
                        eq(null),
                        eq(null),
                        eq(null));
        verify(jdbc)
                .update(
                        contains("INSERT INTO report_field_binding"),
                        eq(33L),
                        eq(3),
                        eq("Count"),
                        eq("AGGREGATE"),
                        eq(null),
                        eq(null),
                        eq("COUNT"),
                        eq(null),
                        eq(null));
        verify(jdbc)
                .update(
                        contains("INSERT INTO report_field_binding"),
                        eq(33L),
                        eq(4),
                        eq("Note"),
                        eq("LITERAL"),
                        eq(null),
                        eq(null),
                        eq(null),
                        eq("Final"),
                        eq(null));
        verify(jdbc)
                .update(
                        contains("INSERT INTO report_field_binding"),
                        eq(33L),
                        eq(5),
                        eq("Net"),
                        eq("DERIVED"),
                        eq(null),
                        eq(null),
                        eq(null),
                        eq("BASIC"),
                        eq("#,##0"));
        verify(jdbc)
                .update(
                        contains("INSERT INTO report_control_total"),
                        eq(33L),
                        eq("Gross"),
                        eq("POSTED_PAYROLL"),
                        eq(BigDecimal.ONE),
                        eq("Y"));
        verify(jdbc)
                .update(
                        contains("INSERT INTO report_distribution"),
                        eq(33L),
                        eq("Finance"),
                        eq("INTERNAL_ROLE"),
                        eq("SCREEN"),
                        eq(null));
    }

    /**
     * SRS-18-9: an optional, role-scoped parameter and a control total that is no release gate are
     * written as N / Y and N; a proposal without a model part holds none of it.
     */
    @Test
    void SRS_18_9_anOptionalRoleScopedParameterAndAControlThatIsNoGateAreWrittenSo()
            throws Exception {
        when(jdbc.queryForObject(
                        contains("AND version_no = ?"), eq(Long.class), any(Object[].class)))
                .thenReturn(33L);
        ReportVersionService.Proposal p =
                new ReportVersionService.Proposal(
                        "EXCEL",
                        true,
                        "STORE",
                        false,
                        null,
                        TODAY.plusDays(3),
                        "ROUND.RUPEE",
                        List.of(
                                new ReportModelRules.Parameter(
                                        "UNIT",
                                        "Unit",
                                        "LOCATION",
                                        false,
                                        true,
                                        "OWN_UNIT",
                                        "In the user's scope")),
                        List.of(
                                new ReportModelRules.Field(
                                        "Members",
                                        ReportModelRules.Binding.AGGREGATE,
                                        "COUNT",
                                        null)),
                        List.of(
                                new ReportModelRules.Control(
                                        "Net", "POSTED_PAYROLL", BigDecimal.ZERO, false)),
                        List.of());
        service.propose(context, "PAYSLIP", p);
        verify(jdbc)
                .update(
                        contains("INSERT INTO report_parameter"),
                        eq(33L),
                        eq("UNIT"),
                        eq("Unit"),
                        eq("LOCATION"),
                        eq("N"),
                        eq("Y"),
                        eq("OWN_UNIT"),
                        eq("In the user's scope"),
                        eq(1));
        verify(jdbc)
                .update(
                        contains("INSERT INTO report_control_total"),
                        eq(33L),
                        eq("Net"),
                        eq("POSTED_PAYROLL"),
                        eq(BigDecimal.ZERO),
                        eq("N"));

        ReportVersionService.Proposal bare =
                new ReportVersionService.Proposal(
                        "PDF", false, "STORE", false, null, TODAY, null, null, null, null, null);
        assertThat(bare.parameters()).isEmpty();
        assertThat(bare.fields()).isEmpty();
        assertThat(bare.controls()).isEmpty();
        assertThat(bare.distribution()).isEmpty();
    }

    /**
     * SRS-18-9, M-12 alternate flow: an unresolved binding rejects the definition, nothing written.
     */
    @Test
    void SRS_18_9_anUnresolvedBindingOrAnUncomputableTotalRejectsTheDefinition() throws Exception {
        when(pay.component(999)).thenReturn(Optional.empty());
        when(rules.checkFormula("NOPE", false))
                .thenThrow(
                        new FciException(
                                in.gov.fci.payroll.platform.error.CommonErrorCode
                                        .VALIDATION_FAILED));
        for (ReportModelRules.Field bad :
                List.of(
                        new ReportModelRules.Field(
                                "B", ReportModelRules.Binding.COMPONENT, "999", null),
                        new ReportModelRules.Field(
                                "B", ReportModelRules.Binding.COMPONENT, "BASIC", null),
                        new ReportModelRules.Field(
                                "B", ReportModelRules.Binding.FIELD_SOURCE, "employee", null),
                        new ReportModelRules.Field(
                                "B", ReportModelRules.Binding.FIELD_SOURCE, ".pan", null),
                        new ReportModelRules.Field(
                                "B", ReportModelRules.Binding.FIELD_SOURCE, "employee.", null),
                        new ReportModelRules.Field(
                                "B", ReportModelRules.Binding.FIELD_SOURCE, "employee.x", null),
                        new ReportModelRules.Field(
                                "B", ReportModelRules.Binding.DERIVED, "NOPE", null))) {
            defect(
                    () -> service.propose(context, "PAYSLIP", modelled(List.of(bad), List.of())),
                    "FIELD_UNRESOLVED");
        }
        defect(
                () ->
                        service.propose(
                                context,
                                "PAYSLIP",
                                modelled(
                                        List.of(
                                                new ReportModelRules.Field(
                                                        "Note",
                                                        ReportModelRules.Binding.LITERAL,
                                                        "x",
                                                        null)),
                                        List.of(
                                                new ReportModelRules.Control(
                                                        "Gross",
                                                        "POSTED_PAYROLL",
                                                        BigDecimal.ZERO,
                                                        true)))),
                "CONTROL_NOT_COMPUTABLE");
        verify(jdbc, never())
                .update(contains("INSERT INTO report_definition_version"), any(Object[].class));
    }

    /** SRS-18-9: the versions list each version with its model, as proposed. */
    @Test
    @SuppressWarnings("unchecked")
    void SRS_18_9_eachVersionIsListedWithItsModel() throws Exception {
        when(jdbc.query(
                        contains("FROM report_definition_version WHERE report_def_id"),
                        any(RowMapper.class),
                        any(Object[].class)))
                .thenAnswer(
                        call -> {
                            RowMapper<?> m = call.getArgument(1);
                            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                            when(rs.getLong(1)).thenReturn(33L);
                            when(rs.getInt(2)).thenReturn(2);
                            when(rs.getDate(3)).thenReturn(java.sql.Date.valueOf(TODAY));
                            when(rs.getTimestamp(12))
                                    .thenReturn(java.sql.Timestamp.from(Instant.now()));
                            when(rs.getString(13)).thenReturn("ROUND.RUPEE");
                            return List.of(m.mapRow(rs, 0));
                        });
        when(jdbc.query(contains("FROM report_field_binding"), any(RowMapper.class), eq(33L)))
                .thenAnswer(
                        call -> {
                            RowMapper<?> m = call.getArgument(1);
                            List<Object> out = new java.util.ArrayList<>();
                            String[][] rows = {
                                {"Basic", "COMPONENT", "101", null, null, null, null},
                                {"PAN", "FIELD_SOURCE", null, "employee", "pan_number", null, null},
                                {"Count", "AGGREGATE", null, null, null, "COUNT", null},
                                {"Note", "LITERAL", null, null, null, null, "Final"},
                                {"Net", "DERIVED", null, null, null, null, "BASIC"}
                            };
                            for (String[] r : rows) {
                                java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                                for (int c = 0; c < r.length; c++) {
                                    when(rs.getString(c + 1)).thenReturn(r[c]);
                                }
                                out.add(m.mapRow(rs, 0));
                            }
                            return out;
                        });
        when(jdbc.query(contains("FROM report_parameter"), any(RowMapper.class), eq(33L)))
                .thenAnswer(
                        call -> {
                            RowMapper<?> m = call.getArgument(1);
                            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                            when(rs.getString(1)).thenReturn("YYMM");
                            when(rs.getString(4)).thenReturn("Y");
                            when(rs.getString(5)).thenReturn("N");
                            return List.of(m.mapRow(rs, 0));
                        });
        when(jdbc.query(contains("FROM report_control_total"), any(RowMapper.class), eq(33L)))
                .thenAnswer(
                        call -> {
                            RowMapper<?> m = call.getArgument(1);
                            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                            when(rs.getString(1)).thenReturn("Gross");
                            when(rs.getString(4)).thenReturn("N");
                            return List.of(m.mapRow(rs, 0));
                        });
        when(jdbc.query(contains("FROM report_distribution"), any(RowMapper.class), eq(33L)))
                .thenAnswer(
                        call -> {
                            RowMapper<?> m = call.getArgument(1);
                            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                            when(rs.getString(1)).thenReturn("Finance");
                            return List.of(m.mapRow(rs, 0));
                        });
        ReportVersionService.Version v = service.versions(context, "PAYSLIP").get(0);
        assertThat(v.roundingRuleKey()).isEqualTo("ROUND.RUPEE");
        assertThat(v.fields())
                .extracting(ReportModelRules.Field::reference)
                .containsExactly("101", "employee.pan_number", "COUNT", "Final", "BASIC");
        assertThat(v.parameters().get(0).mandatory()).isTrue();
        assertThat(v.parameters().get(0).roleScoped()).isFalse();
        assertThat(v.controls().get(0).releaseGate()).isFalse();
        assertThat(v.distribution().get(0).consumerName()).isEqualTo("Finance");
    }
}
