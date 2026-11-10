package in.gov.fci.payroll.integration.orchestration;

import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.api.InterfaceIntake;
import in.gov.fci.payroll.integration.api.InterfaceSettings;
import in.gov.fci.payroll.integration.business.HrmsContracts;
import in.gov.fci.payroll.integration.business.HrmsPayloads;
import in.gov.fci.payroll.integration.business.HrmsPayloads.ApplyPlan;
import in.gov.fci.payroll.integration.business.HrmsPayloads.Change;
import in.gov.fci.payroll.integration.business.HrmsPayloads.StageOnly;
import in.gov.fci.payroll.integration.business.IntegrationErrorCode;
import in.gov.fci.payroll.integration.business.IntegrationRules;
import in.gov.fci.payroll.integration.config.HrmsApiProperties;
import in.gov.fci.payroll.platform.error.FciException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * The inbound {@code request-*} APIs of the HRMS Postman collection (SRS-INT-5, SRS-INT-12,
 * SRS-INT-23, SRS-INT-26, SRS-INT-30 ... 32; BR-INT-003, BR-INT-004; plan 23 section 7). A call
 * with one request is one exchange window; a body with several requests (cafeteria and HRA / DA
 * arrays group their rows by request_id) is one window and one acknowledgement per request, so one
 * bad request is rejected with its reason while the others are accepted and every window balances
 * (received = accepted + rejected, SRS-INT-32). The body is checked against the collection's
 * payload (mandatory request_id and emp_num, typed fields; a field the collection does not list is
 * kept as received and never applied; no repeated key), the employee is found by the HRMS employee
 * number ({@code employee.emp_num}, the EIS number) and must be STAFF (SRS-INT-27). A request is
 * staged once per request_id: a repeat with the same content answers the earlier result, a repeat
 * with different content is refused as conflicting and the earlier record is never overwritten. A
 * request that cannot be staged (bad shape, unknown employee) leaves no key behind, so HRMS may
 * resend it corrected under the same request_id; a staged request that was rejected is resent under
 * a new request_id with {@code reprocess_of=<rejected id>} (SRS-INT-31). Answers: Accepted (applied
 * through I-21), Received (kept as received, the reason recorded on the record) or Rejected (with
 * the reason). A pay-effect value enters only through {@link HrmsChanges#receive} (SRS-1-19,
 * SRS-G-45); an interface without a configuration in force is refused (D23-3). A technical failure
 * while applying rolls back the owning module's change and rejects the staged record with a reason
 * telling the source to resend.
 */
@Service
public class HrmsInboundService {

    /** The acknowledgement of one record (BR-INT-003). */
    public record Ack(
            int http,
            String requestId,
            String hrmsRequestId,
            String status,
            String reason,
            boolean duplicate) {}

    /** The answer of a call: one acknowledgement per request in the body. */
    public record Result(List<Ack> acks) {
        /** The HTTP status: the acknowledgement's for one request, 207 for several. */
        public int http() {
            return acks.size() == 1 ? acks.getFirst().http() : 207;
        }
    }

    static final String RECEIVED = "Received";
    static final String ACCEPTED = "Accepted";
    static final String REJECTED = "Rejected";

    private final IntegrationStore store;
    private final InterfaceIntake intake;
    private final InterfaceSettings settings;
    private final HrmsApiProperties properties;
    private final EmployeeDirectory employees;
    private final HrmsChanges changes;
    private final FailureAlerts alerts;
    private final Clock clock;

    public HrmsInboundService(
            IntegrationStore store,
            InterfaceIntake intake,
            InterfaceSettings settings,
            HrmsApiProperties properties,
            EmployeeDirectory employees,
            HrmsChanges changes,
            FailureAlerts alerts,
            Clock clock) {
        this.store = store;
        this.intake = intake;
        this.settings = settings;
        this.properties = properties;
        this.employees = employees;
        this.changes = changes;
        this.alerts = alerts;
        this.clock = clock;
    }

    /** Handles one inbound call; {@code query} holds the URL parameters as received. */
    public Result intake(HrmsApi api, Map<String, String> query, byte[] body) {
        HrmsContracts.Request contract =
                HrmsContracts.request(api)
                        .orElseThrow(() -> new IllegalArgumentException(api.code()));
        if (settings.inForce(contract.interfaceCode()).isEmpty()) {
            throw new FciException(
                    IntegrationErrorCode.NOT_CONFIGURED,
                    new Object[] {contract.interfaceCode()},
                    List.of(),
                    null);
        }
        List<JsonNode> records;
        try {
            records = HrmsPayloads.records(contract, HrmsPayloads.parse(body));
        } catch (IllegalArgumentException bad) {
            throw new FciException(
                    IntegrationErrorCode.HRMS_MALFORMED,
                    new Object[] {bad.getMessage()},
                    List.of(),
                    null);
        }
        String reprocessOf = query.get("reprocess_of");
        if (reprocessOf != null && !HrmsPayloads.id(reprocessOf)) {
            throw new FciException(
                    IntegrationErrorCode.RECORD,
                    new Object[] {"reprocess_of is not a request id"},
                    List.of(),
                    null);
        }
        String whole = new String(body, StandardCharsets.UTF_8);
        Map<String, List<JsonNode>> groups = new LinkedHashMap<>();
        int unnamed = 0;
        for (JsonNode record : records) {
            String id = HrmsPayloads.text(record.get("request_id"));
            groups.computeIfAbsent(
                            HrmsPayloads.id(id) ? id.strip() : "?" + unnamed++,
                            k -> new ArrayList<>())
                    .add(record);
        }
        List<Ack> acks = new ArrayList<>();
        for (Map.Entry<String, List<JsonNode>> group : groups.entrySet()) {
            String payload = groups.size() == 1 ? whole : arrayText(group.getValue());
            acks.add(
                    request(
                            api,
                            contract,
                            group.getKey().startsWith("?") ? null : group.getKey(),
                            group.getValue(),
                            query,
                            payload,
                            reprocessOf == null ? null : reprocessOf.strip()));
        }
        return new Result(List.copyOf(acks));
    }

    private static String arrayText(List<JsonNode> rows) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            text.append(i == 0 ? "" : ",").append(rows.get(i).toString());
        }
        return text.append(']').toString();
    }

    /** One request: its rows, in one window. */
    private Ack request(
            HrmsApi api,
            HrmsContracts.Request contract,
            String requestId,
            List<JsonNode> rows,
            Map<String, String> query,
            String payload,
            String reprocessOf) {
        Optional<String> defect = batchDefect(contract, rows, query);
        if (defect.isEmpty()) {
            Optional<Stored> earlier = earlier(contract, requestId);
            if (earlier.isPresent()) {
                return duplicate(contract, requestId, payload, earlier.get());
            }
        }
        Long employeeId = null;
        String reason = defect.orElse(null);
        if (reason == null) {
            String empNum = employeeNumber(contract, rows.getFirst(), query);
            employeeId = employees.idOf(empNum).orElse(null);
            if (employeeId == null) {
                reason = "employee number is not a Payroll employee";
            } else if (!"STAFF".equals(employees.employeeTypeOf(employeeId).orElse(null))) {
                // SRS-INT-27: labour are not in HRMS; nothing from HRMS is accepted for them.
                reason = "employee number is not an HRMS employee (labour have no HRMS interface)";
                employeeId = null;
            }
        }
        if (reason != null) {
            return refusedBeforeStaging(contract, requestId, payload, reason);
        }
        return stageAndApply(api, contract, rows, requestId, payload, employeeId, reprocessOf);
    }

    /** Every row fit, and the rows are about one request and one employee. */
    private static Optional<String> batchDefect(
            HrmsContracts.Request contract, List<JsonNode> rows, Map<String, String> query) {
        String queryEmployee = query.get("emp_num");
        Set<String> employeesSeen = new HashSet<>();
        for (JsonNode row : rows) {
            Optional<String> bad = HrmsPayloads.defect(contract, row, queryEmployee);
            if (bad.isPresent()) {
                return bad;
            }
            employeesSeen.add(employeeNumber(contract, row, query));
        }
        if (employeesSeen.size() != 1) {
            return Optional.of("the rows of one request must share emp_num");
        }
        String queryRequest = query.get("request_id");
        String id = HrmsPayloads.text(rows.getFirst().get("request_id"));
        if (queryRequest != null && !queryRequest.strip().equals(id)) {
            return Optional.of("request_id differs from the query parameter");
        }
        return Optional.empty();
    }

    private static String employeeNumber(
            HrmsContracts.Request contract, JsonNode record, Map<String, String> query) {
        return contract.employeeInQuery()
                ? query.get("emp_num") == null ? null : query.get("emp_num").strip()
                : HrmsPayloads.text(record.get("emp_num"));
    }

    private record Stored(String stagingStatus, String detail, String payload) {}

    private Optional<Stored> earlier(HrmsContracts.Request contract, String requestId) {
        List<Stored> rows =
                store.jdbc()
                        .query(
                                "SELECT staging_status, error_detail, payload FROM intf_staging_txn"
                                        + " WHERE txn_type = ? AND request_id = ?",
                                (rs, i) ->
                                        new Stored(
                                                rs.getString(1), rs.getString(2), rs.getString(3)),
                                contract.txnType(),
                                requestId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /**
     * SRS-INT-5: a repeat is counted in its own window. The same content answers the earlier
     * result; different content is refused as conflicting and the earlier record is left as it is.
     */
    private Ack duplicate(
            HrmsContracts.Request contract, String requestId, String payload, Stored earlier) {
        long run = intake.open(contract.interfaceCode(), requestId, now(), now());
        InterfaceIntake.Staged staged =
                intake.stage(
                        run,
                        new InterfaceIntake.Inbound(
                                contract.txnType(), requestId, requestId, null, payload, null));
        boolean same = sameContent(earlier.payload(), payload);
        if (!same) {
            event(
                    staged.stagingId(),
                    "CONFLICT_REJECTED",
                    "Same request_id received again with different content");
        }
        intake.completeCounts(
                run,
                1,
                0,
                1,
                same
                        ? "Duplicate request_id: earlier result returned"
                        : "Duplicate request_id with different content: refused");
        if (same) {
            return ack(earlier.stagingStatus(), requestId, earlier.detail(), true);
        }
        return new Ack(
                409,
                requestId,
                requestId,
                REJECTED,
                "request_id was already used with different content; the earlier record is"
                        + " unchanged",
                true);
    }

    private static boolean sameContent(String stored, String received) {
        try {
            return HrmsPayloads.parse(stored.getBytes(StandardCharsets.UTF_8))
                    .equals(HrmsPayloads.parse(received.getBytes(StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private Ack refusedBeforeStaging(
            HrmsContracts.Request contract, String requestId, String payload, String reason) {
        IntegrationStore.Registered intf = store.interfaceOf(contract.interfaceCode());
        String key = requestId == null ? "unreadable" : requestId;
        long run = intake.open(contract.interfaceCode(), key, now(), now());
        alerts.raise(intf, run, null, payload, "Request refused before staging: " + reason);
        intake.completeCounts(run, 1, 0, 1, IntegrationRules.cut(reason, 500));
        return new Ack(422, requestId, null, REJECTED, reason, false);
    }

    private Ack stageAndApply(
            HrmsApi api,
            HrmsContracts.Request contract,
            List<JsonNode> rows,
            String requestId,
            String payload,
            long employeeId,
            String reprocessOf) {
        long run = intake.open(contract.interfaceCode(), requestId, now(), now());
        InterfaceIntake.Staged staged;
        try {
            staged =
                    intake.stage(
                            run,
                            new InterfaceIntake.Inbound(
                                    contract.txnType(),
                                    requestId,
                                    requestId,
                                    employeeId,
                                    payload,
                                    reprocessOf));
        } catch (DataIntegrityViolationException concurrent) {
            // Another call staged the same request_id between the check and the insert.
            intake.completeCounts(run, 1, 0, 1, "Duplicate request_id: earlier result returned");
            return concurrentAnswer(contract, requestId, payload);
        } catch (FciException refused) {
            String why =
                    reprocessOf != null
                            ? "reprocess_of does not name a rejected request of this type"
                            : reasonOf(refused);
            IntegrationStore.Registered intf = store.interfaceOf(contract.interfaceCode());
            alerts.raise(intf, run, null, payload, "Request refused before staging: " + why);
            intake.completeCounts(run, 1, 0, 1, IntegrationRules.cut(why, 500));
            return new Ack(422, requestId, null, REJECTED, why, false);
        }
        if (staged.outcome() == InterfaceIntake.Outcome.DUPLICATE) {
            intake.completeCounts(run, 1, 0, 1, "Duplicate request_id: earlier result returned");
            return concurrentAnswer(contract, requestId, payload);
        }
        ApplyPlan plan =
                rows.size() == 1 && !contract.arrayBody()
                        ? HrmsPayloads.plan(
                                api,
                                contract,
                                rows.getFirst(),
                                HrmsPayloads.claimTypes(properties.claimType()))
                        : new StageOnly(
                                "accepted for review, no apply rule (owner decision pending):"
                                        + " PENDING API-23-8");
        Ack ack;
        long accepted = 1;
        long rejected = 0;
        if (plan instanceof Change change) {
            try {
                HrmsChanges.Received task =
                        changes.receive(
                                new HrmsChanges.Change(
                                        employeeId,
                                        change.fieldCode(),
                                        change.value(),
                                        change.effectiveDate(),
                                        contract.txnType() + ":" + requestId,
                                        HrmsChanges.Source.HRMS_PUSH));
                intake.applied(staged.stagingId(), "hrms_change_task", task.taskNo());
                ack = new Ack(200, requestId, requestId, ACCEPTED, null, false);
            } catch (FciException refused) {
                String why = reasonOf(refused);
                intake.rejected(staged.stagingId(), why);
                ack = new Ack(422, requestId, requestId, REJECTED, why, false);
                accepted = 0;
                rejected = 1;
            } catch (RuntimeException technical) {
                // The owning module's transaction rolled back: nothing is half applied.
                String why =
                        "Technical failure while applying ("
                                + technical.getClass().getSimpleName()
                                + "); nothing was changed; resend under a new request_id with"
                                + " reprocess_of="
                                + requestId;
                intake.rejected(staged.stagingId(), why);
                ack = new Ack(503, requestId, requestId, REJECTED, why, false);
                accepted = 0;
                rejected = 1;
            }
        } else {
            String why = ((StageOnly) plan).reason();
            event(staged.stagingId(), "NO_APPLY_RULE", why);
            ack = new Ack(202, requestId, requestId, RECEIVED, why, false);
        }
        intake.completeCounts(run, 1, accepted, rejected, null);
        return ack;
    }

    private Ack concurrentAnswer(HrmsContracts.Request contract, String requestId, String payload) {
        Stored earlier = earlier(contract, requestId).orElseThrow();
        if (sameContent(earlier.payload(), payload)) {
            return ack(earlier.stagingStatus(), requestId, earlier.detail(), true);
        }
        return new Ack(
                409,
                requestId,
                requestId,
                REJECTED,
                "request_id was already used with different content; the earlier record is"
                        + " unchanged",
                true);
    }

    static String reasonOf(FciException refused) {
        String detail;
        try {
            detail =
                    MessageFormat.format(
                            refused.getErrorCode().defaultDetail(), refused.getMessageArgs());
        } catch (IllegalArgumentException ex) {
            detail = refused.getErrorCode().defaultTitle();
        }
        return IntegrationRules.cut(refused.getErrorCode().code() + ": " + detail, 500);
    }

    /** The acknowledgement a staging status stands for. */
    static Ack ack(String stagingStatus, String requestId, String detail, boolean duplicate) {
        return switch (stagingStatus) {
            case "APPLIED" -> new Ack(200, requestId, requestId, ACCEPTED, null, duplicate);
            case "REJECTED", "ERROR" ->
                    new Ack(422, requestId, requestId, REJECTED, detail, duplicate);
            case "CANCELLED" -> new Ack(200, requestId, requestId, "Cancelled", detail, duplicate);
            default -> new Ack(202, requestId, requestId, RECEIVED, null, duplicate);
        };
    }

    private void event(long stagingId, String type, String detail) {
        store.insert(
                "intf_staging_event",
                "event_id",
                IntegrationStore.row(
                        "staging_id",
                        stagingId,
                        "event_type",
                        type,
                        "event_detail",
                        IntegrationRules.cut(detail, 2000),
                        "event_by",
                        FailureAlerts.SYSTEM,
                        "event_at",
                        Timestamp.from(clock.instant())));
    }

    private Instant now() {
        return clock.instant();
    }
}
