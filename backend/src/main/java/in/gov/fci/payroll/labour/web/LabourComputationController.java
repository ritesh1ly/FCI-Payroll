package in.gov.fci.payroll.labour.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.labour.orchestration.LabourComputationViews;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.constraints.Pattern;
import java.time.YearMonth;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-03 Departmental, C-04 DPS, C-05 NWNP and C-06 Incentive computation screens, the read APIs of
 * the screens as Wireframes v6.1 draws them (OD-121; API-20-9 in PENDING: the sources give no
 * contract). Each read is the screen's VIEW, in the session's depots. Nothing here writes: the runs
 * wait for FCI (LBR-20d-2, LBR-20d-3, LBR-21d-1, NWNP-22d-1).
 *
 * <pre>
 * GET /v1/labour/computations/departmental/summary?period   C-03 VIEW
 * GET /v1/labour/computations/departmental/gangs?period     C-03 VIEW
 * GET /v1/labour/computations/dps/summary?period            C-04 VIEW
 * GET /v1/labour/computations/dps/gangs?period              C-04 VIEW
 * GET /v1/labour/computations/nwnp/summary?period           C-05 VIEW
 * GET /v1/labour/computations/nwnp/gangs?period             C-05 VIEW
 * GET /v1/labour/incentives/summary?period                  C-06 VIEW
 * GET /v1/labour/incentives/gangs?period                    C-06 VIEW
 * GET /v1/labour/incentives/recomputations                  C-06 VIEW
 * GET /v1/labour/incentives/basics                          C-06 VIEW
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class LabourComputationController {

    private static final String PERIOD = "^\\d{4}-(0[1-9]|1[0-2])$";
    private static final String DEPARTMENTAL = "DEPT_LABOUR";
    private static final String DPS = "DPS_LABOUR";
    private static final String NWNP = "NWNP_LABOUR";

    private final CurrentAccess contexts;
    private final LabourComputationViews views;
    private final Paging paging;

    public LabourComputationController(
            CurrentAccess contexts, LabourComputationViews views, Paging paging) {
        this.contexts = contexts;
        this.views = views;
        this.paging = paging;
    }

    @GetMapping("/v1/labour/computations/departmental/summary")
    @PreAuthorize("@authz.gate('C-03','VIEW')")
    public ResponseEntity<LabourComputationViews.Summary> departmentalSummary(
            @RequestParam @Pattern(regexp = PERIOD) String period) {
        return noStore(views.summary(contexts.current(), DEPARTMENTAL, YearMonth.parse(period)));
    }

    @GetMapping("/v1/labour/computations/departmental/gangs")
    @PreAuthorize("@authz.gate('C-03','VIEW')")
    public ResponseEntity<Paged<LabourComputationViews.GangRow>> departmentalGangs(
            @RequestParam @Pattern(regexp = PERIOD) String period,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return gangs(DEPARTMENTAL, period, page, size);
    }

    @GetMapping("/v1/labour/computations/dps/summary")
    @PreAuthorize("@authz.gate('C-04','VIEW')")
    public ResponseEntity<LabourComputationViews.Summary> dpsSummary(
            @RequestParam @Pattern(regexp = PERIOD) String period) {
        return noStore(views.summary(contexts.current(), DPS, YearMonth.parse(period)));
    }

    @GetMapping("/v1/labour/computations/dps/gangs")
    @PreAuthorize("@authz.gate('C-04','VIEW')")
    public ResponseEntity<Paged<LabourComputationViews.GangRow>> dpsGangs(
            @RequestParam @Pattern(regexp = PERIOD) String period,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return gangs(DPS, period, page, size);
    }

    @GetMapping("/v1/labour/computations/nwnp/summary")
    @PreAuthorize("@authz.gate('C-05','VIEW')")
    public ResponseEntity<LabourComputationViews.Summary> nwnpSummary(
            @RequestParam @Pattern(regexp = PERIOD) String period) {
        return noStore(views.summary(contexts.current(), NWNP, YearMonth.parse(period)));
    }

    @GetMapping("/v1/labour/computations/nwnp/gangs")
    @PreAuthorize("@authz.gate('C-05','VIEW')")
    public ResponseEntity<Paged<LabourComputationViews.GangRow>> nwnpGangs(
            @RequestParam @Pattern(regexp = PERIOD) String period,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return gangs(NWNP, period, page, size);
    }

    @GetMapping("/v1/labour/incentives/summary")
    @PreAuthorize("@authz.gate('C-06','VIEW')")
    public ResponseEntity<LabourComputationViews.IncentiveSummary> incentiveSummary(
            @RequestParam @Pattern(regexp = PERIOD) String period) {
        return noStore(views.incentiveSummary(contexts.current(), YearMonth.parse(period)));
    }

    @GetMapping("/v1/labour/incentives/gangs")
    @PreAuthorize("@authz.gate('C-06','VIEW')")
    public ResponseEntity<Paged<LabourComputationViews.GangRow>> incentiveGangs(
            @RequestParam @Pattern(regexp = PERIOD) String period,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                views.incentiveGangs(
                        contexts.current(), YearMonth.parse(period), paging.resolve(page, size)));
    }

    @GetMapping("/v1/labour/incentives/recomputations")
    @PreAuthorize("@authz.gate('C-06','VIEW')")
    public ResponseEntity<Paged<LabourComputationViews.Recomputation>> recomputations(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(views.recomputations(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/labour/incentives/basics")
    @PreAuthorize("@authz.gate('C-06','VIEW')")
    public ResponseEntity<Paged<LabourComputationViews.IncentiveBasic>> basics(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(views.basics(contexts.current(), paging.resolve(page, size)));
    }

    private ResponseEntity<Paged<LabourComputationViews.GangRow>> gangs(
            String engine, String period, Integer page, Integer size) {
        return noStore(
                views.gangs(
                        contexts.current(),
                        engine,
                        YearMonth.parse(period),
                        paging.resolve(page, size)));
    }
}
