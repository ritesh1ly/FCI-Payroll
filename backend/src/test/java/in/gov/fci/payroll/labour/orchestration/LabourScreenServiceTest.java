package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.labour.web.LabourScreenController;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * The reads of M-02, M-05, M-06 and M-09 as drawn (OD-121; SRS-2-27, SRS-2-47, SRS-2-48, SRS-3-12,
 * SRS-5-3, SRS-G-13, SRS-G-30): each is gated on its screen and held to the session's offices, the
 * Aadhaar stays masked, and a value outside the lists is refused.
 */
class LabourScreenServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final GangStrengthService strengths = mock(GangStrengthService.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final AccessContext context = mock(AccessContext.class);
    private final LabourScreenService service =
            new LabourScreenService(jdbc, named, policy, parameters, strengths, documents);

    @BeforeEach
    void wire() {
        when(parameters.today()).thenReturn(TODAY);
        when(context.locationId()).thenReturn(3L);
        when(context.loginId()).thenReturn("hr.delhi@fci-demo.test");
        when(named.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(1L);
    }

    @SuppressWarnings("unchecked")
    private <T> T mapped(ResultSet rs) throws Exception {
        ArgumentCaptor<RowMapper<T>> mapper = ArgumentCaptor.forClass(RowMapper.class);
        verify(named).query(anyString(), any(MapSqlParameterSource.class), mapper.capture());
        return mapper.getValue().mapRow(rs, 0);
    }

    private MapSqlParameterSource counted(String fragment) {
        ArgumentCaptor<MapSqlParameterSource> p =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named).queryForObject(contains(fragment), p.capture(), eq(Long.class));
        return p.getValue();
    }

    @Test
    void SRS_2_27_theMemberListIsTheEnginesAtTheSessionsOfficesWithTheAadhaarMasked()
            throws Exception {
        service.members(
                context,
                new LabourScreenService.MemberFilter("DPS_LABOUR", 7L, " GNG-01 ", " kumar "),
                new PageSpec(0, 20));
        verify(policy).require(context, "M-02", "VIEW");
        MapSqlParameterSource p = counted("labour_profile");
        assertThat(p.getValue("type")).isEqualTo("DPS_LABOUR");
        assertThat(p.getValue("depot")).isEqualTo(7L);
        assertThat(p.getValue("gang")).isEqualTo("GNG-01");
        assertThat(p.getValue("text")).isEqualTo("%KUMAR%");
        assertThat(p.hasValue("locations")).isTrue();

        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("employee_id")).thenReturn(11L);
        when(rs.getString("labour_number")).thenReturn("DP-20114");
        when(rs.getString("first_name")).thenReturn("Harish");
        when(rs.getString("last_name")).thenReturn("Bhatt");
        when(rs.getString("aadhaar_number")).thenReturn("712300000010");
        when(rs.getLong("transfers")).thenReturn(1L);
        LabourScreenService.MemberLine line = mapped(rs);
        assertThat(line.name()).isEqualTo("Harish Bhatt");
        assertThat(line.aadhaarMasked()).isEqualTo("********0010");
        assertThat(line.transferPending()).isTrue();
    }

    @Test
    void SRS_2_27_aMemberWithoutASurnameOrPendingTransferReadsAsHeld() throws Exception {
        service.members(
                context,
                new LabourScreenService.MemberFilter(null, null, " ", " "),
                new PageSpec(0, 20));
        MapSqlParameterSource p = counted("labour_profile");
        assertThat(p.getValue("type")).isEqualTo("DEPT_LABOUR");
        assertThat(p.getValue("gang")).isNull();
        assertThat(p.getValue("text")).isNull();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("first_name")).thenReturn("Om");
        LabourScreenService.MemberLine line = mapped(rs);
        assertThat(line.name()).isEqualTo("Om");
        assertThat(line.aadhaarMasked()).isNull();
        assertThat(line.transferPending()).isFalse();
    }

    @Test
    void SRS_2_27_aValueOutsideTheEnginesIsRefusedBeforeAnyRead() {
        LabourScreenService.MemberFilter filter =
                new LabourScreenService.MemberFilter("STAFF", null, null, null);
        PageSpec spec = new PageSpec(0, 20);
        assertThatThrownBy(() -> service.members(context, filter, spec))
                .isInstanceOf(FciException.class);
        verify(named, never())
                .queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class));
    }

    @Test
    void SRS_G_13_eachExportIsGatedOnItsScreensExportBeforeItCountsOrWrites() throws Exception {
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .require(any(), anyString(), eq("EXPORT"));
        ExportSink sink = mock(ExportSink.class);
        LabourScreenService.MemberFilter members =
                new LabourScreenService.MemberFilter(null, null, null, null);
        LabourScreenService.GangFilter gangs = new LabourScreenService.GangFilter(null, null, null);
        LabourScreenService.NormFilter norms = new LabourScreenService.NormFilter(null, null, null);
        assertThatThrownBy(() -> service.memberExportSize(context, members))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.exportMembers(context, members, sink))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.gangExportSize(context, gangs))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.exportGangs(context, gangs, sink))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.normExportSize(context, norms))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.exportNorms(context, norms, sink))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.rateExportSize(context, "DPS_LABOUR"))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.exportRates(context, "DPS_LABOUR", sink))
                .isInstanceOf(FciException.class);
        verify(sink, never()).header(any(String[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_G_13_theMemberExportHoldsTheMaskedAadhaar() throws Exception {
        ExportSink sink = mock(ExportSink.class);
        when(named.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(
                        List.of(
                                new LabourScreenService.MemberLine(
                                        11L,
                                        "DL-20114",
                                        "Hemant Kumar",
                                        "DEPT_LABOUR",
                                        "Sardar",
                                        "SARDAR",
                                        "GNG-01",
                                        "Depot Okhla",
                                        "********0010",
                                        new BigDecimal("24800"),
                                        "CPF/DL-20114",
                                        "ACTIVE",
                                        false)));
        service.exportMembers(
                context, new LabourScreenService.MemberFilter(null, null, null, null), sink);
        ArgumentCaptor<Object[]> row = ArgumentCaptor.forClass(Object[].class);
        verify(sink).row(row.capture());
        assertThat(Arrays.asList(row.getAllValues().toArray()).toString())
                .doesNotContain("712300000010");
    }

    @Test
    void SRS_G_30_anOrderIsHeldToTheMembersOfficeAndAMemberOutsideIsNotFound() {
        when(jdbc.queryForList(contains("labour_profile"), eq(11L)))
                .thenReturn(List.of(Map.of("LABOUR_NUMBER", "DL-20114", "LOCATION_ID", 9L)));
        service.orders(context, 11L);
        verify(policy).requireRecordAt(context, "M-02", "VIEW", 9L);
        verify(documents).active(LabourScreenService.LABOUR_ORDERS, "DL-20114");

        byte[] file = {1};
        service.attachOrder(
                context,
                11L,
                new LabourScreenService.OrderCopy(
                        "SANCTION_ORDER", "DO/1", TODAY, "Depot Manager", "o.pdf", file));
        verify(policy).requireRecordAt(context, "M-02", "EDIT", 9L);
        ArgumentCaptor<DocumentStore.NewAttachment> a =
                ArgumentCaptor.forClass(DocumentStore.NewAttachment.class);
        verify(documents).attach(eq("hr.delhi@fci-demo.test"), a.capture());
        assertThat(a.getValue().entityKey()).isEqualTo("DL-20114");
        assertThat(a.getValue().containsPii()).isTrue();

        service.order(context, "10.0.0.1", 11L, 5L);
        verify(documents)
                .read(
                        "hr.delhi@fci-demo.test",
                        "10.0.0.1",
                        LabourScreenService.LABOUR_ORDERS,
                        "DL-20114",
                        5L);

        when(jdbc.queryForList(contains("labour_profile"), eq(12L))).thenReturn(List.of());
        assertThatThrownBy(() -> service.orders(context, 12L)).isInstanceOf(FciException.class);
    }

    @Test
    void BR_LABM_001_theFiguresCountOverTheWholeListNotAPage() {
        LabourScreenService.GangSummary summary =
                service.gangSummary(
                        context, new LabourScreenService.GangFilter("NWNP_LABOUR", 7L, "okhla"));
        verify(policy).require(context, "M-09", "VIEW");
        assertThat(summary.leaderBreaches()).isEqualTo(1L);
        assertThat(summary.endBlocked()).isEqualTo(1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void BR_LABM_011_thePoolHasNoStrengthAndAHandlingGangReadsTodaysCheck() {
        when(named.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(
                        List.of(
                                new LabourScreenService.StrengthLine(
                                        5L,
                                        "GNG-01",
                                        "Gang 1",
                                        "HANDLING",
                                        null,
                                        0,
                                        0,
                                        false,
                                        List.of()),
                                new LabourScreenService.StrengthLine(
                                        1L,
                                        "POOL",
                                        "Pool",
                                        "ANCILLARY_POOL",
                                        null,
                                        0,
                                        0,
                                        false,
                                        List.of())));
        when(strengths.read(5L, TODAY))
                .thenReturn(new GangStrengthService.DayRead(4, 3, 1, true, List.of("DL-20390")));
        when(strengths.read(1L, TODAY))
                .thenReturn(new GangStrengthService.DayRead(null, 2, 0, false, List.of()));
        Paged<LabourScreenService.StrengthLine> rows =
                service.strengthToday(
                        context,
                        new LabourScreenService.GangFilter(null, null, null),
                        new PageSpec(0, 20));
        LabourScreenService.StrengthLine gang = rows.items().get(0);
        assertThat(gang.sanctioned()).isEqualTo(4);
        assertThat(gang.shortBy()).isEqualTo(1);
        assertThat(gang.alertOpen()).isTrue();
        assertThat(gang.filledBy()).containsExactly("DL-20390");
        assertThat(rows.items().get(1).sanctioned()).isNull();
    }

    @Test
    void SRS_3_12_theNormTypeFiltersOnWhatTheSetDeclaresAndAnUnknownTypeIsRefused() {
        service.norms(
                context,
                new LabourScreenService.NormFilter(null, "HEIGHT", null),
                new PageSpec(0, 20));
        verify(policy).require(context, "M-06", "VIEW");
        MapSqlParameterSource p = counted("labour_norm");
        assertThat(p.getValue("height")).isEqualTo("Y");
        assertThat(p.getValue("handling")).isEqualTo("N");
        assertThat(p.getValue("lead")).isEqualTo("N");
        LabourScreenService.NormFilter bad =
                new LabourScreenService.NormFilter(null, "WEIGHT", null);
        PageSpec spec = new PageSpec(0, 20);
        assertThatThrownBy(() -> service.norms(context, bad, spec))
                .isInstanceOf(FciException.class);
    }

    @Test
    void SRS_3_6_theBandsAreHeldToTheNormsDepotAndAnUnknownSetIsNotFound() {
        when(jdbc.queryForList(contains("labour_norm"), eq(Long.class), eq(3L)))
                .thenReturn(List.of(7L));
        service.slabs(context, 3L);
        verify(policy).requireRecordAt(context, "M-06", "VIEW", 7L);
        when(jdbc.queryForList(contains("labour_norm"), eq(Long.class), eq(4L)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.slabs(context, 4L)).isInstanceOf(FciException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_5_11_theMinimumWageIsBasicPlusVdaTimes26AndAnEmptyOperandHasNoMgw() throws Exception {
        service.minWages(context);
        verify(policy).require(context, "M-05", "VIEW");
        ArgumentCaptor<RowMapper<LabourScreenService.MinWageLine>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        verify(jdbc).query(contains("nwnp_loc_type"), mapper.capture(), eq("NWNP_LABOUR"));
        ResultSet rs = mock(ResultSet.class);
        when(rs.getBigDecimal(3)).thenReturn(new BigDecimal("512.00"));
        when(rs.getBigDecimal(4)).thenReturn(new BigDecimal("268.00"));
        when(rs.getDate(5)).thenReturn(Date.valueOf(TODAY));
        LabourScreenService.MinWageLine line = mapper.getValue().mapRow(rs, 0);
        assertThat(line.mgw()).isEqualByComparingTo("20280");
        assertThat(line.approvalState()).isEqualTo("APPROVED");
        when(rs.getBigDecimal(4)).thenReturn(null);
        assertThat(mapper.getValue().mapRow(rs, 0).mgw()).isNull();
    }

    @Test
    void SRS_G_54_everyReadIsItsScreensGateAndTheOnlyWriteIsTheOrderUnderEdit() {
        Map<String, String> gates =
                Arrays.stream(LabourScreenController.class.getDeclaredMethods())
                        .filter(m -> m.isAnnotationPresent(GetMapping.class))
                        .collect(
                                Collectors.toMap(
                                        m -> m.getAnnotation(GetMapping.class).value()[0],
                                        m -> m.getAnnotation(PreAuthorize.class).value()));
        assertThat(gates)
                .hasSize(18)
                .containsEntry("/v1/labour/registers/members", "@authz.gate('M-02','VIEW')")
                .containsEntry(
                        "/v1/labour/registers/members/export", "@authz.gate('M-02','EXPORT')")
                .containsEntry("/v1/labour/registers/gangs", "@authz.gate('M-09','VIEW')")
                .containsEntry("/v1/labour/registers/gangs/export", "@authz.gate('M-09','EXPORT')")
                .containsEntry("/v1/labour/registers/norms", "@authz.gate('M-06','VIEW')")
                .containsEntry("/v1/labour/registers/norms/export", "@authz.gate('M-06','EXPORT')")
                .containsEntry("/v1/labour/registers/asor-rates", "@authz.gate('M-05','VIEW')")
                .containsEntry(
                        "/v1/labour/registers/rate-tables/export", "@authz.gate('M-05','EXPORT')");
        assertThat(
                        Arrays.stream(LabourScreenController.class.getDeclaredMethods())
                                .filter(m -> m.isAnnotationPresent(PostMapping.class))
                                .map(m -> m.getAnnotation(PreAuthorize.class).value()))
                .containsExactly("@authz.gate('M-02','EDIT')");
    }
}
