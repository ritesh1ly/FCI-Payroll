package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.labour.business.LabourRateRules.Rate;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * M-05 rate sets for one depot (plan 21 D21-4; SRS-4-11; PENDING DPS-21c-2, OQ-14 (v0.4)): the API
 * and table take a depot while FCI has not said whether rates differ by location. A depot set is
 * proposed at the depot (the first version, no GoI source) and listed with the depot's code and
 * name; a set of an office no longer known lists without a name.
 */
class LabourRateProposalTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final Authorization policy = mock(Authorization.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final AccessContext context = mock(AccessContext.class);
    private final LabourRateService rates =
            new LabourRateService(jdbc, policy, org, approvals, mock(AuditService.class));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wire() {
        OrgLocation hq = mock(OrgLocation.class);
        when(hq.getId()).thenReturn(1L);
        when(org.headquarters()).thenReturn(Optional.of(hq));
        OrgLocation depot = mock(OrgLocation.class);
        when(depot.getType()).thenReturn(LocationType.DEPOT);
        when(depot.getCode()).thenReturn("DP-DL01");
        when(depot.getName()).thenReturn("Okhla");
        when(org.location(7L)).thenReturn(Optional.of(depot));
        when(jdbc.query(contains("FROM org_designation"), any(RowMapper.class), eq("DPS_LABOUR")))
                .thenAnswer(
                        a -> {
                            ResultSet rs = mock(ResultSet.class);
                            when(rs.getLong(1)).thenReturn(11L);
                            when(rs.getString(4)).thenReturn("HANDLING");
                            return List.of(((RowMapper<Object>) a.getArgument(1)).mapRow(rs, 0));
                        });
    }

    @Test
    void SRS_4_11_aDepotSetIsProposedAtTheDepotAsItsFirstVersion() {
        when(jdbc.queryForObject(
                        contains("SELECT MIN(min_wage_id)"), eq(Long.class), any(), any(), any()))
                .thenReturn(41L);
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(61L, ApprovalStatus.PENDING_APPROVAL, null));
        SubmittedApproval item =
                rates.propose(
                        context,
                        new LabourRateService.Proposal(
                                "DPS_LABOUR",
                                7L,
                                LocalDate.of(2027, 1, 1),
                                List.of(new Rate(11L, new BigDecimal("781.00"))),
                                null,
                                null,
                                "  ",
                                null,
                                "Depot rates"));
        assertThat(item.id()).isEqualTo(61L);
        verify(policy).requireAt(any(), eq(LabourRateService.SCREEN), eq("CREATE"), eq(7L));
        verify(jdbc)
                .update(
                        contains("INSERT INTO labour_min_wage"),
                        eq("DPS_LABOUR"),
                        eq(11L),
                        eq("HANDLING"),
                        eq(7L),
                        eq(new BigDecimal("781.00")),
                        // US-GAP-61: a DPS set holds no Departmental Basic wage or DA.
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(1),
                        eq(Date.valueOf("2027-01-01")));
    }

    private static ResultSet set(long location, boolean wasNull) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(5L);
        when(rs.getLong("location_id")).thenReturn(location);
        when(rs.wasNull()).thenReturn(wasNull);
        when(rs.getDate("effective_from")).thenReturn(Date.valueOf("2027-01-01"));
        return rs;
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_4_11_aDepotSetListsWithTheDepotNameAndAnUnknownOfficeWithout() throws Exception {
        List<ResultSet> rows = List.of(set(7L, false), set(8L, false), set(0L, true));
        when(jdbc.query(
                        contains("GROUP BY w.location_id"),
                        any(RowMapper.class),
                        any(),
                        any(),
                        any()))
                .thenAnswer(
                        a -> {
                            java.util.List<Object> out = new java.util.ArrayList<>();
                            for (ResultSet rs : rows) {
                                out.add(((RowMapper<Object>) a.getArgument(1)).mapRow(rs, 0));
                            }
                            return out;
                        });
        assertThat(rates.page(context, "DPS_LABOUR", new PageSpec(0, 20)).items())
                .extracting(LabourRateService.RateSet::depotId, LabourRateService.RateSet::depot)
                .containsExactly(tuple(7L, "DP-DL01 - Okhla"), tuple(8L, null), tuple(null, null));
    }
}
