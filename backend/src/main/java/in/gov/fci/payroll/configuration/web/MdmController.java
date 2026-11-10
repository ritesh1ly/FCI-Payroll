package in.gov.fci.payroll.configuration.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.configuration.orchestration.CeilingNormService;
import in.gov.fci.payroll.configuration.orchestration.CircularService;
import in.gov.fci.payroll.configuration.orchestration.LabelService;
import in.gov.fci.payroll.configuration.orchestration.NetPayFloorService;
import in.gov.fci.payroll.configuration.orchestration.OperationService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.Csv;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.workflow.api.Otp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The Module 04 master screens (docs/plan/04-masters-mdm.md): M-14 net-pay floors and supplementary
 * exclusion codes, M-15 ceiling norms, M-25 circulars, M-26 labels, M-27 operations. Each endpoint
 * has its own fixed permission gate, the service checks it again, and every change goes through
 * maker-checker: a proposal answers 202 and changes nothing in force until a checker (never the
 * maker) approves it in the inbox.
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/masters")
public class MdmController {

    private static final String PAYROLL_TYPE = "^(STAFF|DEPT_LABOUR|DPS_LABOUR|NWNP_LABOUR)$";
    private static final String LIST_CODE = "^[A-Z0-9][A-Z0-9_]{0,29}$";
    private static final String OPERATION = "^[A-Z0-9][A-Z0-9_-]{0,9}$";
    private static final String LABEL_KEY = "^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$";

    public record FloorRequest(
            @NotNull LocalDate effectiveFrom,
            @NotBlank @Pattern(regexp = "^(PERCENT_OF_GROSS|ABSOLUTE_AMOUNT|NONE)$")
                    String floorBasis,
            BigDecimal floorValue,
            BigDecimal deductionCeilingPct,
            @Size(max = 300) @PrintableAscii String sourceInstrument,
            boolean confirmed,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    public record ExclusionRequest(
            @NotNull @Size(max = 500) List<@NotNull @Positive Integer> payCodes,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    public record NormRequest(
            @NotNull LocalDate effectiveFrom,
            @Pattern(regexp = "^([A-Z0-9][A-Z0-9_]{0,29})?$") String ceilingBasis,
            @NotNull BigDecimal ceilingValue,
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,29}$") String periodicity,
            @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9_]{0,39}$") String eligibilityCategory,
            @Pattern(regexp = "^(EMPLOYEE|LABOUR|BOTH)?$") String memberScope,
            boolean confirmed,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp,
            // SRS-G-38 (US-GAP-211): the Active M-25 circular of the norm revision.
            @Positive Long circularOrderId) {}

    public record CircularRequest(
            @NotBlank @Size(max = 60) @PrintableAscii String number,
            @NotNull LocalDate date,
            @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9_]{0,39}$") String issuingAuthority,
            @NotBlank @Size(max = 500) @PrintableAscii String subject,
            @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9_]{0,39}$") String category,
            @NotNull @Size(min = 1, max = 4)
                    List<@Pattern(regexp = PAYROLL_TYPE) String> payrollTypes,
            boolean appliesAllOffices,
            @Size(max = 200) List<@NotNull @Positive Long> officeIds,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @Positive Long supersedesId,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    public record ReasonRequest(
            @NotBlank @Size(max = 500) @PrintableAscii String reason, @Valid Otp otp) {}

    public record LabelRequest(
            @NotBlank @Pattern(regexp = LABEL_KEY) String key,
            @NotBlank @Pattern(regexp = "^(UI_LABEL|HELP_TEXT|MASTER_VALUE)$") String type,
            @Size(max = 60) @PrintableAscii String masterEntity,
            @Size(max = 40) @PrintableAscii String masterCode,
            @NotBlank @Pattern(regexp = "^[A-Z]{2,10}$") String language,
            @Pattern(regexp = "^(GLOBAL|REGION|OFFICE)?$") String scope,
            @NotBlank @Size(max = 500) String text,
            boolean localisable,
            @NotNull LocalDate effectiveFrom,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    public record OperationRequest(
            @NotNull LocalDate effectiveFrom,
            @NotBlank @Size(max = 100) @PrintableAscii String name,
            boolean departmental,
            boolean dps,
            boolean nwnp,
            boolean bagBased,
            @Pattern(regexp = "^(HANDLING|HEIGHT|LEAD|PIECE_RATE|NONE)?$") String normType,
            @NotBlank @Pattern(regexp = "^(NORM_SLAB|SOR_ASOR_RATE|ALLOWANCE|TIME_BASED|NONE)$")
                    String rateNormLink,
            boolean dusting,
            @Positive Integer allowancePayCode,
            boolean presentButNoWork,
            @NotNull @Positive Long circularId,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    public record EndDateRequest(
            @NotNull LocalDate lastDay,
            @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @Valid Otp otp) {}

    private final CurrentAccess contexts;
    private final NetPayFloorService floors;
    private final CeilingNormService norms;
    private final CircularService circulars;
    private final LabelService labels;
    private final OperationService operations;
    private final ExportStreaming exports;
    private final Paging paging;

    public MdmController(
            CurrentAccess contexts,
            NetPayFloorService floors,
            CeilingNormService norms,
            CircularService circulars,
            LabelService labels,
            OperationService operations,
            ExportStreaming exports,
            Paging paging) {
        this.paging = paging;
        this.contexts = contexts;
        this.floors = floors;
        this.norms = norms;
        this.circulars = circulars;
        this.labels = labels;
        this.operations = operations;
        this.exports = exports;
    }

    // ---------------------------------------------------------------- M-14

    @GetMapping("/net-pay-floors")
    @PreAuthorize("@authz.gate('M-14','VIEW')")
    public ResponseEntity<Paged<NetPayFloorService.FloorRow>> floors(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(Paged.slice(floors.list(contexts.current()), paging.resolve(page, size)));
    }

    /** The drawn tile Min-Net Guard Triggered (I-06) (API-04-14). */
    @GetMapping("/net-pay-floors/guard-count")
    @PreAuthorize("@authz.gate('M-14','VIEW')")
    public ResponseEntity<Long> floorGuardCount() {
        return noStore(floors.guardTriggered(contexts.current()));
    }

    @GetMapping("/net-pay-floors/{type}/versions")
    @PreAuthorize("@authz.gate('M-14','VIEW')")
    public ResponseEntity<Paged<NetPayFloorService.FloorVersion>> floorHistory(
            @PathVariable @Pattern(regexp = PAYROLL_TYPE) String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(floors.history(contexts.current(), type), paging.resolve(page, size)));
    }

    @PostMapping("/net-pay-floors/{type}/versions")
    @PreAuthorize("@authz.gate('M-14','CREATE')")
    public ResponseEntity<NetPayFloorService.FloorVersion> proposeFloor(
            @PathVariable @Pattern(regexp = PAYROLL_TYPE) String type,
            @Valid @RequestBody FloorRequest r) {
        return accepted(
                floors.propose(
                        contexts.current(),
                        type,
                        new NetPayFloorService.ProposedFloor(
                                r.effectiveFrom(),
                                r.floorBasis(),
                                r.floorValue(),
                                r.deductionCeilingPct(),
                                r.sourceInstrument(),
                                r.confirmed(),
                                r.reason()),
                        Otp.proof(r.otp())));
    }

    @GetMapping("/supplementary-exclusions")
    @PreAuthorize("@authz.gate('M-14','VIEW')")
    public ResponseEntity<NetPayFloorService.Exclusions> exclusions() {
        return noStore(floors.exclusions(contexts.current()));
    }

    @PostMapping("/supplementary-exclusions")
    @PreAuthorize("@authz.gate('M-14','EDIT')")
    public ResponseEntity<NetPayFloorService.Exclusions> proposeExclusions(
            @Valid @RequestBody ExclusionRequest r) {
        return accepted(
                floors.proposeExclusions(
                        contexts.current(), r.payCodes(), r.reason(), Otp.proof(r.otp())));
    }

    // ---------------------------------------------------------------- M-15

    @GetMapping("/ceiling-norms")
    @PreAuthorize("@authz.gate('M-15','VIEW')")
    public ResponseEntity<Paged<CeilingNormService.NormRow>> norms(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(Paged.slice(norms.list(contexts.current()), paging.resolve(page, size)));
    }

    /** The drawn tiles of M-15 (API-04-15). */
    @GetMapping("/ceiling-norms/counts")
    @PreAuthorize("@authz.gate('M-15','VIEW')")
    public ResponseEntity<CeilingNormService.NormCounts> normCounts() {
        return noStore(norms.counts(contexts.current()));
    }

    @GetMapping("/ceiling-norms/{claimType}/versions")
    @PreAuthorize("@authz.gate('M-15','VIEW')")
    public ResponseEntity<Paged<CeilingNormService.NormVersion>> normHistory(
            @PathVariable @Pattern(regexp = LIST_CODE) String claimType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(
                        norms.history(contexts.current(), claimType), paging.resolve(page, size)));
    }

    @PostMapping("/ceiling-norms/{claimType}/versions")
    @PreAuthorize("@authz.gate('M-15','CREATE')")
    public ResponseEntity<CeilingNormService.NormVersion> proposeNorm(
            @PathVariable @Pattern(regexp = LIST_CODE) String claimType,
            @Valid @RequestBody NormRequest r) {
        return accepted(
                norms.propose(
                        contexts.current(),
                        claimType,
                        new CeilingNormService.ProposedNorm(
                                r.effectiveFrom(),
                                r.ceilingBasis(),
                                r.ceilingValue(),
                                r.periodicity(),
                                r.eligibilityCategory(),
                                r.memberScope(),
                                r.confirmed(),
                                r.reason(),
                                r.circularOrderId()),
                        Otp.proof(r.otp())));
    }

    // ---------------------------------------------------------------- M-25

    @GetMapping("/circulars")
    @PreAuthorize("@authz.gate('M-25','VIEW')")
    public ResponseEntity<Paged<CircularService.CircularView>> circulars(
            @RequestParam(required = false) @Size(max = 64) String text,
            @RequestParam(required = false) @Pattern(regexp = LIST_CODE) String category,
            @RequestParam(required = false)
                    @Pattern(regexp = "^(PENDING_APPROVAL|ACTIVE|SUPERSEDED|WITHDRAWN|REJECTED)$")
                    String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(
                        circulars.search(
                                contexts.current(),
                                new CircularService.SearchQuery(text, category, status, from, to)),
                        paging.resolve(page, size)));
    }

    /** SRS M-25 action Export: the register as filtered (M-25 EXPORT). */
    @GetMapping("/circulars/export")
    @PreAuthorize("@authz.gate('M-25','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportCirculars(
            @RequestParam(required = false) @Size(max = 64) String text,
            @RequestParam(required = false) @Pattern(regexp = LIST_CODE) String category,
            @RequestParam(required = false)
                    @Pattern(regexp = "^(PENDING_APPROVAL|ACTIVE|SUPERSEDED|WITHDRAWN|REJECTED)$")
                    String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        CircularService.SearchQuery query =
                new CircularService.SearchQuery(text, category, status, from, to);
        exports.checkSize(circulars.exportSize(context, query));
        return exports.stream("circulars", format, sink -> circulars.export(context, query, sink));
    }

    @GetMapping("/circulars/current")
    @PreAuthorize("@authz.gate('M-25','VIEW')")
    public ResponseEntity<List<CircularService.CircularView>> currentCirculars() {
        return noStore(circulars.current(contexts.current()));
    }

    @GetMapping("/circulars/offices")
    @PreAuthorize("@authz.gate('M-25','CREATE')")
    public ResponseEntity<List<CircularService.OfficeOption>> circularOffices() {
        return noStore(circulars.offices(contexts.current()));
    }

    @GetMapping("/circulars/{id}")
    @PreAuthorize("@authz.gate('M-25','VIEW')")
    public ResponseEntity<CircularService.CircularView> circular(@PathVariable @Positive Long id) {
        return noStore(circulars.get(contexts.current(), id));
    }

    @GetMapping("/circulars/{id}/where-used")
    @PreAuthorize("@authz.gate('M-25','VIEW')")
    public ResponseEntity<Paged<CircularService.UseView>> whereUsed(
            @PathVariable @Positive Long id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(
                        circulars.whereUsed(contexts.current(), id), paging.resolve(page, size)));
    }

    @PostMapping(value = "/circulars", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('M-25','CREATE')")
    public ResponseEntity<CircularService.CircularView> recordCircular(
            @Valid @RequestPart("circular") CircularRequest r,
            @RequestPart("file") MultipartFile file) {
        return accepted(
                circulars.record(
                        contexts.current(),
                        new CircularService.NewCircular(
                                r.number(),
                                r.date(),
                                r.issuingAuthority(),
                                r.subject(),
                                r.category(),
                                r.payrollTypes(),
                                r.appliesAllOffices(),
                                r.officeIds(),
                                r.effectiveFrom(),
                                r.effectiveTo(),
                                r.supersedesId(),
                                r.reason()),
                        new CircularService.OrderCopy(file.getOriginalFilename(), bytes(file)),
                        Otp.proof(r.otp())));
    }

    @PostMapping("/circulars/{id}/withdraw")
    @PreAuthorize("@authz.gate('M-25','EDIT')")
    public ResponseEntity<CircularService.CircularView> withdrawCircular(
            @PathVariable @Positive Long id, @Valid @RequestBody ReasonRequest r) {
        return accepted(circulars.withdraw(contexts.current(), id, r.reason(), Otp.proof(r.otp())));
    }

    /** The order copy; served as an attachment so the browser never renders it inline. */
    @GetMapping("/circulars/{id}/document")
    @PreAuthorize("@authz.gate('M-25','VIEW')")
    public ResponseEntity<byte[]> circularDocument(
            @PathVariable @Positive Long id, HttpServletRequest request) {
        DocumentStore.Content content =
                circulars.document(contexts.current(), id, request.getRemoteAddr());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(content.attachment().fileName())
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(content.attachment().contentType()))
                .body(content.bytes());
    }

    // ---------------------------------------------------------------- M-26

    @GetMapping("/labels")
    @PreAuthorize("@authz.gate('M-26','VIEW')")
    public ResponseEntity<Paged<LabelService.LabelView>> labels(
            @RequestParam(required = false) @Size(max = 64) String text,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z]{2,10}$") String language,
            @RequestParam(required = false) @Pattern(regexp = "^(GLOBAL|REGION|OFFICE)$")
                    String scope,
            @RequestParam(required = false)
                    @Pattern(regexp = "^(PENDING_APPROVAL|ACTIVE|SUPERSEDED|WITHDRAWN|REJECTED)$")
                    String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(
                        labels.search(
                                contexts.current(),
                                new LabelService.LabelQuery(text, language, scope, status)),
                        paging.resolve(page, size)));
    }

    @GetMapping("/labels/missing-hindi")
    @PreAuthorize("@authz.gate('M-26','VIEW')")
    public ResponseEntity<Paged<LabelService.LabelView>> missingHindi(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(labels.missingHindi(contexts.current()), paging.resolve(page, size)));
    }

    @GetMapping("/labels/resolve")
    @PreAuthorize("@authz.gate('M-26','VIEW')")
    public ResponseEntity<LabelService.Resolved> resolveLabel(
            @RequestParam @Pattern(regexp = LABEL_KEY) String key,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z]{2,10}$") String language) {
        return noStore(labels.resolve(contexts.current(), key, language));
    }

    @PostMapping("/labels")
    @PreAuthorize("@authz.gate('M-26','CREATE')")
    public ResponseEntity<LabelService.LabelView> proposeLabel(@Valid @RequestBody LabelRequest r) {
        return accepted(
                labels.propose(
                        contexts.current(),
                        new LabelService.ProposedLabel(
                                r.key(),
                                r.type(),
                                r.masterEntity(),
                                r.masterCode(),
                                r.language(),
                                r.scope(),
                                r.text(),
                                r.localisable(),
                                r.effectiveFrom(),
                                r.reason()),
                        Otp.proof(r.otp())));
    }

    @PostMapping("/labels/{id}/withdraw")
    @PreAuthorize("@authz.gate('M-26','EDIT')")
    public ResponseEntity<LabelService.LabelView> withdrawLabel(
            @PathVariable @Positive Long id, @Valid @RequestBody ReasonRequest r) {
        return accepted(labels.withdraw(contexts.current(), id, r.reason(), Otp.proof(r.otp())));
    }

    @GetMapping("/labels/export")
    @PreAuthorize("@authz.gate('M-26','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportLabels(
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z]{2,10}$") String language,
            @RequestParam(required = false) @Pattern(regexp = "^(GLOBAL|REGION|OFFICE)$")
                    String scope,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(labels.exportSize(context, language, scope));
        return exports.stream(
                "labels", format, sink -> labels.export(context, language, scope, sink));
    }

    /**
     * SRS M-26 bulk upload: a CSV in the download's columns (UTF-8); every row is checked and
     * submitted like a single change, all or none. SRS M-26 actions "Bulk upload (Administrator)"
     * (UI_BMAD_g1 #48): the administrator's M-26 EDIT right, not the office maker's CREATE.
     */
    @PostMapping(value = "/labels/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.gate('M-26','EDIT')")
    public ResponseEntity<List<LabelService.LabelView>> uploadLabels(
            @RequestParam @NotBlank @Size(max = 500) @PrintableAscii String reason,
            @RequestPart("file") MultipartFile file) {
        return accepted(labels.upload(contexts.current(), labelRows(bytes(file)), reason, null));
    }

    /**
     * The rows of an uploaded label file: a header with at least label_key, label_type, language,
     * label_text and effective_from (the download's names); a cell a spreadsheet guard prefixed
     * with an apostrophe is read without it.
     */
    static List<LabelService.ProposedLabel> labelRows(byte[] content) {
        String text;
        try {
            text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(content))
                            .toString();
        } catch (CharacterCodingException ex) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<List<String>> rows = Csv.parse(text);
        if (rows.isEmpty()) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        List<String> head = rows.get(0).stream().map(String::strip).toList();
        int key = head.indexOf("label_key");
        int type = head.indexOf("label_type");
        int entity = head.indexOf("master_entity");
        int code = head.indexOf("master_code");
        int language = head.indexOf("language");
        int scope = head.indexOf("scope");
        int labelText = head.indexOf("label_text");
        int localisable = head.indexOf("localisable");
        int from = head.indexOf("effective_from");
        if (key < 0 || type < 0 || language < 0 || labelText < 0 || from < 0) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        List<LabelService.ProposedLabel> out = new ArrayList<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            out.add(
                    new LabelService.ProposedLabel(
                            cell(row, key),
                            cell(row, type),
                            cell(row, entity),
                            cell(row, code),
                            cell(row, language),
                            cell(row, scope),
                            cell(row, labelText),
                            "Y".equals(cell(row, localisable)),
                            date(cell(row, from)),
                            null));
        }
        return out;
    }

    private static LocalDate date(String value) {
        if (value == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private static String cell(List<String> row, int at) {
        if (at < 0 || at >= row.size()) {
            return null;
        }
        String v = row.get(at);
        v = v.startsWith("'") ? v.substring(1) : v;
        return v.isBlank() ? null : v.strip();
    }

    // ---------------------------------------------------------------- M-27

    @GetMapping("/operations")
    @PreAuthorize("@authz.gate('M-27','VIEW')")
    public ResponseEntity<Paged<OperationService.OperationRow>> operations(
            // SRS-G-39: a search by code, name or any label; the names in the user's language.
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Pattern(regexp = "^(en|hi|EN|HI)$") String lang,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(
                        operations.list(contexts.current(), q, lang), paging.resolve(page, size)));
    }

    /** SRS M-27 action Export: the master as filtered (M-27 EXPORT). */
    @GetMapping("/operations/export")
    @PreAuthorize("@authz.gate('M-27','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportOperations(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Pattern(regexp = "^(en|hi|EN|HI)$") String lang,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(operations.exportSize(context, q, lang));
        return exports.stream(
                "operations", format, sink -> operations.export(context, q, lang, sink));
    }

    @GetMapping("/operations/{code}/versions")
    @PreAuthorize("@authz.gate('M-27','VIEW')")
    public ResponseEntity<Paged<OperationService.OperationVersion>> operationHistory(
            @PathVariable @Pattern(regexp = OPERATION) String code,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(
                        operations.history(contexts.current(), code), paging.resolve(page, size)));
    }

    @PostMapping("/operations/{code}/versions")
    @PreAuthorize("@authz.gate('M-27','CREATE')")
    public ResponseEntity<OperationService.OperationVersion> proposeOperation(
            @PathVariable @Pattern(regexp = OPERATION) String code,
            @Valid @RequestBody OperationRequest r) {
        return accepted(
                operations.propose(
                        contexts.current(),
                        code,
                        new OperationService.ProposedOperation(
                                r.effectiveFrom(),
                                r.name(),
                                r.departmental(),
                                r.dps(),
                                r.nwnp(),
                                r.bagBased(),
                                r.normType(),
                                r.rateNormLink(),
                                r.dusting(),
                                r.allowancePayCode(),
                                r.presentButNoWork(),
                                r.circularId(),
                                r.reason()),
                        Otp.proof(r.otp())));
    }

    @PostMapping("/operations/{code}/end-date")
    @PreAuthorize("@authz.gate('M-27','EDIT')")
    public ResponseEntity<OperationService.OperationRow> endDateOperation(
            @PathVariable @Pattern(regexp = OPERATION) String code,
            @Valid @RequestBody EndDateRequest r) {
        return accepted(
                operations.endDate(
                        contexts.current(), code, r.lastDay(), r.reason(), Otp.proof(r.otp())));
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static <T> ResponseEntity<T> accepted(T body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
