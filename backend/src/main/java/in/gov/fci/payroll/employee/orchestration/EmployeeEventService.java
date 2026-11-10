package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.business.EventRules;
import in.gov.fci.payroll.employee.business.model.Employee;
import in.gov.fci.payroll.employee.business.model.EmployeeTransfer;
import in.gov.fci.payroll.employee.business.model.LifecycleEvent;
import in.gov.fci.payroll.employee.business.model.RecordStatus;
import in.gov.fci.payroll.employee.persistence.EmployeeRepository;
import in.gov.fci.payroll.employee.persistence.EventRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * I-17 Employee Life-Cycle Events and M-11 Staff Transfer, on {@code employee_lifecycle_event} and
 * {@code employee_transfer} (target DDL v4.0 S03). A life-cycle event is never keyed in Payroll
 * (SRS-1-63; I-17 l.3871-3875, read-only): it arrives from HRMS through an I-21 task or from the
 * X-07 fallback upload (SRS-1-72), and I-17 only shows it. Events keyed here before (event_source
 * PAYROLL) are kept as they are and a pending one is still decided ({@link EmployeeEventEffects};
 * OD-74). A transfer is recorded here by the Establishment / HR maker and approved by a Payroll
 * Checker of the employee's unit (workflow action M11_CREATE); nothing changes until the approval.
 * Every read and write is limited to employees in the session's data scope.
 */
@Service
@Loggable
public class EmployeeEventService {

    public static final String EVENT_ENTITY = "employee_lifecycle_event";
    public static final String TRANSFER_ENTITY = "employee_transfer";

    public record EventView(
            Long id,
            String eis,
            String employeeName,
            String eventType,
            LocalDate eventDate,
            LocalDate endDate,
            String reason,
            BigDecimal subsistencePercent,
            String releaseFromYymm,
            String releaseToYymm,
            String orderReference,
            String source,
            String createdBy,
            String status,
            Long approvalId,
            // SRS-1-63 / I-17 l.3879, l.3882: the I-21 task that applied it, the HRMS Request ID
            // and the intervening-period treatment of a reinstatement, all as received.
            Long hrmsChangeTaskId,
            String hrmsRequestId,
            String interveningPeriod) {}

    public record TransferView(
            Long id,
            String eis,
            String employeeName,
            String fromLocation,
            String toLocation,
            LocalDate transferDate,
            LocalDate relievingDate,
            LocalDate joiningDate,
            String joiningTime,
            String orderReference,
            String lpcReference,
            boolean splitWage,
            Integer fromUnitDays,
            Integer toUnitDays,
            String reason,
            String status,
            Long approvalId) {}

    public record NewTransfer(
            String toLocationCode,
            LocalDate transferDate,
            LocalDate relievingDate,
            LocalDate joiningDate,
            String joiningTime,
            String orderReference,
            String lpcReference,
            String newStaffNumber,
            boolean splitWage,
            Integer fromUnitDays,
            Integer toUnitDays,
            String reason,
            boolean orderHeldInHrms,
            Long orderAttachmentId,
            String orderProvisionalKey) {

        /** As before US-GAP-26: no order copy (tests and callers that send it later). */
        public NewTransfer(
                String toLocationCode,
                LocalDate transferDate,
                LocalDate relievingDate,
                LocalDate joiningDate,
                String joiningTime,
                String orderReference,
                String lpcReference,
                String newStaffNumber,
                boolean splitWage,
                Integer fromUnitDays,
                Integer toUnitDays,
                String reason) {
            this(
                    toLocationCode,
                    transferDate,
                    relievingDate,
                    joiningDate,
                    joiningTime,
                    orderReference,
                    lpcReference,
                    newStaffNumber,
                    splitWage,
                    fromUnitDays,
                    toUnitDays,
                    reason,
                    false,
                    null,
                    null);
        }
    }

    /** A transfer order copy stored before its transfer (SRS-G-31): its id and provisional key. */
    public record StoredOrder(long attachmentId, String provisionalKey) {}

    /** The order copy's particulars and file (SRS-G-30; M-11 field "Transfer Order"). */
    public record OrderCopy(
            String documentType,
            String reference,
            LocalDate orderDate,
            String issuingAuthority,
            String fileName,
            byte[] content) {}

    public record Destination(String code, String label, String type) {}

    private final EmployeeRepository employees;
    private final EventRepositories.LifecycleEvents events;
    private final EventRepositories.Transfers transfers;
    private final ApprovalStatuses approvalStatuses;
    private final MakerChecker approvals;
    private final Authorization policy;
    private final CurrentAccess contexts;
    private final OrgDirectory org;
    private final Clock clock;
    private final DocumentStore documents;
    private final AuditService audit;
    private final TransferOrigin origin;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public EmployeeEventService(
            EmployeeRepository employees,
            EventRepositories.LifecycleEvents events,
            EventRepositories.Transfers transfers,
            ApprovalStatuses approvalStatuses,
            MakerChecker approvals,
            Authorization policy,
            CurrentAccess contexts,
            OrgDirectory org,
            Clock clock,
            DocumentStore documents,
            AuditService audit,
            TransferOrigin origin) {
        this.documents = documents;
        this.audit = audit;
        this.origin = origin;
        this.employees = employees;
        this.events = events;
        this.transfers = transfers;
        this.approvalStatuses = approvalStatuses;
        this.approvals = approvals;
        this.policy = policy;
        this.contexts = contexts;
        this.org = org;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- I-17

    /** The latest events of employees in the session's scope. */
    @Transactional(readOnly = true)
    public Paged<EventView> recentEvents(AccessContext context, PageSpec spec) {
        policy.require(context, "I-17", "VIEW");
        Page<LifecycleEvent> rows = events.findRecentInScope(scope(context), spec.pageable());
        return new Paged<>(
                eventViews(rows.getContent()),
                rows.getNumber(),
                rows.getSize(),
                rows.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<EventView> events(AccessContext context, String eis) {
        Employee employee = employee(eis);
        policy.requireEmployeeRecord(
                context, "I-17", "VIEW", employee.getId(), employee.getLocationId());
        return eventViews(events.findByEmployeeIdOrderByEventDateDescEventIdDesc(employee.getId()));
    }

    // ---------------------------------------------------------------- M-11

    @Transactional(readOnly = true)
    public Paged<TransferView> recentTransfers(AccessContext context, PageSpec spec) {
        policy.require(context, "M-11", "VIEW");
        Page<EmployeeTransfer> rows = transfers.findRecentInScope(scope(context), spec.pageable());
        return new Paged<>(
                transferViews(rows.getContent()),
                rows.getNumber(),
                rows.getSize(),
                rows.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<TransferView> transfers(AccessContext context, String eis) {
        Employee employee = employee(eis);
        policy.requireEmployeeRecord(
                context, "M-11", "VIEW", employee.getId(), employee.getLocationId());
        return transferViews(transfers.findByEmployeeIdOrderByTransferDateDesc(employee.getId()));
    }

    /** Every office a transfer may go to (inter-zone / inter-region transfers included). */
    @Transactional(readOnly = true)
    public List<Destination> destinations(AccessContext context) {
        policy.require(context, "M-11", "CREATE");
        LocalDate today = contexts.today();
        return org.locations(org.all()).stream()
                // M-11: the destination must be an active organisational unit.
                .filter(l -> l.isActiveOn(today))
                .sorted(java.util.Comparator.comparing(OrgLocation::getCode))
                .map(l -> new Destination(l.getCode(), l.displayLabel(), l.getType().name()))
                .toList();
    }

    /** Records a transfer for approval by a Payroll Checker of the releasing unit. */
    @Transactional
    public TransferView recordTransfer(
            AccessContext context, String eis, NewTransfer request, OtpProof otp) {
        Employee employee = employee(eis);
        policy.requireEmployeeRecord(
                context, "M-11", "CREATE", employee.getId(), employee.getLocationId());
        OrgLocation to =
                request.toLocationCode() == null
                        ? null
                        : org.byCode(request.toLocationCode()).orElse(null);
        if (to == null || to.getId().equals(employee.getLocationId())) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        // M-11 field "Destination Region": an active unit of the location hierarchy (US-GAP-27).
        if (!to.isActiveOn(contexts.today())) {
            throw new FciException(ApiErrorCode.M11_DESTINATION);
        }
        EventRules.validateTransfer(
                request.transferDate(),
                request.relievingDate(),
                request.joiningDate(),
                request.joiningTime(),
                request.splitWage(),
                request.fromUnitDays(),
                request.toUnitDays(),
                contexts.today());
        // UC M-11 main flow (SRS l.1321): no computation or work-slip at the origin after the date.
        origin.requireClear(employee.getId(), request.transferDate());
        if (employee.getRecordStatus() == RecordStatus.CHANGE_PENDING
                || hasPendingTransfer(employee.getId())) {
            throw new FciException(ApiErrorCode.ALREADY_PENDING);
        }
        if (EventRules.SEPARATED_STATUSES.contains(employee.getEmploymentStatus())) {
            throw new FciException(
                    ApiErrorCode.EMPLOYEE_EVENT_NOT_ALLOWED,
                    new Object[] {employee.getEmploymentStatus()},
                    List.of(),
                    null);
        }
        String newNumber = blank(request.newStaffNumber());
        if (newNumber != null) {
            if (!newNumber.matches("^[A-Za-z0-9-]{3,20}$")) {
                throw new FciException(CommonErrorCode.VALIDATION_FAILED);
            }
            if (employees
                    .findByEmpNum(newNumber)
                    .filter(e -> !e.getId().equals(employee.getId()))
                    .isPresent()) {
                throw new FciException(ApiErrorCode.EMPLOYEE_NUMBER_TAKEN);
            }
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new FciException(ApiErrorCode.REASON_REQUIRED);
        }
        DocumentStore.AttachmentView copy = transferOrder(context, eis, request);
        Long fromZone =
                org.location(employee.getLocationId()).map(OrgLocation::getZoneId).orElse(null);
        EmployeeTransfer transfer =
                transfers.save(
                        EmployeeTransfer.record(
                                employee.getId(),
                                employee.getLocationId(),
                                to.getId(),
                                fromZone,
                                to.getZoneId(),
                                request.transferDate(),
                                request.relievingDate(),
                                request.joiningDate(),
                                blank(request.joiningTime()),
                                request.reason().strip(),
                                blank(request.lpcReference()),
                                blank(request.orderReference()),
                                request.splitWage(),
                                request.fromUnitDays(),
                                request.toUnitDays()));
        if (copy != null) {
            documents.adopt(
                    context.loginId(),
                    TRANSFER_ENTITY,
                    request.orderProvisionalKey(),
                    copy.id(),
                    String.valueOf(transfer.getTransferId()));
        }
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                "M-11",
                                "CREATE",
                                TRANSFER_ENTITY,
                                String.valueOf(transfer.getTransferId()),
                                ActionType.CREATE,
                                employee.getLocationId(),
                                "{\"employeeId\":"
                                        + employee.getId()
                                        + ",\"transferId\":"
                                        + transfer.getTransferId()
                                        + ",\"to\":\""
                                        + to.getCode()
                                        + "\""
                                        + (newNumber == null
                                                ? ""
                                                : ",\"newStaffNumber\":\"" + newNumber + "\"")
                                        + "}",
                                request.transferDate(),
                                request.reason()),
                        otp);
        transfer.attachApproval(item.id());
        transfers.save(transfer);
        return transferViews(List.of(transfer)).get(0);
    }

    /**
     * SRS-16-18 / SRS-G-31 (US-GAP-26): the Maker stores the order copy first (its particulars:
     * type, order / reference number, order date, issuing authority), then records the transfer
     * naming it; an OTP retry names the same copy.
     */
    @Transactional
    public StoredOrder storeTransferOrder(AccessContext context, String eis, OrderCopy copy) {
        Employee employee = employee(eis);
        policy.requireEmployeeRecord(
                context, "M-11", "CREATE", employee.getId(), employee.getLocationId());
        String provisional = DocumentStore.PROVISIONAL + java.util.UUID.randomUUID();
        DocumentStore.AttachmentView stored =
                documents.attach(
                        context.loginId(),
                        new DocumentStore.NewAttachment(
                                TRANSFER_ENTITY,
                                provisional,
                                copy.documentType(),
                                copy.reference(),
                                copy.orderDate(),
                                copy.issuingAuthority(),
                                copy.fileName(),
                                copy.content(),
                                true));
        return new StoredOrder(stored.id(), provisional);
    }

    /**
     * SRS-16-18, M-11 UC alternate flow: "A transfer task without the order copy (where HRMS does
     * not hold it) cannot be submitted". The order reference is always recorded (the HRMS reference
     * where HRMS holds the letter); the copy is one this user stored for it. A refusal is audited.
     */
    private DocumentStore.AttachmentView transferOrder(
            AccessContext context, String eis, NewTransfer request) {
        if (blank(request.orderReference()) == null) {
            blocked(context, eis, "order reference");
            throw new FciException(
                    ApiErrorCode.M11_ORDER, new Object[] {"REFERENCE_MISSING"}, List.of(), null);
        }
        if (request.orderHeldInHrms()) {
            return null;
        }
        DocumentStore.AttachmentView copy =
                request.orderAttachmentId() == null
                                || request.orderProvisionalKey() == null
                                || !request.orderProvisionalKey()
                                        .startsWith(DocumentStore.PROVISIONAL)
                        ? null
                        : documents.active(TRANSFER_ENTITY, request.orderProvisionalKey()).stream()
                                .filter(
                                        a ->
                                                a.id().equals(request.orderAttachmentId())
                                                        && context.loginId().equals(a.uploadedBy()))
                                .findFirst()
                                .orElse(null);
        if (copy == null) {
            blocked(context, eis, "transfer order copy");
            throw new FciException(
                    ApiErrorCode.M11_ORDER, new Object[] {"COPY_MISSING"}, List.of(), null);
        }
        return copy;
    }

    private void blocked(AccessContext context, String eis, String missing) {
        audit.submissionBlocked(context.loginId(), TRANSFER_ENTITY, eis, "M-11", missing);
    }

    private boolean hasPendingTransfer(Long employeeId) {
        List<Long> ids =
                transfers.findByEmployeeIdOrderByTransferDateDesc(employeeId).stream()
                        .map(EmployeeTransfer::getApprovalId)
                        .filter(Objects::nonNull)
                        .toList();
        return approvalStatuses.of(ids).containsValue(ApprovalStatus.PENDING_APPROVAL);
    }

    // ---------------------------------------------------------------- views

    private Employee employee(String eis) {
        return employees
                .findByEmpNum(eis == null ? "" : eis.strip())
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private java.util.Set<Long> scope(AccessContext context) {
        return context.dataScope().isEmpty() ? java.util.Set.of(-1L) : context.dataScope();
    }

    private List<EventView> eventViews(List<LifecycleEvent> rows) {
        Map<Long, ApprovalStatus> status =
                approvalStatuses.of(
                        rows.stream()
                                .map(LifecycleEvent::getApprovalId)
                                .filter(Objects::nonNull)
                                .toList());
        return rows.stream()
                .map(
                        e -> {
                            Employee m = employees.findById(e.getEmployeeId()).orElse(null);
                            ApprovalStatus s = status.get(e.getApprovalId());
                            return new EventView(
                                    e.getEventId(),
                                    m == null ? null : m.getEmpNum(),
                                    m == null ? null : m.fullName(),
                                    e.getEventType(),
                                    e.getEventDate(),
                                    e.getEndDate(),
                                    e.getReason(),
                                    e.getSubsistencePercent(),
                                    e.getReleaseFromYymm(),
                                    e.getReleaseToYymm(),
                                    e.getOrderReference(),
                                    e.getEventSource(),
                                    e.getCreatedBy(),
                                    s == null ? "NONE" : s.name(),
                                    e.getApprovalId(),
                                    e.getHrmsChangeTaskId(),
                                    e.getHrmsRequestId(),
                                    e.getInterveningPeriodTreatment());
                        })
                .toList();
    }

    private List<TransferView> transferViews(List<EmployeeTransfer> rows) {
        Map<Long, ApprovalStatus> status =
                approvalStatuses.of(
                        rows.stream()
                                .map(EmployeeTransfer::getApprovalId)
                                .filter(Objects::nonNull)
                                .toList());
        return rows.stream()
                .map(
                        t -> {
                            Employee m = employees.findById(t.getEmployeeId()).orElse(null);
                            ApprovalStatus s = status.get(t.getApprovalId());
                            return new TransferView(
                                    t.getTransferId(),
                                    m == null ? null : m.getEmpNum(),
                                    m == null ? null : m.fullName(),
                                    label(t.getFromLocationId()),
                                    label(t.getToLocationId()),
                                    t.getTransferDate(),
                                    t.getRelievingDate(),
                                    t.getJoiningDate(),
                                    t.getJoiningTime(),
                                    t.getOrderReference(),
                                    t.getLpcReference(),
                                    t.isSplitWageFlag(),
                                    t.getFromUnitDays(),
                                    t.getToUnitDays(),
                                    t.getReason(),
                                    s == null ? "NONE" : s.name(),
                                    t.getApprovalId());
                        })
                .toList();
    }

    private String label(Long locationId) {
        return locationId == null
                ? null
                : org.location(locationId).map(OrgLocation::displayLabel).orElse(null);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
