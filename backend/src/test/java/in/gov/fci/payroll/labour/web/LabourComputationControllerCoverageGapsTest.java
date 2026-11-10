package in.gov.fci.payroll.labour.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ControllerKit;
import in.gov.fci.payroll.labour.orchestration.LabourComputationViews;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * C-03, C-04, C-05 and C-06 (OD-121; SRS-2-29; PENDING LBR-RUNS): each screen reads its own labour
 * engine (C-03 departmental, C-04 DPS, C-05 NWNP) for the period and page asked, the incentive
 * screen its month, and nothing is cached.
 */
class LabourComputationControllerCoverageGapsTest {

    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final PageSpec PAGE = new PageSpec(0, 20);

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final AccessContext context = mock(AccessContext.class);
    private final LabourComputationViews views = mock(LabourComputationViews.class);
    private final Paging paging = mock(Paging.class);
    private final LabourComputationController controller =
            new LabourComputationController(contexts, views, paging);

    @BeforeEach
    void session() {
        when(contexts.current()).thenReturn(context);
        when(paging.resolve(0, 20)).thenReturn(PAGE);
    }

    @SuppressWarnings("unchecked")
    private static Paged<LabourComputationViews.GangRow> paged() {
        return mock(Paged.class);
    }

    @Test
    void SRS_2_29_eachScreenReadsItsOwnEngineForThePeriod() {
        LabourComputationViews.Summary dept = mock(LabourComputationViews.Summary.class);
        LabourComputationViews.Summary dps = mock(LabourComputationViews.Summary.class);
        LabourComputationViews.Summary nwnp = mock(LabourComputationViews.Summary.class);
        when(views.summary(context, "DEPT_LABOUR", OCT)).thenReturn(dept);
        when(views.summary(context, "DPS_LABOUR", OCT)).thenReturn(dps);
        when(views.summary(context, "NWNP_LABOUR", OCT)).thenReturn(nwnp);
        Paged<LabourComputationViews.GangRow> deptGangs = paged();
        Paged<LabourComputationViews.GangRow> dpsGangs = paged();
        Paged<LabourComputationViews.GangRow> nwnpGangs = paged();
        when(views.gangs(context, "DEPT_LABOUR", OCT, PAGE)).thenReturn(deptGangs);
        when(views.gangs(context, "DPS_LABOUR", OCT, PAGE)).thenReturn(dpsGangs);
        when(views.gangs(context, "NWNP_LABOUR", OCT, PAGE)).thenReturn(nwnpGangs);

        List<ResponseEntity<?>> answers =
                List.of(
                        controller.departmentalSummary("2026-10"),
                        controller.dpsSummary("2026-10"),
                        controller.nwnpSummary("2026-10"),
                        controller.departmentalGangs("2026-10", 0, 20),
                        controller.dpsGangs("2026-10", 0, 20),
                        controller.nwnpGangs("2026-10", 0, 20));

        assertThat(answers)
                .extracting(r -> (Object) r.getBody())
                .containsExactly(dept, dps, nwnp, deptGangs, dpsGangs, nwnpGangs);
        assertThat(answers).allMatch(ControllerKit::noStore);
    }

    @Test
    void SRS_2_29_theIncentiveScreenReadsItsMonth() {
        LabourComputationViews.IncentiveSummary summary =
                mock(LabourComputationViews.IncentiveSummary.class);
        when(views.incentiveSummary(context, OCT)).thenReturn(summary);
        Paged<LabourComputationViews.GangRow> gangs = paged();
        when(views.incentiveGangs(context, OCT, PAGE)).thenReturn(gangs);

        var s = controller.incentiveSummary("2026-10");
        var g = controller.incentiveGangs("2026-10", 0, 20);

        assertThat(s.getBody()).isSameAs(summary);
        assertThat(g.getBody()).isSameAs(gangs);
        assertThat(List.of(s, g)).allMatch(ControllerKit::noStore);
    }
}
