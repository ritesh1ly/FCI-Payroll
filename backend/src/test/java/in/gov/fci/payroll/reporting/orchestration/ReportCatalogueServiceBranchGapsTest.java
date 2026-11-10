package in.gov.fci.payroll.reporting.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * O-04 / M-12 catalogue filters (SRS-18-9, SRS-18-14): a family or a name text left out, blank or
 * padded reaches the query as no filter or as the stripped text (the name matched in upper case).
 */
class ReportCatalogueServiceBranchGapsTest {

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final ReportCatalogueService service =
            new ReportCatalogueService(
                    named,
                    policy,
                    Clock.fixed(Instant.parse("2026-11-04T05:00:00Z"), ZoneOffset.UTC));
    private final AccessContext context = mock(AccessContext.class);

    @Test
    @SuppressWarnings("unchecked")
    void SRS_18_9_aBlankFilterIsNoneAndAGivenOneIsStrippedAndMatchedInUpperCase() {
        service.page(context, null, null, new PageSpec(0, 20));
        service.page(context, "  ", "   ", new PageSpec(0, 20));
        service.page(context, " STATUTORY ", " sal ", new PageSpec(1, 10));

        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named, org.mockito.Mockito.times(3))
                .query(anyString(), params.capture(), any(RowMapper.class));
        List<MapSqlParameterSource> sent = new ArrayList<>(params.getAllValues());
        assertThat(sent.get(0).getValue("family")).isNull();
        assertThat(sent.get(0).getValue("text")).isNull();
        assertThat(sent.get(1).getValue("family")).isNull();
        assertThat(sent.get(1).getValue("text")).isNull();
        assertThat(sent.get(2).getValue("family")).isEqualTo("STATUTORY");
        assertThat(sent.get(2).getValue("text")).isEqualTo("%SAL%");
        assertThat(sent.get(2).getValue("offset")).isEqualTo(10L);
        verify(policy, org.mockito.Mockito.times(3)).require(eq(context), eq("O-04"), eq("VIEW"));
    }

    /**
     * M-12 (Wireframes v6.1, OD-121, OD-123): the drawn family CPF Control &amp; Audit is the O-07
     * set inside Control-Audit-MIS, the payroll filter takes one drawn type and also finds a report
     * for several types, and the list runs in the order of the Annexure sections.
     */
    @Test
    @SuppressWarnings("unchecked")
    void SRS_18_9_theDrawnFiltersOfM12FindTheirReports() {
        service.page(context, "M-12", "CPF Control & Audit", "DPS", null, new PageSpec(0, 20));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("d.report_key IN (:cpfKeys)")
                .contains("INSTR(d.applicable_payroll_types, :type) > 0")
                .contains("d.applicable_payroll_types = 'All'")
                .contains("d.applicable_payroll_types = 'Labour' AND :type <> 'Employee'")
                .contains("ORDER BY d.catalogue_section");
        assertThat(params.getValue().getValue("family")).isNull();
        assertThat(params.getValue().getValue("type")).isEqualTo("DPS");
        assertThat((List<String>) params.getValue().getValue("cpfKeys"))
                .contains("FCI_ESEVA_REPORT", "CPF_OBCB", "IOCPF_PENDING");
    }

    /** O-03 (Wireframes v6.1, OD-121): its own gate, its own entries, the payroll's word. */
    @Test
    @SuppressWarnings("unchecked")
    void SRS_18_24_theLabourCatalogueIsO03sEntriesOrTheLabourAnnexure() {
        service.labour(context, false, "DPS", new PageSpec(0, 20));
        service.labour(context, true, "NWNP", new PageSpec(0, 20));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named, org.mockito.Mockito.times(2))
                .query(sql.capture(), params.capture(), any(RowMapper.class));
        assertThat(sql.getAllValues().get(0)).contains("d.producing_screen_code = :screen");
        assertThat(sql.getAllValues().get(1)).contains("d.catalogue_section = 'A.11'");
        assertThat(params.getAllValues().get(0).getValue("word")).isEqualTo("%DPS%");
        assertThat(params.getAllValues().get(1).getValue("word")).isEqualTo("%NWNP%");
        verify(policy, org.mockito.Mockito.times(2)).require(eq(context), eq("O-03"), eq("VIEW"));
    }
}
