package in.gov.fci.payroll.increment.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.increment.business.IncrementErrorCode;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * M-03 Pay Scales & Grades (plan 17; SRS M-03 field table, SC-17-0030 … 0036; D17-10): Headquarters
 * proposes a version with its minimum below its maximum, every stage between them and none
 * repeated, starting after every existing version; the members above the new maximum are listed
 * (SC-17-0028); on approval the version before ends the day before and the circular is linked; a
 * rejection changes nothing.
 */
class PayScaleServiceTest {

    private static final LocalDate FROM = LocalDate.of(2027, 1, 1);
    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final Circulars circulars = mock(Circulars.class);
    private final AuditService audit = mock(AuditService.class);
    private final PayScaleService service =
            new PayScaleService(named, policy, approvals, circulars, audit);
    private final AccessContext hq =
            new AccessContext(
                    1L, "ra@x", "R", "s", "P", 1L, null, List.of(), Map.of(), Set.of(), Set.of(),
                    Set.of());

    private static PayScaleService.Proposal proposal(
            String min, String max, List<BigDecimal> stages, LocalDate from, String reason) {
        return new PayScaleService.Proposal(
                "E-4",
                min == null ? null : new BigDecimal(min),
                max == null ? null : new BigDecimal(max),
                new BigDecimal("1500"),
                from,
                3L,
                stages,
                reason,
                " WR-2026-01 ");
    }

    private void band(String scaleType) {
        when(named.queryForList(
                        contains("FROM pay_scale_band WHERE band_code"),
                        any(SqlParameterSource.class)))
                .thenReturn(List.of(Map.of("BAND_ID", 4L, "SCALE_TYPE", scaleType)));
    }

    private void revision(String... payrollTypes) {
        when(named.queryForList(
                        contains("FROM wage_revision WHERE revision_code"),
                        any(SqlParameterSource.class),
                        eq(String.class)))
                .thenReturn(List.of(payrollTypes));
    }

    private static List<BigDecimal> stages(String... s) {
        return java.util.Arrays.stream(s).map(BigDecimal::new).toList();
    }

    private ErrorCode refusal(PayScaleService.Proposal p) {
        return ((FciException) catchThrowable(() -> service.propose(hq, p))).getErrorCode();
    }

    @Test
    void SC_17_0031_theMinimumIsBelowTheMaximumAndEveryStageLiesBetweenThemOnceOnly() {
        assertThat(refusal(proposal("100", "200", stages("150"), FROM, "r")))
                .as("a unit office")
                .isEqualTo(CommonErrorCode.ACCESS_DENIED);
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        List<PayScaleService.Proposal> bad =
                List.of(
                        proposal(null, "200", stages("150"), FROM, "r"),
                        proposal("100", null, stages("150"), FROM, "r"),
                        proposal("200", "200", stages("200"), FROM, "r"),
                        proposal("100", "200", stages("150"), null, "r"),
                        proposal("100", "200", stages("150"), FROM, null),
                        proposal("100", "200", stages("150"), FROM, " "),
                        proposal("100", "200", null, FROM, "r"),
                        proposal("100", "200", List.of(), FROM, "r"),
                        proposal("100", "200", stages("99"), FROM, "r"),
                        proposal("100", "200", stages("201"), FROM, "r"),
                        proposal("100", "200", stages("150", "150"), FROM, "r"));
        for (PayScaleService.Proposal p : bad) {
            assertThat(refusal(p)).as(p.toString()).isEqualTo(IncrementErrorCode.ENTRY);
        }
        verify(named, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void SC_17_0036_anUnknownScaleIsNotFoundAndAVersionStartsAfterEveryExistingOne() {
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        when(named.queryForList(
                        contains("FROM pay_scale_band WHERE band_code"),
                        any(SqlParameterSource.class)))
                .thenReturn(List.of());
        assertThat(refusal(proposal("100", "200", stages("100", "200"), FROM, "r")))
                .isEqualTo(CommonErrorCode.NOT_FOUND);
        band("IDA");
        revision("STAFF_IDA, STAFF_CDA");
        when(named.queryForObject(
                        contains("effective_from >= :from"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(1L);
        assertThat(refusal(proposal("100", "200", stages("100", "200"), FROM, "r")))
                .isEqualTo(IncrementErrorCode.ENTRY);
    }

    // BR-INCR-006: members drawing above a revised scale maximum are flagged for review.
    @Test
    void SC_17_0028_theProposalIsSentWithItsStagesAndListsTheMembersAboveTheNewMaximum() {
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        band("IDA");
        revision("STAFF_IDA, STAFF_CDA");
        when(named.queryForObject(
                        contains("effective_from >= :from"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(null);
        when(named.queryForObject(
                        contains("COALESCE(MAX(version_no)"),
                        any(SqlParameterSource.class),
                        eq(Integer.class)))
                .thenReturn(null);
        when(named.queryForObject(
                        contains("SELECT band_version_id"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(9L);
        when(named.queryForList(
                        contains("current_basic > :max"),
                        any(SqlParameterSource.class),
                        eq(String.class)))
                .thenReturn(List.of("109991"));
        SubmittedApproval item = mock(SubmittedApproval.class);
        when(item.id()).thenReturn(30L);
        when(item.status()).thenReturn(ApprovalStatus.PENDING_APPROVAL);
        when(approvals.submit(any(), any(), any())).thenReturn(item);
        PayScaleService.Proposed out =
                service.propose(
                        hq,
                        proposal("100", "200", stages("200", "100", "150"), FROM, " Scale order "));
        assertThat(out.aboveMaximum()).containsExactly("109991");
        assertThat(out.status()).isEqualTo("PENDING_APPROVAL");
        verify(named, times(3))
                .update(
                        contains("INSERT INTO pay_scale_band_stage"),
                        any(SqlParameterSource.class));
    }

    @Test
    void SRS_13_23_aVersionNamesAStaffWageRevisionOfTheScalesPattern() {
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        band("CDA");
        PayScaleService.Proposal ok = proposal("100", "200", stages("100", "200"), FROM, "r");
        for (String code : new String[] {null, "  "}) {
            PayScaleService.Proposal none =
                    new PayScaleService.Proposal(
                            ok.bandCode(),
                            ok.min(),
                            ok.max(),
                            ok.stagnationAmount(),
                            ok.effectiveFrom(),
                            ok.circularId(),
                            ok.stages(),
                            ok.reason(),
                            code);
            assertThat(refusal(none)).as(String.valueOf(code)).isEqualTo(IncrementErrorCode.ENTRY);
        }
        // Unknown (or rejected / cancelled), and a revision of another pattern only.
        when(named.queryForList(
                        contains("FROM wage_revision WHERE revision_code"),
                        any(SqlParameterSource.class),
                        eq(String.class)))
                .thenReturn(List.of());
        assertThat(refusal(ok)).isEqualTo(IncrementErrorCode.ENTRY);
        revision("STAFF_IDA,DEPT_LABOUR");
        assertThat(refusal(ok)).isEqualTo(IncrementErrorCode.ENTRY);
        verify(named, never()).update(anyString(), any(SqlParameterSource.class));
        // A CDA scale under a revision covering STAFF_CDA is recorded with its code.
        revision("DEPT_LABOUR, STAFF_CDA");
        when(named.queryForObject(
                        contains("effective_from >= :from"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(1L);
        assertThat(refusal(ok)).as("then the date check").isEqualTo(IncrementErrorCode.ENTRY);
        org.mockito.ArgumentCaptor<SqlParameterSource> row =
                org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named, org.mockito.Mockito.atLeastOnce())
                .queryForObject(contains("effective_from >= :from"), row.capture(), eq(Long.class));
        assertThat(row.getValue().getValue("revision")).isEqualTo("WR-2026-01");
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_13_23_theWageRevisionLookupListsStaffRevisionsOnly() throws Exception {
        when(named.query(
                        contains("FROM wage_revision WHERE revision_status NOT IN"),
                        any(SqlParameterSource.class),
                        any(RowMapper.class)))
                .thenAnswer(
                        i -> {
                            ResultSet rs = mock(ResultSet.class);
                            when(rs.getString("revision_code")).thenReturn("WR-2026-01");
                            when(rs.getString("revision_name")).thenReturn("Staff revision");
                            when(rs.getString("payroll_types")).thenReturn("STAFF_IDA");
                            when(rs.getString("revision_status")).thenReturn("DEFINED");
                            return List.of(((RowMapper<Object>) i.getArgument(2)).mapRow(rs, 0));
                        });
        assertThat(service.wageRevisions(hq))
                .containsExactly(
                        new PayScaleService.WageRevision(
                                "WR-2026-01", "Staff revision", "STAFF_IDA", "DEFINED"));
        verify(policy).require(hq, "M-03", "VIEW");
    }

    private ApprovalDecided decided(String entity, ApprovalStatus outcome) {
        return new ApprovalDecided(
                30L,
                "M-03",
                entity,
                "9",
                outcome,
                null,
                "{\"circularId\":3}",
                "checker@x",
                Instant.now());
    }

    @Test
    void D17_10_theApprovedVersionEndsTheOneBeforeAndARejectionChangesNothing() {
        service.on(decided("other", ApprovalStatus.APPROVED));
        verify(named, never()).queryForMap(anyString(), any(SqlParameterSource.class));
        when(named.queryForMap(
                        contains("FROM pay_scale_band_version v JOIN"),
                        any(SqlParameterSource.class)))
                .thenReturn(
                        Map.of(
                                "BAND_ID",
                                4L,
                                "EFFECTIVE_FROM",
                                Date.valueOf(FROM),
                                "BAND_CODE",
                                "E-4"));
        service.on(decided(PayScaleService.ENTITY, ApprovalStatus.REJECTED));
        verify(named, never()).update(anyString(), any(SqlParameterSource.class));
        service.on(decided(PayScaleService.ENTITY, ApprovalStatus.APPROVED));
        verify(named).update(contains("SET effective_to = :before"), any(SqlParameterSource.class));
        verify(circulars)
                .link(
                        eq("checker@x"),
                        eq(3L),
                        eq(Circulars.LinkType.MASTER_ENTRY),
                        anyString(),
                        eq("9"),
                        anyString());
    }

    @Test
    void SC_17_0035_theScaleListShowsTheVersionInForceAndAPendingVersion() throws Exception {
        when(named.queryForObject(
                        contains("SELECT COUNT(*) FROM pay_scale_band"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(2L);
        ResultSet inForce = mock(ResultSet.class);
        when(inForce.getDate("effective_from")).thenReturn(Date.valueOf(FROM));
        when(inForce.getInt("pending")).thenReturn(1);
        ResultSet none = mock(ResultSet.class);
        when(named.query(
                        anyString(),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<PayScaleService.ScaleRow>>any()))
                .thenAnswer(
                        call -> {
                            RowMapper<?> m = call.getArgument(2);
                            return List.of(m.mapRow(inForce, 0), m.mapRow(none, 1));
                        });
        var page = service.scales(hq, new PageSpec(0, 20));
        assertThat(page.items())
                .extracting(PayScaleService.ScaleRow::pending)
                .containsExactly(true, false);
        assertThat(page.items().get(1).effectiveFrom()).isNull();
    }

    private PayScaleService.NewScale scale(String code, String name, String type) {
        return new PayScaleService.NewScale(
                code, name, type, proposal("100", "200", stages("100", "200"), FROM, "New scale"));
    }

    private ErrorCode defineRefusal(PayScaleService.NewScale s) {
        return ((FciException) catchThrowable(() -> service.define(hq, s))).getErrorCode();
    }

    @Test
    void SC_17_0030_aNewScaleNeedsAUniqueCodeANameAndAnIdaOrCdaType() {
        assertThat(defineRefusal(scale("E-9", "IDA E-9", "IDA")))
                .as("a unit office")
                .isEqualTo(CommonErrorCode.ACCESS_DENIED);
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        List<PayScaleService.NewScale> bad =
                List.of(
                        scale(null, "IDA E-9", "IDA"),
                        scale(" ", "IDA E-9", "IDA"),
                        scale("E-90-LONGER", "IDA E-9", "IDA"),
                        scale("E 9", "IDA E-9", "IDA"),
                        scale("E-9", null, "IDA"),
                        scale("E-9", "  ", "IDA"),
                        scale("E-9", "N".repeat(101), "IDA"),
                        scale("E-9", "IDA E-9", null),
                        scale("E-9", "IDA E-9", "FND"),
                        new PayScaleService.NewScale("E-9", "IDA E-9", "IDA", null));
        for (PayScaleService.NewScale s : bad) {
            assertThat(defineRefusal(s)).as(s.toString()).isEqualTo(IncrementErrorCode.ENTRY);
        }
        // Unique whatever the case, and a rejected definition keeps its code.
        when(named.queryForObject(
                        contains("WHERE UPPER(band_code) = :code"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(1L);
        assertThat(defineRefusal(scale("e-4", "IDA E-4", "IDA")))
                .isEqualTo(IncrementErrorCode.SCALE_CODE_TAKEN);
        verify(named, never()).update(anyString(), any(SqlParameterSource.class));
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void SC_17_0030_aNewScaleIsHeldInactiveAndProposedWithItsFirstVersion() {
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        when(named.queryForObject(
                        contains("WHERE UPPER(band_code) = :code"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(0L);
        band("CDA");
        revision("STAFF_CDA");
        when(named.queryForObject(
                        contains("SELECT band_version_id"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(11L);
        SubmittedApproval item = mock(SubmittedApproval.class);
        when(item.id()).thenReturn(31L);
        when(item.status()).thenReturn(ApprovalStatus.PENDING_APPROVAL);
        when(approvals.submit(any(), any(), any())).thenReturn(item);
        PayScaleService.Proposed out = service.define(hq, scale(" C-1 ", " CDA Level 1 ", "CDA"));
        assertThat(out.approvalId()).isEqualTo(31L);
        org.mockito.ArgumentCaptor<SqlParameterSource> header =
                org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named).update(contains("INSERT INTO pay_scale_band ("), header.capture());
        assertThat(header.getValue().getValue("code")).isEqualTo("C-1");
        assertThat(header.getValue().getValue("name")).isEqualTo("CDA Level 1");
        assertThat(header.getValue().getValue("type")).isEqualTo("CDA");
        verify(named)
                .update(
                        contains("INSERT INTO pay_scale_band_version"),
                        any(SqlParameterSource.class));
    }

    @Test
    void SC_17_0030_theFirstApprovedVersionActivatesTheScaleALaterOneDoesNot() {
        when(named.queryForMap(
                        contains("FROM pay_scale_band_version v JOIN"),
                        any(SqlParameterSource.class)))
                .thenReturn(
                        Map.of(
                                "BAND_ID",
                                4L,
                                "EFFECTIVE_FROM",
                                Date.valueOf(FROM),
                                "BAND_CODE",
                                "C-1",
                                "ACTIVE_FLAG",
                                "N"));
        service.on(decided(PayScaleService.ENTITY, ApprovalStatus.REJECTED));
        verify(named, never())
                .update(contains("SET active_flag = 'Y'"), any(SqlParameterSource.class));
        service.on(decided(PayScaleService.ENTITY, ApprovalStatus.APPROVED));
        verify(named).update(contains("SET active_flag = 'Y'"), any(SqlParameterSource.class));
        verify(audit, times(2)).record(eq("checker@x"), any(AuditService.Entry.class));
        org.mockito.Mockito.clearInvocations(named, audit);
        when(named.queryForMap(
                        contains("FROM pay_scale_band_version v JOIN"),
                        any(SqlParameterSource.class)))
                .thenReturn(
                        Map.of(
                                "BAND_ID",
                                4L,
                                "EFFECTIVE_FROM",
                                Date.valueOf(FROM),
                                "BAND_CODE",
                                "C-1",
                                "ACTIVE_FLAG",
                                "Y"));
        service.on(decided(PayScaleService.ENTITY, ApprovalStatus.APPROVED));
        verify(named, never())
                .update(contains("SET active_flag = 'Y'"), any(SqlParameterSource.class));
        verify(audit).record(eq("checker@x"), any(AuditService.Entry.class));
    }
}
