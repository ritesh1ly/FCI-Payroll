package in.gov.fci.payroll.jobs.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.ChangeType;
import in.gov.fci.payroll.jobs.api.BackgroundJobs;
import in.gov.fci.payroll.jobs.api.ResumeCheck;
import in.gov.fci.payroll.jobs.batch.support.BatchHistory;
import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.jobs.batch.support.PartitionedSteps;
import in.gov.fci.payroll.jobs.business.JobParameterCodec;
import in.gov.fci.payroll.jobs.business.model.BatchJobControl;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.ControlCommand;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The batch job queue as its users see it (both profiles; the online REST API and the batch
 * schedules call it). Submissions and control commands are rows only: nothing here launches or
 * stops a job, a batch instance does.
 *
 * <p>Maker-checker: a submission from the UI goes through the approval workflow (approval_queue,
 * rule C-13 CREATE): it is AWAITING_APPROVAL in the Payroll Checker's inbox (W-01) and on C-13, and
 * becomes PENDING, and thus claimable by a batch node, only when a checker of the unit who is not
 * the maker approves it. Scheduled jobs are system-initiated and enter the queue directly.
 * Authorisation is C-13 (VIEW / CREATE / EDIT) within the caller's data scope; jobs without a
 * location (scheduled) are visible to every C-13 viewer and controllable by the System
 * Administrator only.
 */
@Service
@Loggable
public class JobRequestService implements BackgroundJobs {

    static final String SCREEN = "C-13";

    /**
     * The screen that owns what a job type produces, whose CREATE right the requester also needs at
     * the unit (Module 08: Form 16 Part B on C-10, the 24Q return on C-11).
     */
    static final Map<JobType, String> OWNER_SCREENS =
            Map.of(JobType.TAX_FORM16_PART_B, "C-10", JobType.TAX_24Q_RETURN, "C-11");

    /** Jobs that run on the payroll cycle unit of their location (C-02). */
    private static final Set<JobType> CYCLE_JOBS =
            EnumSet.of(
                    JobType.PAYROLL_DRY_RUN,
                    JobType.PAYROLL_COMPUTATION,
                    JobType.DA_ARREAR,
                    JobType.HRA_ARREAR,
                    JobType.TAX_FORM16_PART_B,
                    JobType.TAX_24Q_RETURN);

    static final String SYSADMIN = "SYSTEM_ADMINISTRATOR";

    /** The approval item of an abandon (plan 05j): key = the request id. */
    public static final String ABANDON_ENTITY = "batch_job_abandon";

    /** {@link JobView#pendingCommand} while an abandon waits for the checker. */
    static final String ABANDON_AWAITING_APPROVAL = "ABANDON_AWAITING_APPROVAL";

    static final Set<String> SORTABLE =
            Set.of("id", "requestedAt", "startedAt", "finishedAt", "status", "jobType", "priority");

    public record JobQuery(
            JobStatus status,
            JobType jobType,
            Long locationId,
            LocalDate from,
            LocalDate to,
            String requestedBy,
            int page,
            int size,
            String sort,
            boolean ascending) {}

    public record JobView(
            Long id,
            String jobType,
            Map<String, String> params,
            int priority,
            String status,
            String source,
            String requestedBy,
            Long locationId,
            String locationLabel,
            String payPeriod,
            Instant requestedAt,
            Instant startedAt,
            Instant finishedAt,
            Long durationSeconds,
            int attemptNo,
            Long jobExecutionId,
            Long approvalId,
            boolean canDecide,
            long readCount,
            long writeCount,
            long skipCount,
            String resultSummary,
            String errorMessage,
            String pendingCommand,
            List<String> allowedCommands) {}

    public record StepView(
            String name,
            boolean partition,
            String status,
            long readCount,
            long writeCount,
            long filterCount,
            long readSkipCount,
            long processSkipCount,
            long writeSkipCount,
            long commitCount,
            long rollbackCount,
            Long plannedItems,
            Instant startTime,
            Instant endTime,
            String exitCode,
            String exitDescription) {}

    public record AttemptView(
            Long jobExecutionId,
            String status,
            String exitCode,
            String exitDescription,
            Instant startTime,
            Instant endTime,
            List<StepView> steps) {}

    public record ControlView(
            Long id,
            String command,
            String status,
            String requestedBy,
            Instant requestedAt,
            String reason,
            String appliedBy,
            Instant appliedAt,
            String outcome) {}

    public record JobDetail(
            JobView job,
            List<AttemptView> attempts,
            List<ControlView> controls,
            List<JobJournal.AuditEntry> audit,
            long skippedTotal) {}

    public record PageView<T>(List<T> items, int page, int size, long total) {}

    public record LocationOption(Long id, String code, String label) {}

    private final JobRepositories.JobRequests requests;
    private final JobRepositories.Controls controls;
    private final JobCatalog catalog;
    private final JobJournal journal;
    private final BatchHistory explorer;
    private final Authorization policy;
    private final OrgDirectory org;
    private final AuditService audit;
    private final MakerChecker approvals;
    private final BatchProperties properties;
    private final in.gov.fci.payroll.platform.config.ApiProperties apiProperties;
    private final List<ResumeCheck> resumeChecks;
    private final ApprovalStatuses approvalStatuses;
    private final JobRepositories.Halts halts;
    private final Clock clock;

    public JobRequestService(
            JobRepositories.JobRequests requests,
            JobRepositories.Controls controls,
            JobCatalog catalog,
            JobJournal journal,
            BatchHistory explorer,
            Authorization policy,
            OrgDirectory org,
            AuditService audit,
            MakerChecker approvals,
            BatchProperties properties,
            in.gov.fci.payroll.platform.config.ApiProperties apiProperties,
            List<ResumeCheck> resumeChecks,
            ApprovalStatuses approvalStatuses,
            JobRepositories.Halts halts,
            Clock clock) {
        this.requests = requests;
        this.controls = controls;
        this.catalog = catalog;
        this.journal = journal;
        this.explorer = explorer;
        this.policy = policy;
        this.org = org;
        this.audit = audit;
        this.approvals = approvals;
        this.properties = properties;
        this.apiProperties = apiProperties;
        this.resumeChecks = List.copyOf(resumeChecks);
        this.approvalStatuses = approvalStatuses;
        this.halts = halts;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- submit

    /**
     * Submits a job for checking (maker-checker). Idempotent on content: while a request for the
     * same job and parameters is awaiting approval, pending, running or paused, a second one is
     * refused (409). Nothing runs until a Payroll Checker approves it.
     */
    @Transactional
    public JobView submit(
            AccessContext context,
            String jobTypeName,
            Map<String, String> rawParams,
            Integer priority,
            String reason) {
        JobType type =
                JobType.of(jobTypeName)
                        .orElseThrow(() -> new FciException(ApiErrorCode.JOB_TYPE_NOT_SUBMITTABLE));
        Map<String, String> params = catalog.validate(type, rawParams);
        Long locationId = Long.valueOf(params.get("locationId"));
        org.location(locationId).orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        policy.requireAt(context, SCREEN, "CREATE", locationId);
        if (CYCLE_JOBS.contains(type)) {
            // The run covers the location's whole cycle unit (possibly a hosting ancestor): the
            // requester must hold C-13 CREATE there too, not just at the location named.
            Long unitId =
                    org.cycleUnitOf(locationId)
                            .orElseThrow(() -> new FciException(ApiErrorCode.CYCLE_NO_UNIT))
                            .getId();
            policy.requireAt(context, SCREEN, "CREATE", unitId);
            String owner = OWNER_SCREENS.get(type);
            if (owner != null) {
                // A statutory output is generated by its own screen's Maker (C-10, C-11): C-13
                // runs it in the background, it does not widen who may generate it.
                policy.requireAt(context, owner, "CREATE", unitId);
            }
        }
        // SRS-NF-11 (DR-17): the type sets the priority; a requester may lower it, never raise it.
        if (priority != null && (priority < 1 || priority > 9)) {
            throw new FciException(
                    ApiErrorCode.JOB_PARAMETER_INVALID, new Object[] {"priority"}, List.of(), null);
        }
        int effectivePriority =
                priority == null ? type.priority() : Math.min(priority, type.priority());
        if (effectivePriority < 1) {
            throw new FciException(
                    ApiErrorCode.JOB_PARAMETER_INVALID, new Object[] {"priority"}, List.of(), null);
        }
        BatchJobRequest saved =
                queue(
                        type,
                        params,
                        effectivePriority,
                        BatchJobRequest.Source.UI,
                        context.userId(),
                        context.loginId(),
                        locationId);
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                SCREEN,
                                "CREATE",
                                "batch_job_request",
                                String.valueOf(saved.getId()),
                                ActionType.CREATE,
                                locationId,
                                "{\"jobType\":\""
                                        + type.name()
                                        + "\",\"params\":"
                                        + JobParameterCodec.toJson(params)
                                        + "}",
                                null,
                                reason),
                        null);
        saved.awaitApproval(item.id());
        saved = requests.save(saved);
        journal.record(
                saved.getId(),
                JobJournal.Event.SUBMITTED,
                context.loginId(),
                JobStatus.PENDING,
                JobStatus.AWAITING_APPROVAL,
                null,
                "Awaiting approval " + item.id() + ": " + reason);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "batch_job_request",
                                String.valueOf(saved.getId()),
                                "job_status",
                                null,
                                "PENDING",
                                type.name() + " " + JobParameterCodec.toJson(params))
                        .type(ChangeType.INITIAL_VALUE)
                        .module(SCREEN));
        return view(saved, context);
    }

    /**
     * Plan 05 D-21: a job a module queues for its user (C-09 simulation above the interactive
     * limit). PENDING at once, without an approval: the module authorised the use case, and the job
     * changes nothing a checker decides. The requester's C-13 VIEW right at the office is still
     * needed, so the request is one the user can follow on C-13.
     */
    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<BackgroundJobs.Job> find(Kind kind, long id) {
        return requests.findById(id)
                .filter(r -> r.getJobType().name().equals(kind.name()))
                .map(r -> job(kind, r));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<BackgroundJobs.Job> latest(
            Kind kind, String login, Map<String, String> params) {
        JobType type = JobType.valueOf(kind.name());
        String hash = JobParameterCodec.hash(type.name(), new java.util.TreeMap<>(params));
        return requests.findFirstByJobTypeAndParamHashAndRequestedByLoginOrderByIdDesc(
                        type, hash, login)
                .map(r -> job(kind, r));
    }

    private static BackgroundJobs.Job job(Kind kind, BatchJobRequest r) {
        return new BackgroundJobs.Job(
                r.getId(),
                kind,
                r.getStatus().name(),
                r.getRequestedByLogin(),
                r.getLocationId(),
                JobParameterCodec.fromJson(r.getParams()),
                r.getWriteCount(),
                r.getSkipCount(),
                r.getResultSummary());
    }

    @Override
    @Transactional
    public long enqueue(
            AccessContext context, Kind kind, long locationId, Map<String, String> params) {
        JobType type = JobType.valueOf(kind.name());
        policy.requireAt(context, SCREEN, "VIEW", locationId);
        Map<String, String> sorted = new java.util.TreeMap<>(params);
        BatchJobRequest saved =
                queue(
                        type,
                        sorted,
                        type.priority(),
                        BatchJobRequest.Source.UI,
                        context.userId(),
                        context.loginId(),
                        locationId);
        journal.record(
                saved.getId(),
                JobJournal.Event.SUBMITTED,
                context.loginId(),
                null,
                JobStatus.PENDING,
                null,
                "Queued by " + type.name());
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "batch_job_request",
                                String.valueOf(saved.getId()),
                                "job_status",
                                null,
                                "PENDING",
                                type.name() + " " + JobParameterCodec.toJson(sorted))
                        .type(ChangeType.INITIAL_VALUE)
                        .module(SCREEN));
        return saved.getId();
    }

    /** Called by the batch profile's schedules; false if the same job is already open. */
    @Transactional
    public boolean enqueueScheduled(JobType type, Map<String, String> params) {
        String hash = JobParameterCodec.hash(type.name(), params);
        if (requests.existsByJobTypeAndParamHashAndStatusIn(type, hash, JobStatus.OPEN)) {
            return false;
        }
        queue(
                type,
                params,
                type.priority(),
                BatchJobRequest.Source.SCHEDULE,
                null,
                "system.schedule",
                null);
        return true;
    }

    private BatchJobRequest queue(
            JobType type,
            Map<String, String> params,
            int priority,
            BatchJobRequest.Source source,
            Long userId,
            String login,
            Long locationId) {
        String hash = JobParameterCodec.hash(type.name(), params);
        if (requests.existsByJobTypeAndParamHashAndStatusIn(type, hash, JobStatus.OPEN)) {
            throw new FciException(ApiErrorCode.JOB_ALREADY_QUEUED);
        }
        BatchJobRequest saved =
                requests.save(
                        BatchJobRequest.submit(
                                type,
                                JobParameterCodec.toJson(params),
                                hash,
                                priority,
                                source,
                                userId,
                                login,
                                locationId,
                                JobCatalog.periodOf(type, params),
                                clock.instant()));
        if (source == BatchJobRequest.Source.SCHEDULE) {
            journal.record(
                    saved.getId(),
                    JobJournal.Event.SUBMITTED,
                    login,
                    null,
                    JobStatus.PENDING,
                    null,
                    "Scheduled");
        }
        return saved;
    }

    // ---------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public PageView<JobView> page(AccessContext context, JobQuery query) {
        policy.require(context, SCREEN, "VIEW");
        String sortField = SORTABLE.contains(query.sort()) ? query.sort() : "requestedAt";
        Sort sort =
                Sort.by(query.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC, sortField)
                        .and(Sort.by(Sort.Direction.DESC, "id"));
        int size = Math.min(Math.max(1, query.size()), 100);
        Page<BatchJobRequest> page =
                requests.findAll(
                        filter(context, query),
                        PageRequest.of(Math.max(0, query.page()), size, sort));
        Set<Long> pending = pendingControls(page.getContent());
        Set<Long> abandoning = abandonPending();
        List<JobView> items =
                page.getContent().stream()
                        .map(r -> view(r, context))
                        .map(v -> pending.contains(v.id()) ? withPending(v) : v)
                        .map(
                                v ->
                                        abandoning.contains(v.id())
                                                ? withPending(v, ABANDON_AWAITING_APPROVAL)
                                                : v)
                        .toList();
        return new PageView<>(items, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public JobDetail detail(AccessContext context, Long id) {
        BatchJobRequest request = visible(context, id);
        List<AttemptView> attempts = new ArrayList<>();
        if (request.getJobExecutionId() != null) {
            JobExecution latest = explorer.getJobExecution(request.getJobExecutionId());
            if (latest != null) {
                for (JobExecution execution : explorer.getJobExecutions(latest.getJobInstance())) {
                    attempts.add(attempt(execution));
                }
            }
        }
        List<ControlView> commands =
                controls.findByJobRequestIdOrderByIdDesc(id).stream()
                        .map(
                                c ->
                                        new ControlView(
                                                c.getId(),
                                                c.getCommand().name(),
                                                c.getStatus().name(),
                                                c.getRequestedByLogin(),
                                                c.getRequestedAt(),
                                                c.getReason(),
                                                c.getAppliedBy(),
                                                c.getAppliedAt(),
                                                c.getOutcomeMessage()))
                        .toList();
        JobView view = view(request, context);
        if (commands.stream().anyMatch(c -> "PENDING".equals(c.status()))) {
            view = withPending(view);
        }
        if (abandonPending().contains(id)) {
            view = withPending(view, ABANDON_AWAITING_APPROVAL);
        }
        return new JobDetail(view, attempts, commands, journal.audit(id), journal.skippedCount(id));
    }

    @Transactional(readOnly = true)
    public PageView<JobJournal.SkippedItem> skipped(
            AccessContext context, Long id, int page, int size) {
        visible(context, id);
        int s = Math.min(Math.max(1, size), 100);
        int p = Math.max(0, page);
        return new PageView<>(journal.skippedItems(id, p, s), p, s, journal.skippedCount(id));
    }

    /** Locations the caller may submit a job for (the submit form's picker). */
    @Transactional(readOnly = true)
    public List<LocationOption> submitLocations(AccessContext context) {
        policy.require(context, SCREEN, "CREATE");
        return context.dataScope().stream()
                .filter(id -> policy.canAt(context, SCREEN, "CREATE", id))
                .map(org::location)
                .flatMap(java.util.Optional::stream)
                .map(
                        l ->
                                new LocationOption(
                                        l.getId(),
                                        l.getCode(),
                                        l.getType().name()
                                                + " — "
                                                + l.getName()
                                                + " ("
                                                + l.getCode()
                                                + ")"))
                .sorted(java.util.Comparator.comparing(LocationOption::code))
                .toList();
    }

    // ---------------------------------------------------------------- control

    /**
     * Records a Stop / Pause / Resume / Restart command for the batch instance. Valid only in the
     * states of {@link ControlCommand}, one pending command at a time, and Resume / Restart only
     * within the restart window after the run ended.
     */
    @Transactional
    public ControlView requestControl(
            AccessContext context, Long id, ControlCommand command, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new FciException(ApiErrorCode.REASON_REQUIRED);
        }
        if (command.needsApproval()) {
            // Recorded only by its approval (requestAbandon); never as a direct command.
            throw new IllegalArgumentException(command + " is requested through requestAbandon");
        }
        BatchJobRequest request = visible(context, id);
        requireControl(context, request);
        if (!command.isValidFor(request.getStatus())) {
            throw new FciException(
                    ApiErrorCode.JOB_INVALID_STATE,
                    new Object[] {command.name(), request.getStatus().name()},
                    List.of(),
                    null);
        }
        if (controls.existsByJobRequestIdAndStatus(id, BatchJobControl.Status.PENDING)) {
            throw new FciException(ApiErrorCode.JOB_CONTROL_PENDING);
        }
        if (abandonPending().contains(id)) {
            throw new FciException(ApiErrorCode.JOB_ABANDON_PENDING);
        }
        if (command.startsAttempt() && halts.existsByReleasedAtIsNull()) {
            throw new FciException(ApiErrorCode.JOB_HALTED);
        }
        if (command.startsAttempt() && !withinRestartWindow(request)) {
            throw new FciException(
                    ApiErrorCode.JOB_RESTART_EXPIRED,
                    new Object[] {properties.restartWindow().toHours()},
                    List.of(),
                    null);
        }
        if (command.startsAttempt()) {
            requireResumable(request);
        }
        BatchJobControl saved =
                controls.save(
                        BatchJobControl.request(
                                id,
                                command,
                                context.userId(),
                                context.loginId(),
                                reason.strip(),
                                clock.instant()));
        journal.record(
                id,
                JobJournal.Event.CONTROL_REQUESTED,
                context.loginId(),
                request.getStatus(),
                null,
                request.getJobExecutionId(),
                command.name() + ": " + reason.strip());
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "batch_job_request",
                                String.valueOf(id),
                                "control",
                                null,
                                command.name(),
                                reason.strip())
                        .module(SCREEN));
        return new ControlView(
                saved.getId(),
                command.name(),
                saved.getStatus().name(),
                saved.getRequestedByLogin(),
                saved.getRequestedAt(),
                saved.getReason(),
                null,
                null,
                null);
    }

    /**
     * Plan 05j step j2 (owner decision D-5; SRS-15-27): asks for a failed, stopped or paused job to
     * be abandoned for good. Maker-checker: the controller asks with a reason, a Payroll Checker
     * decides (C13_ABANDON at the unit; C13_ABANDON_SYS, at HQ, for a scheduled job). Nothing
     * changes until the approval ({@link JobAbandonEffect}); meanwhile no other command is taken.
     */
    @Transactional
    public JobView requestAbandon(AccessContext context, Long id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new FciException(ApiErrorCode.REASON_REQUIRED);
        }
        BatchJobRequest request = visible(context, id);
        requireControl(context, request);
        if (!ControlCommand.ABANDON.isValidFor(request.getStatus())) {
            throw new FciException(
                    ApiErrorCode.JOB_INVALID_STATE,
                    new Object[] {ControlCommand.ABANDON.name(), request.getStatus().name()},
                    List.of(),
                    null);
        }
        if (controls.existsByJobRequestIdAndStatus(id, BatchJobControl.Status.PENDING)) {
            throw new FciException(ApiErrorCode.JOB_CONTROL_PENDING);
        }
        boolean scheduled = request.getLocationId() == null;
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                scheduled ? "C13_ABANDON_SYS" : "C13_ABANDON",
                                SCREEN,
                                "EDIT",
                                ABANDON_ENTITY,
                                String.valueOf(id),
                                ActionType.UPDATE,
                                scheduled ? context.locationId() : request.getLocationId(),
                                "{\"jobRequestId\":"
                                        + id
                                        + ",\"jobType\":\""
                                        + request.getJobType().name()
                                        + "\",\"status\":\""
                                        + request.getStatus().name()
                                        + "\"}",
                                null,
                                reason.strip()),
                        null);
        journal.record(
                id,
                JobJournal.Event.ABANDON_REQUESTED,
                context.loginId(),
                request.getStatus(),
                null,
                request.getJobExecutionId(),
                "Approval " + item.id() + ": " + reason.strip());
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "batch_job_request",
                                String.valueOf(id),
                                "abandon",
                                null,
                                "REQUESTED",
                                reason.strip())
                        .approval(item.id())
                        .module(SCREEN));
        return withPending(view(request, context), ABANDON_AWAITING_APPROVAL);
    }

    /** The requests whose abandon waits for the checker. */
    private Set<Long> abandonPending() {
        return approvalStatuses.pending(ABANDON_ENTITY).stream()
                .map(p -> Long.valueOf(p.entityKey()))
                .collect(Collectors.toSet());
    }

    /**
     * SRS-15-26 / BR-CYC-013: Resume and Restart continue the run from where it stopped, so the
     * module that owns the job may refuse it (rates or rules activated, parameters changed since
     * the run started). The refusal names the reason; a new submission is the full restart.
     */
    private void requireResumable(BatchJobRequest request) {
        for (ResumeCheck check : resumeChecks) {
            Optional<ErrorCode> refusal =
                    check.refusal(
                            request.getId(), request.getJobType().name(), request.getParamHash());
            if (refusal.isPresent()) {
                throw new FciException(refusal.get());
            }
        }
    }

    /**
     * The restart window (cf. computation_run.abandon_after): a run older than this is not resumed.
     */
    public boolean withinRestartWindow(BatchJobRequest request) {
        Instant end =
                request.getFinishedAt() != null
                        ? request.getFinishedAt()
                        : request.getRequestedAt();
        return end.plus(properties.restartWindow()).isAfter(clock.instant());
    }

    // ---------------------------------------------------------------- helpers

    private void requireControl(AccessContext context, BatchJobRequest request) {
        if (request.getLocationId() == null) {
            if (!context.roleCodes().contains(SYSADMIN)) {
                // OD-130: C-13 offers no control of such a job to this role; a call is forged.
                throw new FciException(
                                ApiErrorCode.PERMISSION_DENIED,
                                new Object[] {SCREEN, "EDIT"},
                                List.of(),
                                null)
                        .asAccessOffence();
            }
            policy.require(context, SCREEN, "EDIT");
            return;
        }
        policy.requireRecordAt(context, SCREEN, "EDIT", request.getLocationId());
    }

    private boolean mayControl(AccessContext context, BatchJobRequest request) {
        if (request.getLocationId() == null) {
            return context.roleCodes().contains(SYSADMIN) && policy.can(context, SCREEN, "EDIT");
        }
        return policy.canAt(context, SCREEN, "EDIT", request.getLocationId());
    }

    /**
     * A checker of the unit who is not the maker may approve or reject (UX hint; ApprovalService
     * decides).
     */
    private boolean mayDecide(AccessContext context, BatchJobRequest request) {
        return request.getStatus() == JobStatus.AWAITING_APPROVAL
                && request.getApprovalId() != null
                && request.getLocationId() != null
                && !context.userId().equals(request.getRequestedBy())
                && policy.canAt(context, SCREEN, "APPROVE", request.getLocationId());
    }

    private BatchJobRequest visible(AccessContext context, Long id) {
        policy.require(context, SCREEN, "VIEW");
        BatchJobRequest request =
                requests.findById(id)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        if (request.getLocationId() != null
                && !context.dataScope().contains(request.getLocationId())) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return request;
    }

    private Specification<BatchJobRequest> filter(AccessContext context, JobQuery query) {
        Set<Long> scope = context.dataScope();
        ZoneId zone = apiProperties.businessZone();
        return (root, cq, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(
                    scope.isEmpty()
                            ? cb.isNull(root.get("locationId"))
                            : cb.or(
                                    cb.isNull(root.get("locationId")),
                                    root.get("locationId").in(scope)));
            if (query.status() != null) {
                where.add(cb.equal(root.get("status"), query.status()));
            }
            if (query.jobType() != null) {
                where.add(cb.equal(root.get("jobType"), query.jobType()));
            }
            if (query.locationId() != null) {
                where.add(cb.equal(root.get("locationId"), query.locationId()));
            }
            if (query.from() != null) {
                where.add(
                        cb.greaterThanOrEqualTo(
                                root.get("requestedAt"),
                                query.from().atStartOfDay(zone).toInstant()));
            }
            if (query.to() != null) {
                where.add(
                        cb.lessThan(
                                root.get("requestedAt"),
                                query.to().plusDays(1).atStartOfDay(zone).toInstant()));
            }
            if (query.requestedBy() != null && !query.requestedBy().isBlank()) {
                where.add(
                        cb.like(
                                cb.lower(root.get("requestedByLogin")),
                                "%"
                                        + query.requestedBy()
                                                .strip()
                                                .toLowerCase(java.util.Locale.ROOT)
                                                .replace("\\", "\\\\")
                                                .replace("%", "\\%")
                                                .replace("_", "\\_")
                                        + "%",
                                '\\'));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private Set<Long> pendingControls(List<BatchJobRequest> rows) {
        return rows.stream()
                .filter(
                        r ->
                                controls.existsByJobRequestIdAndStatus(
                                        r.getId(), BatchJobControl.Status.PENDING))
                .map(BatchJobRequest::getId)
                .collect(Collectors.toSet());
    }

    /**
     * What a request has read, written and skipped: live from its steps while it runs ({@link
     * PartitionedSteps#counts}), else as the batch node recorded it when the attempt ended. A
     * running request whose execution is not in the repository shows the recorded counts.
     */
    private PartitionedSteps.Counts counts(BatchJobRequest request) {
        PartitionedSteps.Counts recorded =
                new PartitionedSteps.Counts(
                        request.getReadCount(), request.getWriteCount(), request.getSkipCount());
        if (request.getStatus() != JobStatus.RUNNING || request.getJobExecutionId() == null) {
            return recorded;
        }
        JobExecution execution = explorer.getJobExecution(request.getJobExecutionId());
        return execution == null
                ? recorded
                : PartitionedSteps.counts(execution.getStepExecutions());
    }

    private JobView view(BatchJobRequest r, AccessContext context) {
        Duration duration = r.duration(clock.instant());
        PartitionedSteps.Counts counts = counts(r);
        List<String> allowed = new ArrayList<>();
        if (mayControl(context, r)) {
            boolean halted = halts.existsByReleasedAtIsNull();
            for (ControlCommand command : EnumSet.allOf(ControlCommand.class)) {
                if (command.isValidFor(r.getStatus())
                        && (!command.startsAttempt() || (withinRestartWindow(r) && !halted))) {
                    allowed.add(command.name());
                }
            }
        }
        return new JobView(
                r.getId(),
                r.getJobType().name(),
                JobParameterCodec.fromJson(r.getParams()),
                r.getPriority(),
                r.getStatus().name(),
                r.getSource().name(),
                r.getRequestedByLogin(),
                r.getLocationId(),
                r.getLocationId() == null
                        ? null
                        : org.location(r.getLocationId())
                                .map(
                                        l ->
                                                l.getType().name()
                                                        + " — "
                                                        + l.getName()
                                                        + " ("
                                                        + l.getCode()
                                                        + ")")
                                .orElse(null),
                r.getPayPeriod(),
                r.getRequestedAt(),
                r.getStartedAt(),
                r.getFinishedAt(),
                duration == null ? null : duration.toSeconds(),
                r.getAttemptNo(),
                r.getJobExecutionId(),
                r.getApprovalId(),
                mayDecide(context, r),
                counts.read(),
                counts.written(),
                counts.skipped(),
                r.getResultSummary(),
                r.getErrorMessage(),
                null,
                List.copyOf(allowed));
    }

    /** While a command is pending, no further command is offered. */
    private static JobView withPending(JobView v) {
        return withPending(v, "PENDING");
    }

    /**
     * As {@link #withPending(JobView)}, naming what waits (a command, or an abandon's approval).
     */
    private static JobView withPending(JobView v, String pending) {
        return new JobView(
                v.id(),
                v.jobType(),
                v.params(),
                v.priority(),
                v.status(),
                v.source(),
                v.requestedBy(),
                v.locationId(),
                v.locationLabel(),
                v.payPeriod(),
                v.requestedAt(),
                v.startedAt(),
                v.finishedAt(),
                v.durationSeconds(),
                v.attemptNo(),
                v.jobExecutionId(),
                v.approvalId(),
                v.canDecide(),
                v.readCount(),
                v.writeCount(),
                v.skipCount(),
                v.resultSummary(),
                v.errorMessage(),
                pending,
                List.of());
    }

    private static AttemptView attempt(JobExecution execution) {
        List<StepView> steps =
                execution.getStepExecutions().stream()
                        .sorted(java.util.Comparator.comparing(StepExecution::getId))
                        .map(
                                s ->
                                        new StepView(
                                                s.getStepName(),
                                                PartitionedSteps.isWorker(s.getStepName()),
                                                s.getStatus().name(),
                                                s.getReadCount(),
                                                s.getWriteCount(),
                                                s.getFilterCount(),
                                                s.getReadSkipCount(),
                                                s.getProcessSkipCount(),
                                                s.getWriteSkipCount(),
                                                s.getCommitCount(),
                                                s.getRollbackCount(),
                                                s.getExecutionContext()
                                                                .containsKey(BatchKeys.PLANNED)
                                                        ? s.getExecutionContext()
                                                                .getLong(BatchKeys.PLANNED)
                                                        : null,
                                                toInstant(s.getStartTime()),
                                                toInstant(s.getEndTime()),
                                                s.getExitStatus().getExitCode(),
                                                s.getExitStatus().getExitDescription()))
                        .toList();
        return new AttemptView(
                execution.getId(),
                execution.getStatus().name(),
                execution.getExitStatus().getExitCode(),
                execution.getExitStatus().getExitDescription(),
                toInstant(execution.getStartTime()),
                toInstant(execution.getEndTime()),
                steps);
    }

    private static Instant toInstant(java.time.LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }
}
