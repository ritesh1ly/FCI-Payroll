package in.gov.fci.payroll.payroll.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.payroll.business.SimulationRules;
import in.gov.fci.payroll.payroll.orchestration.SimulationService;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * C-09 General (Simulation) Run (plan 05 phase 05i; SRS-15-7, SRS C-09). The SRS names the screen,
 * not the API (PENDING API-05-4). A run computed at once answers 201 with its preview; one queued
 * in the background answers 202 with its record (status QUEUED, its C-13 request).
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/simulations")
public class SimulationController {

    /**
     * The request. {@code members}: employee numbers (MEMBER: one, MEMBER_LIST: several, OFFICE:
     * none); each checked against the rules and the office on the server. The technical bound is
     * that of the A-07 setting; the configured maximum applies below it.
     */
    public record RunBody(
            @NotNull SimulationRules.Population population,
            @Size(max = 100000) List<String> members,
            @NotNull SimulationRules.WhatIf whatIf,
            BigDecimal daIda,
            BigDecimal daCda) {}

    private final CurrentAccess contexts;
    private final SimulationService simulations;
    private final Paging paging;
    private final ExportStreaming exports;

    public SimulationController(
            CurrentAccess contexts,
            SimulationService simulations,
            Paging paging,
            ExportStreaming exports) {
        this.exports = exports;
        this.contexts = contexts;
        this.simulations = simulations;
        this.paging = paging;
    }

    /** SRS C-09 tiles of the current cycle, with the two configured limits. */
    @GetMapping("/tiles")
    @PreAuthorize("@authz.gate('C-09','VIEW')")
    public ResponseEntity<SimulationService.Overview> tiles() {
        return noStore(simulations.overview(contexts.current()));
    }

    /** Recent Simulation Runs of the office, newest first. */
    @GetMapping
    @PreAuthorize("@authz.gate('C-09','VIEW')")
    public ResponseEntity<Paged<SimulationService.RunView>> list(
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String search,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                simulations.list(contexts.current(), blank(search), paging.resolve(page, size)));
    }

    /** SRS l.1789: the run records, the simulation marker on every row. */
    @GetMapping("/export")
    @PreAuthorize("@authz.gate('C-09','EXPORT')")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String search,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        String filter = blank(search);
        exports.checkSize(simulations.exportSize(context, filter));
        return exports.stream(
                "simulation-runs", format, sink -> simulations.export(context, filter, sink));
    }

    private static String blank(String search) {
        return search == null || search.isBlank() ? null : search.trim();
    }

    /** One run with its summary; {@code current} false when the data changed since it ran. */
    @GetMapping("/{id}")
    @PreAuthorize("@authz.gate('C-09','VIEW')")
    public ResponseEntity<SimulationService.Detail> detail(@PathVariable @Min(1) long id) {
        return noStore(simulations.detail(contexts.current(), id));
    }

    /** SRS-15-7: runs a simulation (201 computed now, 202 queued in the background). */
    @PostMapping
    @PreAuthorize("@authz.gate('C-09','RUN')")
    public ResponseEntity<SimulationService.Detail> run(@Valid @RequestBody RunBody body) {
        SimulationService.Detail result =
                simulations.run(
                        contexts.current(),
                        new SimulationService.Input(
                                body.population(),
                                body.members() == null ? List.of() : body.members(),
                                body.whatIf(),
                                body.daIda(),
                                body.daCda()));
        HttpStatus status =
                "QUEUED".equals(result.run().status()) ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(result);
    }
}
