package in.gov.fci.payroll.integration.orchestration;

import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.employee.api.HrmsRecords;
import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.api.HrmsGateway;
import in.gov.fci.payroll.integration.api.HrmsReply;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * {@link HrmsRecords} through the real {@link HrmsGateway} (SRS-1-70, SRS-INT-1; owner 2026-11-06:
 * a screen fed from HRMS makes the API call). The collection holds two employee reads: {@code
 * get_basic_syncup_status} (basic pay) and {@code get_payroll_statust} (status). Neither is
 * described in a source (PENDING API-16-7), so an answer that does not carry the value is treated
 * as no value and nothing is guessed. No API reads any other field (EMP_BOB, EMP_TRANSFER and the
 * rest are not in the collection, HRMS-PULL-1): a fetch of such a field is reported unavailable, so
 * the HRMS Update Request is recorded FETCH_FAILED and can be fetched again, never closed as "not
 * yet in HRMS".
 */
@Service
public class HrmsRecordReader implements HrmsRecords {

    private static final Logger LOG = LoggerFactory.getLogger(HrmsRecordReader.class);

    /** {@code hrms_pay_effect_field.field_code} of the one field an API reads. */
    static final String BASIC_PAY = "BASIC_PAY";

    /** HRMS's error codes for "it holds no such record" (a 4xx, not a failure). */
    private static final java.util.Set<String> NOT_HELD =
            java.util.Set.of("EMPLOYEE_NOT_FOUND", "NO_SYNC_RECORD", "NO_PAYROLL_DATA");

    private final HrmsGateway gateway;
    private final EmployeeDirectory directory;

    public HrmsRecordReader(HrmsGateway gateway, EmployeeDirectory directory) {
        this.gateway = gateway;
        this.directory = directory;
    }

    @Override
    public Optional<Fetched> current(long employeeId, String fieldCode) {
        if (!BASIC_PAY.equals(fieldCode)) {
            throw new Unavailable("no HRMS API reads field " + fieldCode, null);
        }
        String eis =
                directory
                        .eisOf(employeeId)
                        .orElseThrow(() -> new Unavailable("no employee " + employeeId, null));
        HrmsReply reply;
        try {
            reply = gateway.status(HrmsApi.GET_BASIC_SYNCUP_STATUS, basicQuery(eis));
        } catch (FciException refused) {
            throw new Unavailable(refused.getErrorCode().code(), refused);
        }
        if (reply.outcome() == HrmsReply.Outcome.ACCEPTED) {
            return amount(reply.data(), "hrms_basic")
                    .map(
                            v ->
                                    new Fetched(
                                            v.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                            null));
        }
        if (reply.outcome() == HrmsReply.Outcome.REJECTED && NOT_HELD.contains(reply.errorCode())) {
            return Optional.empty();
        }
        throw new Unavailable("HRMS answered " + reply.outcome(), null);
    }

    /** M-01: both reads; either may fail on its own and the other is still shown. */
    @Override
    public Snapshot snapshot(String empNum) {
        JsonNode basic = read(HrmsApi.GET_BASIC_SYNCUP_STATUS, basicQuery(empNum));
        JsonNode status = read(HrmsApi.GET_PAYROLL_STATUST, Map.of("emp_num", empNum));
        if (basic == null && status == null) {
            return UNAVAILABLE;
        }
        return new Snapshot(
                true,
                amount(basic, "hrms_basic").orElse(null),
                text(status, "emp_status").orElse(null));
    }

    private static Map<String, String> basicQuery(String eis) {
        return Map.of("hist_flag", "N", "emp_num", eis);
    }

    private JsonNode read(HrmsApi api, Map<String, String> query) {
        try {
            HrmsReply reply = gateway.status(api, query);
            return reply.outcome() == HrmsReply.Outcome.ACCEPTED ? reply.data() : null;
        } catch (FciException refused) {
            // Not configured, full bulkhead or still failing after the retries (R-INT): the record
            // shows Payroll's copy and says HRMS could not be read.
            LOG.debug("HRMS read {} refused: {}", api.code(), refused.getErrorCode().code());
            return null;
        }
    }

    private static Optional<String> text(JsonNode data, String field) {
        if (data == null || data.get(field) == null || data.get(field).isNull()) {
            return Optional.empty();
        }
        String value = data.get(field).asString().strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private static Optional<BigDecimal> amount(JsonNode data, String field) {
        return text(data, field)
                .flatMap(
                        v -> {
                            try {
                                return Optional.of(new BigDecimal(v));
                            } catch (NumberFormatException notANumber) {
                                return Optional.empty();
                            }
                        });
    }
}
