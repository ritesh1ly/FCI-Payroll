package in.gov.fci.payroll.integration.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.business.HrmsContracts;
import in.gov.fci.payroll.integration.business.HrmsPayloads;
import in.gov.fci.payroll.integration.business.IntegrationErrorCode;
import in.gov.fci.payroll.integration.business.IntegrationRules;
import in.gov.fci.payroll.platform.error.FciException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The status ({@code get-*}) and cancel ({@code PUT cancel-*}) APIs of the HRMS Postman collection
 * (SRS-INT-31, SRS-INT-35; BR-INT-003; R-DEL, R-PII). A status call answers the status and amount
 * of the HRMS-originated payment named by its parameters, from the staged record: only a record of
 * the transaction types of that API, never another API's, and never a PAN, bank or Aadhaar value
 * (the answer is built field by field, not copied). A cancel call records "cancellation requested"
 * on the staged record once (an event and an audit entry) and changes nothing else: what a
 * cancellation does to a paid or locked item is not stated in the sources (PENDING API-23-13).
 */
@Service
public class HrmsRequestService {

    static final String CANCEL_EVENT = "CANCEL_REQUESTED";

    private final IntegrationStore store;
    private final AuditService audit;
    private final Clock clock;

    public HrmsRequestService(IntegrationStore store, AuditService audit, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    private record Row(
            long stagingId,
            String txnType,
            String requestId,
            String status,
            String detail,
            String payload,
            Timestamp receivedAt) {}

    /** The status of the request the query names (SRS-INT-35). */
    @Transactional(readOnly = true)
    public Map<String, Object> status(HrmsApi api, Map<String, String> query) {
        HrmsContracts.Lookup lookup =
                HrmsContracts.lookup(api)
                        .orElseThrow(() -> new IllegalArgumentException(api.code()));
        String id =
                HrmsPayloads.id(query.get(lookup.idParam()))
                        ? query.get(lookup.idParam()).strip()
                        : null;
        Row row;
        if (lookup.keyedByEmployee()) {
            String emp = query.get("emp_num");
            require(HrmsPayloads.id(emp), "emp_num");
            require(id != null, lookup.idParam());
            row = find(lookup, id, emp.strip());
        } else {
            String request = query.get("request_id");
            require(HrmsPayloads.id(request), "request_id");
            require(id != null, lookup.idParam());
            row = find(lookup, request.strip(), null);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        HrmsInboundService.Ack ack =
                HrmsInboundService.ack(row.status(), row.requestId(), row.detail(), false);
        out.put("request_id", row.requestId());
        out.put("status", cancelled(row.stagingId()) ? "Cancellation requested" : ack.status());
        out.put("staging_status", row.status());
        out.put("reason", ack.reason());
        out.put("amount", amount(row));
        out.put("received_at", row.receivedAt().toInstant().toString());
        return out;
    }

    /** Records a cancellation request against the staged request (idempotent, nothing deleted). */
    @Transactional
    public Map<String, Object> cancel(HrmsApi api, byte[] body) {
        HrmsContracts.Lookup lookup =
                HrmsContracts.lookup(api)
                        .orElseThrow(() -> new IllegalArgumentException(api.code()));
        JsonNode json;
        try {
            json = HrmsPayloads.parse(body);
            if (!json.isObject()) {
                throw new IllegalArgumentException("the body must be one JSON object");
            }
            for (String name : json.propertyNames()) {
                if (!name.equals("request_id") && !name.equals(lookup.idParam())) {
                    throw new IllegalArgumentException("unknown field");
                }
            }
        } catch (IllegalArgumentException bad) {
            throw new FciException(
                    IntegrationErrorCode.HRMS_MALFORMED,
                    new Object[] {bad.getMessage()},
                    List.of(),
                    null);
        }
        String request = HrmsPayloads.text(json.get("request_id"));
        require(HrmsPayloads.id(request), "request_id");
        require(HrmsPayloads.id(HrmsPayloads.text(json.get(lookup.idParam()))), lookup.idParam());
        Row row = find(lookup, request.strip(), null);
        boolean repeat = cancelled(row.stagingId());
        if (!repeat) {
            store.insert(
                    "intf_staging_event",
                    "event_id",
                    IntegrationStore.row(
                            "staging_id",
                            row.stagingId(),
                            "event_type",
                            CANCEL_EVENT,
                            "event_detail",
                            "HRMS asked to cancel; recorded only, nothing changed"
                                    + " (rule pending: PENDING API-23-13)",
                            "event_by",
                            FailureAlerts.SYSTEM,
                            "event_at",
                            Timestamp.from(clock.instant())));
            audit.record(
                    FailureAlerts.SYSTEM,
                    AuditService.Entry.change(
                            "intf_staging_txn",
                            String.valueOf(row.stagingId()),
                            "cancellation",
                            null,
                            "REQUESTED",
                            "HRMS cancellation request on " + row.txnType()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("request_id", row.requestId());
        out.put("status", "Cancellation requested");
        out.put("staging_status", row.status());
        out.put("duplicate", repeat);
        out.put(
                "message",
                "Cancellation recorded; the record is not changed (paid or locked items:"
                        + " rule pending)");
        return out;
    }

    private void require(boolean ok, String parameter) {
        if (!ok) {
            throw new FciException(
                    IntegrationErrorCode.RECORD,
                    new Object[] {parameter + " is required (letters, digits, . _ -, up to 30)"},
                    List.of(),
                    null);
        }
    }

    /** The staged record of one of the API's types with that request_id (and employee). */
    private Row find(HrmsContracts.Lookup lookup, String requestId, String empNum) {
        String in = String.join(",", java.util.Collections.nCopies(lookup.txnTypes().size(), "?"));
        StringBuilder sql =
                new StringBuilder(
                        "SELECT s.staging_id, s.txn_type, s.request_id, s.staging_status,"
                            + " s.error_detail, s.payload, s.received_at FROM intf_staging_txn s");
        if (empNum != null) {
            sql.append(" JOIN employee e ON e.employee_id = s.employee_id");
        }
        sql.append(" WHERE s.request_id = ? AND s.txn_type IN (").append(in).append(")");
        if (empNum != null) {
            sql.append(" AND e.emp_num = ?");
        }
        sql.append(" ORDER BY s.staging_id");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(requestId);
        args.addAll(lookup.txnTypes());
        if (empNum != null) {
            args.add(empNum);
        }
        List<Row> rows =
                store.jdbc()
                        .query(
                                sql.toString(),
                                (rs, i) ->
                                        new Row(
                                                rs.getLong(1),
                                                rs.getString(2),
                                                rs.getString(3),
                                                rs.getString(4),
                                                rs.getString(5),
                                                rs.getString(6),
                                                rs.getTimestamp(7)),
                                args.toArray());
        if (rows.isEmpty()) {
            throw new FciException(
                    IntegrationErrorCode.HRMS_REQUEST_NOT_FOUND,
                    new Object[] {requestId},
                    List.of(),
                    null);
        }
        return rows.getFirst();
    }

    private boolean cancelled(long stagingId) {
        Integer n =
                store.jdbc()
                        .queryForObject(
                                "SELECT COUNT(*) FROM intf_staging_event WHERE staging_id = ? AND"
                                        + " event_type = ?",
                                Integer.class,
                                stagingId,
                                CANCEL_EVENT);
        return n != null && n > 0;
    }

    /** The amount as received (text), read from the staged payload by the type's amount field. */
    private static String amount(Row row) {
        Optional<HrmsContracts.Request> contract = HrmsContracts.byTxnType(row.txnType());
        if (contract.isEmpty() || contract.get().amountField() == null || row.payload() == null) {
            return null;
        }
        try {
            JsonNode node =
                    HrmsPayloads.parse(
                            row.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return node.isObject()
                    ? IntegrationRules.cut(HrmsPayloads.amountOf(contract.get(), node), 40)
                    : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
