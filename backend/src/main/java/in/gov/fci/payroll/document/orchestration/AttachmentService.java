package in.gov.fci.payroll.document.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.document.business.AttachmentRules;
import in.gov.fci.payroll.document.business.DocumentErrorCode;
import in.gov.fci.payroll.document.business.DocumentStorage;
import in.gov.fci.payroll.document.business.MalwareScanner;
import in.gov.fci.payroll.document.business.StorageLayout;
import in.gov.fci.payroll.document.business.model.DocumentAttachment;
import in.gov.fci.payroll.document.business.model.DocumentType;
import in.gov.fci.payroll.document.config.DocumentProperties;
import in.gov.fci.payroll.document.persistence.DocumentAccessLog;
import in.gov.fci.payroll.document.persistence.DocumentRepositories;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.cache.VersionedCache;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The order control's attachment half (SRS-G-30, SRS-G-31, SRS-DM-13; KB OC-3…OC-8, OC-13, OC-14).
 *
 * <p>Order of an upload, all in one transaction so a failure leaves nothing behind:
 *
 * <ol>
 *   <li>file checks: not empty, within the size limit, PDF / JPG / PNG by content (CF-13 limits);
 *   <li>the record has room for another file (file-count limit);
 *   <li>order particulars: type, date, issuing authority, reference where the type requires it;
 *   <li>virus scan, fail closed: no verdict means the file is not stored;
 *   <li>metadata row (with SHA-256), then the bytes, then one audit entry.
 * </ol>
 *
 * Authorisation is the owning module's: see {@link DocumentStore}.
 */
@Service
@Loggable
public class AttachmentService implements DocumentStore {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private final DocumentRepositories.Attachments attachments;
    private final DocumentRepositories.Types types;
    private final DocumentStorage storage;
    private final StorageLayout layout;
    private final MalwareScanner scanner;
    private final DocumentAccessLog accessLog;
    private final DocumentProperties properties;
    private final AuditService audit;
    private final ApiProperties api;
    private final Clock clock;

    /**
     * The document types (seed data, no runtime writer): cached with Caffeine and reloaded at the
     * periodic {@code fci.cache.max-age}, not read once per attachment shown (deep profile
     * 2026-10-27).
     */
    static final String TYPE_REGION = "DOCUMENT_TYPE";

    private final VersionedCache<String, List<DocumentType>> typeCache;

    public AttachmentService(
            DocumentRepositories.Attachments attachments,
            DocumentRepositories.Types types,
            DocumentStorage storage,
            StorageLayout layout,
            MalwareScanner scanner,
            DocumentAccessLog accessLog,
            DocumentProperties properties,
            AuditService audit,
            ApiProperties api,
            Clock clock,
            CacheRegions regions) {
        this.attachments = attachments;
        this.types = types;
        this.typeCache = VersionedCache.of(TYPE_REGION, regions, 1);
        this.storage = storage;
        this.layout = layout;
        this.scanner = scanner;
        this.accessLog = accessLog;
        this.properties = properties;
        this.audit = audit;
        this.api = api;
        this.clock = clock;
    }

    @Override
    @Transactional
    public AttachmentView attach(String actor, NewAttachment request) {
        AttachmentRules.requireRoomForAnother(
                countActive(request.entityName(), request.entityKey()),
                properties.maxFilesPerRecord());
        DocumentAttachment saved = store(actor, request);
        audit.record(
                actor,
                AuditService.Entry.change(
                                request.entityName(),
                                request.entityKey(),
                                "attachment",
                                null,
                                describe(saved),
                                "Document attached")
                        .module("DOC"));
        return view(saved);
    }

    @Override
    @Transactional
    public AttachmentView storeGenerated(
            String actor, String entityName, String entityKey, String fileName, byte[] content) {
        if (content == null || content.length < 4) {
            throw new FciException(DocumentErrorCode.EMPTY_FILE);
        }
        // A ZIP starts with a local file header (PK 03 04), or, when it holds no entry, with the
        // end of its central directory (PK 05 06): an office without documents is an empty ZIP.
        boolean zip =
                content[0] == 'P'
                        && content[1] == 'K'
                        && ((content[2] == 3 && content[3] == 4)
                                || (content[2] == 5 && content[3] == 6));
        String contentType =
                zip
                        ? "application/zip"
                        : AttachmentRules.detectContentType(content)
                                .filter("application/pdf"::equals)
                                .orElseThrow(() -> new FciException(DocumentErrorCode.FILE_TYPE));
        DocumentAttachment attachment =
                DocumentAttachment.record(
                        entityName,
                        entityKey,
                        null,
                        null,
                        null,
                        null,
                        AttachmentRules.ascii(fileName, 255),
                        contentType,
                        content.length,
                        AttachmentRules.sha256(content),
                        true,
                        actor,
                        clock.instant());
        attachment.storedAt(
                storage.provider(),
                storage.bucket(),
                layout.keyOf(entityName, clock.instant(), UUID.randomUUID()));
        DocumentAttachment saved = attachments.saveAndFlush(attachment);
        storage.write(saved, content);
        audit.record(
                actor,
                AuditService.Entry.change(
                                entityName,
                                entityKey,
                                "attachment",
                                null,
                                saved.getFileName(),
                                "System output stored")
                        .module("DOC"));
        return view(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttachmentView> active(String entityName, String entityKey) {
        return attachments
                .findByEntityNameAndEntityKeyAndStatusOrderByIdAsc(
                        entityName, entityKey, DocumentAttachment.ACTIVE)
                .stream()
                .map(this::view)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<String, List<AttachmentView>> activeByKey(
            String entityName, java.util.Collection<String> entityKeys) {
        java.util.Map<String, List<AttachmentView>> out = new java.util.LinkedHashMap<>();
        List<String> keys = entityKeys.stream().distinct().toList();
        // Oracle refuses more than 1,000 values in one IN list.
        for (int i = 0; i < keys.size(); i += 1000) {
            for (DocumentAttachment a :
                    attachments.findByEntityNameAndEntityKeyInAndStatusOrderByIdAsc(
                            entityName,
                            keys.subList(i, Math.min(keys.size(), i + 1000)),
                            DocumentAttachment.ACTIVE)) {
                out.computeIfAbsent(a.getEntityKey(), k -> new java.util.ArrayList<>())
                        .add(view(a));
            }
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public int countActive(String entityName, String entityKey) {
        return attachments.countByEntityNameAndEntityKeyAndStatus(
                entityName, entityKey, DocumentAttachment.ACTIVE);
    }

    @Override
    @Transactional
    public Content read(
            String actor, String sourceIp, String entityName, String entityKey, Long attachmentId) {
        DocumentAttachment attachment = owned(entityName, entityKey, attachmentId);
        // OC-7: every view or download is logged, superseded and cancelled files included.
        accessLog.record(
                attachment.getId(), actor, DocumentAccessLog.AccessType.DOWNLOAD, sourceIp);
        return new Content(view(attachment), storage.read(attachment));
    }

    @Override
    @Transactional
    public AttachmentView replace(
            String actor,
            String entityName,
            String entityKey,
            Long attachmentId,
            NewAttachment replacement) {
        DocumentAttachment old = owned(entityName, entityKey, attachmentId);
        if (!old.isActive()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        // Replacing frees the old slot, so no file-count check here; the old row stays SUPERSEDED.
        DocumentAttachment successor = store(actor, replacement);
        old.supersede(successor.getId());
        attachments.save(old);
        audit.record(
                actor,
                AuditService.Entry.change(
                                entityName,
                                entityKey,
                                "attachment",
                                describe(old),
                                describe(successor),
                                "Document replaced before submission")
                        .module("DOC"));
        return view(successor);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TypeView> types() {
        return allTypes().stream()
                .map(t -> new TypeView(t.getCode(), t.getName(), t.requiresReferenceNumber()))
                .toList();
    }

    @Override
    @Transactional
    public void cancel(
            String actor, String entityName, String entityKey, Long attachmentId, String reason) {
        DocumentAttachment attachment = owned(entityName, entityKey, attachmentId);
        if (!attachment.isActive()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        attachment.cancel();
        attachments.save(attachment);
        audit.record(
                actor,
                AuditService.Entry.change(
                                entityName,
                                entityKey,
                                "attachment_status",
                                describe(attachment),
                                DocumentAttachment.CANCELLED,
                                reason)
                        .module("DOC"));
    }

    @Override
    @Transactional
    public void cancelAll(String actor, String entityName, String entityKey, String reason) {
        for (DocumentAttachment attachment :
                attachments.findByEntityNameAndEntityKeyAndStatusOrderByIdAsc(
                        entityName, entityKey, DocumentAttachment.ACTIVE)) {
            attachment.cancel();
            attachments.save(attachment);
            audit.record(
                    actor,
                    AuditService.Entry.change(
                                    entityName,
                                    entityKey,
                                    "attachment_status",
                                    DocumentAttachment.ACTIVE,
                                    DocumentAttachment.CANCELLED,
                                    reason)
                            .module("DOC"));
        }
    }

    @Override
    @Transactional
    public void adopt(
            String actor,
            String entityName,
            String provisionalKey,
            Long attachmentId,
            String entityKey) {
        if (provisionalKey == null || !provisionalKey.startsWith(PROVISIONAL)) {
            throw new IllegalArgumentException("Not a provisional key");
        }
        DocumentAttachment attachment =
                attachments
                        .findById(attachmentId)
                        .filter(a -> a.belongsTo(entityName, provisionalKey))
                        .orElseThrow(
                                () -> new IllegalArgumentException("No such provisional file"));
        attachment.adoptedBy(entityKey, PROVISIONAL);
        attachments.save(attachment);
        audit.record(
                actor,
                AuditService.Entry.change(
                                entityName,
                                entityKey,
                                "attachment_owner",
                                provisionalKey,
                                entityKey,
                                "Order stored before its record")
                        .module("DOC"));
    }

    // ---------------------------------------------------------------- internals

    /** Validates, scans and stores one file; returns the saved ACTIVE row. */
    private DocumentAttachment store(String actor, NewAttachment request) {
        // An order (typed) must be PDF / JPG / PNG; a system-captured source file (no type, e.g. an
        // X-07 HRMS export) must be plain text.
        String contentType =
                request.documentTypeCode() == null
                        ? AttachmentRules.requireTextSourceFile(
                                request.content(), properties.maxFileBytes())
                        : AttachmentRules.requireAcceptedFile(
                                request.content(), properties.maxFileBytes());
        DocumentType type =
                request.documentTypeCode() == null
                        ? null
                        : allTypes().stream()
                                .filter(t -> t.getCode().equals(request.documentTypeCode()))
                                .findFirst()
                                .orElseThrow(
                                        () -> new FciException(DocumentErrorCode.UNKNOWN_TYPE));
        String reference = AttachmentRules.ascii(request.referenceNumber(), 60);
        String authority = AttachmentRules.ascii(request.issuingAuthority(), 200);
        AttachmentRules.requireOrderParticulars(
                type, reference, request.orderDate(), authority, today());
        scan(request.content());
        DocumentAttachment attachment =
                DocumentAttachment.record(
                        request.entityName(),
                        request.entityKey(),
                        type == null ? null : type.getId(),
                        reference,
                        request.orderDate(),
                        authority,
                        AttachmentRules.safeFileName(request.fileName(), contentType),
                        contentType,
                        request.content().length,
                        AttachmentRules.sha256(request.content()),
                        request.containsPii(),
                        actor,
                        clock.instant());
        // The configured folder plus a random id: unique per (provider, bucket) as the DDL
        // requires, independent of the row id, so the same key serves object storage later.
        attachment.storedAt(
                storage.provider(),
                storage.bucket(),
                layout.keyOf(request.entityName(), clock.instant(), UUID.randomUUID()));
        DocumentAttachment saved =
                attachments.saveAndFlush(attachment); // flush: the bytes need the id
        storage.write(saved, request.content());
        return saved;
    }

    private void scan(byte[] content) {
        MalwareScanner.Verdict verdict;
        try {
            verdict = scanner.scan(content);
        } catch (MalwareScanner.ScanUnavailableException ex) {
            log.warn("event=DOCUMENT_SCAN_UNAVAILABLE reason={}", ex.getMessage());
            throw new FciException(DocumentErrorCode.SCAN_UNAVAILABLE, ex);
        }
        if (verdict == MalwareScanner.Verdict.INFECTED) {
            log.warn("event=DOCUMENT_INFECTED_REFUSED size={}", content.length);
            throw new FciException(DocumentErrorCode.INFECTED);
        }
    }

    /**
     * The attachment if it belongs to the named record; otherwise "not found" (not "forbidden"), so
     * ids of other records cannot be probed through a record the caller may see.
     */
    private DocumentAttachment owned(String entityName, String entityKey, Long attachmentId) {
        return attachments
                .findById(attachmentId)
                .filter(a -> a.belongsTo(entityName, entityKey))
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    /**
     * Today in the business time zone (order dates are Indian dates, whatever the server's zone).
     */
    private LocalDate today() {
        return LocalDate.now(clock.withZone(api.businessZone()));
    }

    /** Audit value: id, type and checksum; never the file name, which may carry personal data. */
    private static String describe(DocumentAttachment a) {
        return "attachment "
                + a.getId()
                + " "
                + a.getContentType()
                + " sha256:"
                + a.getChecksumSha256().substring(0, 16);
    }

    private List<DocumentType> allTypes() {
        return typeCache.get(TYPE_REGION, k -> List.copyOf(types.findAllByOrderByCode()));
    }

    private AttachmentView view(DocumentAttachment a) {
        DocumentType type =
                a.getDocumentTypeId() == null
                        ? null
                        : allTypes().stream()
                                .filter(t -> t.getId().equals(a.getDocumentTypeId()))
                                .findFirst()
                                .orElse(null);
        return new AttachmentView(
                a.getId(),
                a.getEntityName(),
                a.getEntityKey(),
                type == null ? null : type.getCode(),
                type == null ? null : type.getName(),
                a.getReferenceNumber(),
                a.getOrderDate(),
                a.getIssuingAuthority(),
                a.getFileName(),
                a.getContentType(),
                a.getFileSizeBytes(),
                a.getChecksumSha256(),
                a.getStatus(),
                a.getUploadedBy(),
                a.getUploadedAt());
    }
}
