package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Module 17 (plan docs/plan/17-increment-pay-fixation.md) on the demo members 109991 (E-4, stages
 * configured) and 109992 (E-2, no stages, no increment rule; seed V049_3_9): the I-05 Increment Due
 * list with its proposals and flags (SRS-13-21, SRS-13-23, D17-4), the run submitted by
 * Establishment / HR and approved by the Payroll Checker into the Basic history (SRS-13-1,
 * SRS-13-26), an individual revision worked out in Payroll with its order (SRS-13-18, SRS-13-20),
 * an increment-date correction with OTP (SRS-13-24), the M-03 scale version under maker-checker at
 * Headquarters, and the I-25 bulk fixation of an approved revision (SRS-8-25 steps 5 - 8).
 */
class IncrementIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @org.springframework.beans.factory.annotation.Autowired
    private in.gov.fci.payroll.payroll.api.PendingSalary pending;

    private long employee(String eis) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE emp_num = ? AND parent_zone_id = (SELECT"
                        + " zone_id FROM org_zone WHERE zone_code = 'NA11')",
                Long.class,
                eis);
    }

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private Session hr() throws Exception {
        return login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
    }

    private Session checker() throws Exception {
        return login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
    }

    private long circular() {
        return circular("DA");
    }

    /** An Active all-office circular of {@code category} (I-25 takes WAGE_REVISION only). */
    private long circular(String category) {
        String number = "IT/INC/" + System.nanoTime();
        jdbc.update(
                "INSERT INTO document_attachment (entity_name, entity_key, file_name,"
                        + " storage_provider, storage_object_key, contains_pii, uploaded_by) VALUES"
                        + " ('circular_order', ?, 'it.pdf', 'ORACLE_SECUREFILE', ?, 'N', 'it')",
                number,
                "it/circular/" + number);
        jdbc.update(
                "INSERT INTO circular_order (circular_number, circular_date, subject,"
                        + " category_code, applies_all_offices, effective_from,"
                        + " document_attachment_id, circular_status, created_by) SELECT ?, ?, 'Pay"
                        + " scale (IT)', ?, 'Y', ?, attachment_id, 'ACTIVE', 'it.fixture' FROM"
                        + " document_attachment WHERE storage_object_key = ?",
                number,
                java.sql.Date.valueOf(LocalDate.now(IST)),
                category,
                java.sql.Date.valueOf(LocalDate.of(2026, 4, 1)),
                "it/circular/" + number);
        return jdbc.queryForObject(
                "SELECT circular_order_id FROM circular_order WHERE circular_number = ?",
                Long.class,
                number);
    }

    /** Stores the case's order copy as the screen does and names it in the case (SRS-13-18). */
    private void withOrder(Session s, Map<String, Object> body) throws Exception {
        org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder r =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                        "/v1/pay-fixation-cases/orders");
        r.file(
                new org.springframework.mock.web.MockMultipartFile(
                        "file",
                        "order.pdf",
                        "application/pdf",
                        "%PDF-1.7 increment order"
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        r.param("eis", (String) body.get("eis"))
                .param("documentType", "OTHER")
                .param("reference", String.valueOf(body.get("orderNumber")))
                .param("orderDate", String.valueOf(body.get("orderDate")))
                .param("issuingAuthority", String.valueOf(body.get("issuingAuthority")));
        MvcResult stored =
                mvc.perform(
                                userRequest(s, r)
                                        .header(
                                                "Idempotency-Key",
                                                java.util.UUID.randomUUID().toString()))
                        .andReturn();
        assertThat(stored.getResponse().getStatus())
                .as(stored.getResponse().getContentAsString())
                .isEqualTo(201);
        body.put("orderAttachmentId", body(stored).get("attachmentId").asLong());
        body.put("orderProvisionalKey", body(stored).get("provisionalKey").asString());
    }

    // SC-17-0037: UC Run Annual Increment and Individual Pay Fixation (I-05), main flow.
    @Test
    void SRS_13_21_theDueListProposesTheNextStageAndTheApprovedIncrementIsAppliedOnItsDueDate()
            throws Exception {
        long vikram = employee("109991");
        long meera = employee("109992");
        Session hr = hr();
        YearMonth month = YearMonth.now(IST);
        JsonNode due = all(hr, "/v1/increments/due?month=" + month);
        JsonNode v = null;
        JsonNode m = null;
        for (JsonNode row : due) {
            if (row.path("employeeId").asLong() == vikram) {
                v = row;
            } else if (row.path("employeeId").asLong() == meera) {
                m = row;
            }
        }
        assertThat(v).as("109991 is due this month").isNotNull();
        assertThat(v.path("revisedBasic").decimalValue()).isEqualByComparingTo("64300");
        // I-05 l.4001-4002 (SC-17-0047, SC-17-0059): the HRMS pay scale and the next due date.
        assertThat(v.path("payScaleCode").asString()).isNotBlank();
        assertThat(LocalDate.parse(v.path("nextDueDate").asString()))
                .isEqualTo(LocalDate.parse(v.path("dueDate").asString()).plusYears(1));
        assertThat(m).isNotNull();
        assertThat(m.path("flags").toString()).contains("NOT_CONFIGURED");

        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        Map<String, Object> run =
                Map.of("employeeIds", List.of(vikram, meera), "remark", "Increment run (IT)");
        assertThat(post(maker, "/v1/increments/submissions", run).getResponse().getStatus())
                .as("only Establishment / HR raises the run")
                .isEqualTo(403);
        MvcResult submitted = post(hr, "/v1/increments/submissions", run);
        assertThat(submitted.getResponse().getStatus())
                .as(submitted.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode outcome = body(submitted);
        assertThat(outcome.path("approvalIds")).hasSize(1);
        assertThat(outcome.path("refused").get(0).path("eis").asString()).isEqualTo("109992");
        assertThat(outcome.path("refused").get(0).path("reason").asString())
                .contains("NOT_CONFIGURED");
        long approval = outcome.path("approvalIds").get(0).asLong();
        assertThat(decide(hr, approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(200);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT current_basic FROM employee WHERE employee_id = ?",
                                BigDecimal.class,
                                vikram))
                .isEqualByComparingTo("64300");
        Map<String, Object> cycle =
                jdbc.queryForMap(
                        "SELECT increment_type, effective_date, next_due_date, basic_history_id"
                                + " FROM pay_increment_cycle WHERE employee_id = ?",
                        vikram);
        assertThat(cycle.get("INCREMENT_TYPE")).isEqualTo("ANNUAL");
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT effective_date FROM pay_increment_cycle WHERE"
                                                + " employee_id = ?",
                                        java.sql.Date.class,
                                        vikram)
                                .toLocalDate())
                .isEqualTo(month.atDay(1));
        assertThat(cycle.get("BASIC_HISTORY_ID")).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT next_due_date FROM employee_increment_due WHERE"
                                                + " employee_id = ?",
                                        java.sql.Date.class,
                                        vikram)
                                .toLocalDate())
                .isEqualTo(month.atDay(1).plusYears(1));
        JsonNode history =
                body(mvc.perform(userGet(hr, "/v1/basic-history?eis=109991")).andReturn());
        JsonNode increment = null;
        for (JsonNode h : history) {
            if ("INCREMENT".equals(h.path("event").asString())) {
                increment = h;
            }
        }
        assertThat(increment).as("the increment in the Basic history").isNotNull();
        assertThat(increment.path("oldBasic").decimalValue()).isEqualByComparingTo("62400");
        // SRS-13-26 (US-GAP-55): the event names its Maker (the run's submitter) and Checker.
        assertThat(increment.path("maker").asString()).isEqualTo("hr.delhi@fci-demo.test");
        // SC-17-0046 (l.3974): the old Basic kept with the event name and its Checker.
        assertThat(increment.path("by").asString()).isEqualTo("ddo.delhi@fci-demo.test");
        assertThat(increment.path("basic").decimalValue()).isEqualByComparingTo("64300");
        // SC-17-0045 (l.3973): the next computation run takes the revised Basic.
        assertThat(
                        pending.compute(vikram, month.plusMonths(1)).lines().stream()
                                .filter(l -> l.payCode() == 101)
                                .findFirst()
                                .orElseThrow()
                                .amount())
                .isEqualByComparingTo("64300");
        assertThat(all(hr, "/v1/increments/due?month=" + month).findValuesAsString("eis"))
                .as("applied: off the due list")
                .doesNotContain("109991");
        assertThat(
                        mvc.perform(userGet(hr, "/v1/basic-history/report?eis=109991&format=CSV"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        assertThat(
                        mvc.perform(
                                        userGet(
                                                hr,
                                                "/v1/increments/tracking?month="
                                                        + month
                                                        + "&format=CSV"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        // UI audit #29 (SRS-13-21): the tracking export lists every member the due list shows
        // for the month, the overdue ones included.
        String csv = csv(hr, "/v1/increments/tracking?month=" + month + "&format=CSV");
        for (String listed :
                all(hr, "/v1/increments/due?month=" + month).findValuesAsString("eis")) {
            assertThat(csv).as("member %s of the due list", listed).contains(listed);
        }
    }

    private Map<String, Object> fixationCase(String type, LocalDate effective) {
        Map<String, Object> body = new HashMap<>();
        body.put("eis", "109992");
        body.put("caseType", type);
        body.put("effectiveDate", effective.toString());
        body.put("orderNumber", "EST/IT/17");
        body.put("orderDate", LocalDate.now(IST).minusDays(2).toString());
        body.put("issuingAuthority", "General Manager (P), Delhi");
        body.put("reason", "Worked out in Payroll (IT)");
        return body;
    }

    /**
     * SRS-13-27 and SRS-13-24 (US-GAP-54, US-GAP-50): a past Basic refixed on an Audit observation
     * keeps the observation on the correction record, then the wrong entry is reversed; both take
     * the Checker's OTP, and the member ends on the Basic before them with both entries kept.
     */
    @Test
    void SRS_13_27_anObservationRefixationAndItsReversalTakeTheCheckersOtp() throws Exception {
        // A Delhi member on a stage of a staged scale with a higher stage free, not the members
        // the other cases of this class change.
        Map<String, Object> m =
                jdbc
                        .queryForList(
                                "SELECT e.employee_id, e.emp_num, e.current_basic, (SELECT"
                                        + " MIN(s2.stage_basic) FROM pay_scale_band_stage s2"
                                        + " WHERE s2.band_version_id = v.band_version_id AND"
                                        + " s2.stage_basic > e.current_basic) AS next_stage FROM"
                                        + " employee e JOIN pay_scale_band_version v ON v.band_id ="
                                        + " e.band_id AND v.effective_from <= ? AND (v.effective_to"
                                        + " IS NULL OR v.effective_to >= ?) JOIN"
                                        + " pay_scale_band_stage s ON s.band_version_id ="
                                        + " v.band_version_id AND s.stage_basic ="
                                        + " e.current_basic WHERE e.location_id = ? AND"
                                        + " e.record_status = 'ACTIVE' AND e.emp_lbr_flag = 'N' AND"
                                        + " e.employee_type = 'STAFF' AND e.emp_num NOT IN"
                                        + " ('109991', '109992') ORDER BY e.emp_num",
                                java.sql.Date.valueOf(LocalDate.now(IST).minusMonths(1)),
                                java.sql.Date.valueOf(LocalDate.now(IST)),
                                locationId("DH001"))
                        .stream()
                        .filter(r -> r.get("NEXT_STAGE") != null)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no staged Delhi member"));
        long employee = ((Number) m.get("EMPLOYEE_ID")).longValue();
        String eis = (String) m.get("EMP_NUM");
        BigDecimal before = (BigDecimal) m.get("CURRENT_BASIC");
        BigDecimal refixed = (BigDecimal) m.get("NEXT_STAGE");
        LocalDate from = LocalDate.now(IST).minusMonths(1).withDayOfMonth(1);
        Session hr = hr();

        Map<String, Object> observed = fixationCase("OBSERVATION_REFIXATION", from);
        observed.put("eis", eis);
        observed.put("basic", refixed);
        assertThat(code(post(hr, "/v1/pay-fixation-cases/previews", observed)))
                .as("SRS-13-27: the observation is named")
                .isEqualTo("INC-422-ENTRY");
        observed.put("observationSource", "INSPECTION");
        assertThat(post(hr, "/v1/pay-fixation-cases/previews", observed).getResponse().getStatus())
                .isEqualTo(400);
        observed.put("observationSource", "AUDIT");
        observed.put("observationReference", "AUD/IT/4");
        withOrder(hr, observed);
        MvcResult raised = post(hr, "/v1/pay-fixation-cases", observed);
        assertThat(raised.getResponse().getStatus())
                .as(raised.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(raised).get("approvalId").asLong();
        assertThat(decide(checker(), approval, "APPROVE", null, null))
                .as("OTP first")
                .isEqualTo(428);
        assertThat(decide(checker(), approval, "APPROVE", null, "INCREMENT_DATE_BASIC_CORRECTION"))
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT current_basic FROM employee WHERE employee_id = ?",
                                BigDecimal.class,
                                employee))
                .isEqualByComparingTo(refixed);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT correction_reason FROM increment_correction WHERE"
                                        + " employee_id = ? AND correction_type = 'BASIC_PAY'",
                                String.class,
                                employee))
                .startsWith("Audit observation AUD/IT/4");

        // The refixation turns out wrong: it is reversed from its own date.
        Map<String, Object> reverse = fixationCase("REVERSE_ENTRY", from.plusDays(1));
        reverse.put("eis", eis);
        assertThat(code(post(hr, "/v1/pay-fixation-cases/previews", reverse)))
                .as("the date names the latest entry")
                .isEqualTo("INC-422-ENTRY");
        reverse.put("effectiveDate", from.toString());
        JsonNode preview = body(post(hr, "/v1/pay-fixation-cases/previews", reverse));
        assertThat(preview.path("revisedBasic").decimalValue()).isEqualByComparingTo(before);
        withOrder(hr, reverse);
        MvcResult reversal = post(hr, "/v1/pay-fixation-cases", reverse);
        assertThat(reversal.getResponse().getStatus())
                .as(reversal.getResponse().getContentAsString())
                .isEqualTo(202);
        long second = body(reversal).get("approvalId").asLong();
        assertThat(decide(checker(), second, "APPROVE", null, "INCREMENT_DATE_BASIC_CORRECTION"))
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT current_basic FROM employee WHERE employee_id = ?",
                                BigDecimal.class,
                                employee))
                .isEqualByComparingTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM increment_correction WHERE employee_id = ?"
                                    + " AND correction_type = 'REVERSE_ENTRY' AND correction_status"
                                    + " = 'APPROVED' AND otp_challenge_id IS NOT NULL",
                                Integer.class,
                                employee))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM employee_basic_history WHERE employee_id = ?"
                                        + " AND effective_from = ?",
                                Integer.class,
                                employee,
                                java.sql.Date.valueOf(from)))
                .as("nothing is deleted: both entries stay")
                .isEqualTo(2);
    }

    @Test
    void SRS_13_24_aRevisionWithItsOrderAndADateCorrectionWithOtpTakeTheCheckersApproval()
            throws Exception {
        long meera = employee("109992");
        Session hr = hr();
        LocalDate twoBack = LocalDate.now(IST).minusMonths(2).withDayOfMonth(1);
        Map<String, Object> revision = fixationCase("INDIVIDUAL_REVISION", twoBack);
        revision.put("basic", 52000);
        Map<String, Object> noOrder = new HashMap<>(revision);
        noOrder.put("orderNumber", " ");
        assertThat(code(post(hr, "/v1/pay-fixation-cases", noOrder)))
                .as("SRS-13-18: the order is mandatory")
                .isEqualTo("INC-422-ENTRY");
        Map<String, Object> tooHigh = new HashMap<>(revision);
        tooHigh.put("basic", 999999);
        assertThat(code(post(hr, "/v1/pay-fixation-cases", tooHigh))).isEqualTo("INC-422-FLAGGED");
        JsonNode preview = body(post(hr, "/v1/pay-fixation-cases/previews", revision));
        assertThat(preview.path("oldBasic").decimalValue()).isEqualByComparingTo("50000");
        // SRS-13-18 (US-GAP-10): not without the order copy; with it, the case goes ahead.
        assertThat(code(post(hr, "/v1/pay-fixation-cases", revision))).isEqualTo("INC-422-ENTRY");
        withOrder(hr, revision);
        MvcResult raised = post(hr, "/v1/pay-fixation-cases", revision);
        assertThat(raised.getResponse().getStatus())
                .as(raised.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(
                        decide(
                                checker(),
                                body(raised).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT current_basic FROM employee WHERE employee_id = ?",
                                BigDecimal.class,
                                meera))
                .isEqualByComparingTo("52000");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM pay_fixation_event WHERE employee_id = ? AND"
                                        + " is_historical_only = 'N'",
                                Integer.class,
                                meera))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM arrear_event WHERE source_module ="
                                        + " 'INCREMENT' AND source_entity = 'pay_fixation_event'",
                                Integer.class))
                .as("SRS-13-4: a past effective date raises the arrear")
                .isPositive();
        // SC-17-0046 (l.3974): the old Basic is kept in the history with the event, the order,
        // the Maker and the Checker.
        JsonNode revised = null;
        for (JsonNode h :
                body(mvc.perform(userGet(hr, "/v1/basic-history?eis=109992")).andReturn())) {
            if (h.path("basic").decimalValue().compareTo(new BigDecimal("52000")) == 0) {
                revised = h;
            }
        }
        assertThat(revised).as("the revision in the Basic history").isNotNull();
        assertThat(revised.path("event").asString()).isNotBlank();
        assertThat(revised.path("oldBasic").decimalValue()).isEqualByComparingTo("50000");
        assertThat(revised.path("order").asString()).contains("EST/IT/17");
        assertThat(revised.path("maker").asString()).isEqualTo("hr.delhi@fci-demo.test");
        assertThat(revised.path("by").asString()).isEqualTo("ddo.delhi@fci-demo.test");

        Map<String, Object> date = fixationCase("DATE_CORRECTION", LocalDate.now(IST));
        // The month's first day: the member stays on the due list the run test reads.
        LocalDate corrected = YearMonth.now(IST).atDay(1);
        date.put("correctedDueDate", corrected.toString());
        withOrder(hr, date);
        MvcResult correction = post(hr, "/v1/pay-fixation-cases", date);
        assertThat(correction.getResponse().getStatus())
                .as(correction.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(correction).get("approvalId").asLong();
        assertThat(decide(checker(), approval, "APPROVE", null, null))
                .as("OTP first")
                .isEqualTo(428);
        // SRS-13-24 / SRS-G-22 (R-OTP): the right code after its challenge expired is refused and
        // the correction stays pending; a fresh code approves it.
        Map.Entry<MvcResult, Long> late =
                decideAfterOtpExpiry(
                        checker(), approval, "APPROVE", null, "INCREMENT_DATE_BASIC_CORRECTION");
        assertThat(late.getKey().getResponse().getStatus()).isEqualTo(400);
        assertThat(code(late.getKey())).isEqualTo("AUTH-400-OTP");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT outcome FROM otp_challenge WHERE otp_challenge_id = ?",
                                String.class,
                                late.getValue()))
                .isEqualTo("EXPIRED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                approval))
                .isEqualTo("PENDING_APPROVAL");
        assertThat(decide(checker(), approval, "APPROVE", null, "INCREMENT_DATE_BASIC_CORRECTION"))
                .isEqualTo(200);
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT correction_status, otp_challenge_id FROM increment_correction WHERE"
                                + " employee_id = ? AND correction_type = 'INCREMENT_DATE'",
                        meera);
        assertThat(row.get("CORRECTION_STATUS")).isEqualTo("APPROVED");
        assertThat(row.get("OTP_CHALLENGE_ID")).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT next_due_date FROM employee_increment_due WHERE"
                                                + " employee_id = ?",
                                        java.sql.Date.class,
                                        meera)
                                .toLocalDate())
                .isEqualTo(corrected);
    }

    /**
     * A member of this test alone (EIS 109993, copied from 109991) whose annual increment was given
     * on {@code given}: the cycle row and the due row a year on, as the I-05 run writes them.
     */
    private long memberWithAnIncrementGivenOn(LocalDate given) {
        jdbc.update(
                "INSERT INTO employee (emp_num, first_name, last_name, birth_date, gender,"
                        + " comp_joining_date, employee_type, emp_lbr_flag, pan_number,"
                        + " designation_id, grade_id, location_id, department_id, band_id,"
                        + " pay_pattern, pay_pattern_source, provident_fund_type, cpf_code,"
                        + " cpf_code_origin, parent_zone_id, employment_status, record_status,"
                        + " current_basic, mobile_number, email_address, bank_account_number,"
                        + " bank_branch_id, record_origin) SELECT '109993', 'Asha', 'Tandon',"
                        + " birth_date, gender, comp_joining_date, employee_type, emp_lbr_flag,"
                        + " 'ABCPT2003S', designation_id, grade_id, location_id, department_id,"
                        + " band_id, pay_pattern, pay_pattern_source, provident_fund_type,"
                        + " 'CPF/109993', cpf_code_origin, parent_zone_id, employment_status,"
                        + " record_status, 62400, '9000029993', 'asha.tandon@fci-demo.test',"
                        + " '0000009993', bank_branch_id, record_origin FROM employee WHERE"
                        + " emp_num = '109991'");
        long asha = employee("109993");
        jdbc.update(
                "INSERT INTO employee_basic_history (employee_id, basic_amount, band_id,"
                        + " event_type, effective_from, event_sequence, effective_to, source_code,"
                        + " reason, changed_by) SELECT employee_id, 60000, band_id,"
                        + " 'MIGRATION_OPENING', comp_joining_date, 1, ?, 'SYS', 'Opening Basic"
                        + " (IT)', 'it.fixture' FROM employee WHERE employee_id = ?",
                java.sql.Date.valueOf(given.minusDays(1)),
                asha);
        // The increment's own history row, as the I-05 run links it (SRS-13-24: the old entry).
        jdbc.update(
                "INSERT INTO employee_basic_history (employee_id, basic_amount, old_basic_amount,"
                        + " band_id, event_type, effective_from, event_sequence, source_code,"
                        + " reason, changed_by) SELECT employee_id, 62400, 60000, band_id,"
                        + " 'INCREMENT', ?, 1, 'CALC', 'Annual increment (IT)', 'it.fixture' FROM"
                        + " employee WHERE employee_id = ?",
                java.sql.Date.valueOf(given),
                asha);
        jdbc.update(
                "INSERT INTO pay_increment_cycle (employee_id, due_period, increment_type,"
                        + " increment_due_date, next_due_date, effective_date, old_basic,"
                        + " revised_basic, basic_history_id) SELECT ?, ?, 'ANNUAL', ?, ?, ?, 60000,"
                        + " 62400, history_id FROM employee_basic_history WHERE employee_id = ?"
                        + " AND event_type = 'INCREMENT'",
                asha,
                in.gov.fci.payroll.increment.business.IncrementRules.duePeriod(given),
                java.sql.Date.valueOf(given),
                java.sql.Date.valueOf(given.plusYears(1)),
                java.sql.Date.valueOf(given),
                asha);
        jdbc.update(
                "INSERT INTO employee_increment_due (employee_id, next_due_date, source_code,"
                        + " updated_by) VALUES (?, ?, 'HRMS', 'it.fixture')",
                asha,
                java.sql.Date.valueOf(given.plusYears(1)));
        return asha;
    }

    @Test
    void SRS_13_24_anIncrementGivenOnTheWrongDateIsReappliedFromTheCorrectedOne() throws Exception {
        LocalDate given = YearMonth.now(IST).minusMonths(6).atDay(1);
        LocalDate corrected = given.minusMonths(1);
        long asha = memberWithAnIncrementGivenOn(given);
        Session hr = hr();
        Map<String, Object> date = fixationCase("DATE_CORRECTION", LocalDate.now(IST));
        date.put("eis", "109993");
        date.put("correctedDueDate", corrected.toString());
        withOrder(hr, date);
        MvcResult correction = post(hr, "/v1/pay-fixation-cases", date);
        assertThat(correction.getResponse().getStatus())
                .as(correction.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(correction).get("approvalId").asLong();
        assertThat(decide(checker(), approval, "APPROVE", null, "INCREMENT_DATE_BASIC_CORRECTION"))
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM employee_basic_history WHERE employee_id = ?"
                                        + " AND effective_from = ? AND basic_amount = 62400 AND"
                                        + " event_type <> 'MIGRATION_OPENING'",
                                Integer.class,
                                asha,
                                java.sql.Date.valueOf(corrected)))
                .as("SRS-13-24: the last increment re-applied from the corrected date")
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT next_due_date FROM employee_increment_due WHERE"
                                                + " employee_id = ?",
                                        java.sql.Date.class,
                                        asha)
                                .toLocalDate())
                .as("the next due date a year on from the corrected one")
                .isEqualTo(corrected.plusYears(1));
        JsonNode history =
                body(mvc.perform(userGet(hr, "/v1/basic-history?eis=109993")).andReturn());
        java.util.Map<String, Boolean> marked = new java.util.HashMap<>();
        history.forEach(
                h ->
                        marked.put(
                                h.path("event").asString()
                                        + "@"
                                        + h.path("effectiveFrom").asString(),
                                h.path("corrected").asBoolean()));
        assertThat(marked)
                .as("SRS-13-24: the increment of the wrong date stays, marked as corrected")
                .containsEntry("INCREMENT@" + given, true)
                .containsEntry("CORRECTION@" + corrected, false);
        assertThat(marked.values().stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
    }

    @Test
    // SC-17-0027: UC Maintain Pay Scales & Grades (M-03), main flow.
    void M_03_aScaleVersionIsProposedAtHeadquartersAndInForceOnlyOnceApproved() throws Exception {
        Session admin = login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
        Session hqChecker = login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
        LocalDate from = LocalDate.now(IST).plusMonths(4).withDayOfMonth(1);
        Map<String, Object> body = new HashMap<>();
        body.put("min", 41000);
        body.put("max", 145000);
        body.put("stagnationAmount", 1300);
        body.put("effectiveFrom", from.toString());
        body.put("circularId", circular());
        body.put("stages", List.of(41000, 42300, 43600, 145000));
        body.put("reason", "Pay scale revision (IT)");
        // SRS M-03 Wage Revision: the version names the staff revision it belongs to.
        String revision = "WR-IT-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        jdbc.update(
                "INSERT INTO wage_revision (revision_code, revision_name, circular_order_id,"
                        + " payroll_types, defined_location_id, created_by) VALUES (?, ?, ?, ?, ?,"
                        + " 'it')",
                revision,
                "IT staff revision",
                circular(),
                "STAFF_IDA,STAFF_CDA",
                locationId("HQ001"));
        assertThat(code(post(admin, "/v1/pay-scales/E-2/versions", body)))
                .as("the wage revision is mandatory")
                .isEqualTo("INC-422-ENTRY");
        body.put("wageRevision", "WR-IT-NONE");
        assertThat(code(post(admin, "/v1/pay-scales/E-2/versions", body)))
                .isEqualTo("INC-422-ENTRY");
        body.put("wageRevision", revision);
        assertThat(
                        body(mvc.perform(userGet(admin, "/v1/pay-scales/wage-revisions"))
                                        .andReturn())
                                .findValuesAsString("code"))
                .contains(revision);
        Map<String, Object> outside = new HashMap<>(body);
        outside.put("stages", List.of(40000));
        assertThat(code(post(admin, "/v1/pay-scales/E-2/versions", outside)))
                .isEqualTo("INC-422-ENTRY");
        assertThat(
                        post(
                                        login(
                                                "maker.delhi@fci-demo.test",
                                                "PAYROLL_MAKER",
                                                "DH001"),
                                        "/v1/pay-scales/E-2/versions",
                                        body)
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        MvcResult proposed = post(admin, "/v1/pay-scales/E-2/versions", body);
        assertThat(proposed.getResponse().getStatus())
                .as(proposed.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(proposed).get("approvalId").asLong();
        assertThat(code(post(admin, "/v1/pay-scales/E-2/versions", body)))
                .as("a version starts after every existing one")
                .isEqualTo("INC-422-ENTRY");
        assertThat(decide(admin, approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(hqChecker, approval, "APPROVE", null, null)).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT v.effective_to FROM pay_scale_band_version v JOIN"
                                            + " pay_scale_band b ON b.band_id = v.band_id WHERE"
                                            + " b.band_code = 'E-2' AND v.effective_from < ? ORDER"
                                            + " BY v.effective_from DESC FETCH FIRST 1 ROWS ONLY",
                                        java.sql.Date.class,
                                        java.sql.Date.valueOf(from))
                                .toLocalDate())
                .isEqualTo(from.minusDays(1));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT v.pay_commission_ref FROM pay_scale_band_version v JOIN"
                                        + " pay_scale_band b ON b.band_id = v.band_id WHERE"
                                        + " b.band_code = 'E-2' AND v.effective_from = ?",
                                String.class,
                                java.sql.Date.valueOf(from)))
                .isEqualTo(revision);
        assertThat(all(admin, "/v1/pay-scales").findValuesAsString("code")).contains("E-2", "E-4");
        assertThat(body(mvc.perform(userGet(admin, "/v1/pay-scales/E-4/stages")).andReturn()))
                .isNotEmpty();
    }

    /**
     * SC-17-0030 (US-GAP-56): Headquarters defines a new scale with a unique code, its name, type
     * and first version; it is inactive (not offered to HRMS records) until the Payroll Checker
     * approves that version; a unit office, a Maker approving the own item and a taken code are
     * refused.
     */
    @Test
    void SC_17_0030_aNewScaleIsDefinedAtHeadquartersAndActiveOnceItsFirstVersionIsApproved()
            throws Exception {
        Session admin = login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
        Session hqChecker = login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
        String revision = "WR-IT-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        jdbc.update(
                "INSERT INTO wage_revision (revision_code, revision_name, circular_order_id,"
                        + " payroll_types, defined_location_id, created_by) VALUES (?, ?, ?, ?, ?,"
                        + " 'it')",
                revision,
                "IT CDA revision",
                circular(),
                "STAFF_CDA",
                locationId("HQ001"));
        String code = "C" + (System.nanoTime() % 100_000_000L);
        Map<String, Object> version = new HashMap<>();
        version.put("min", 30000);
        version.put("max", 90000);
        version.put("effectiveFrom", LocalDate.now(IST).plusMonths(2).withDayOfMonth(1).toString());
        version.put("circularId", circular());
        version.put("stages", List.of(30000, 31000, 90000));
        version.put("reason", "New CDA scale (IT)");
        version.put("wageRevision", revision);
        Map<String, Object> scale = new HashMap<>();
        scale.put("code", code);
        scale.put("name", "CDA IT level");
        scale.put("scaleType", "CDA");
        scale.put("version", version);

        assertThat(
                        post(
                                        login(
                                                "maker.delhi@fci-demo.test",
                                                "PAYROLL_MAKER",
                                                "DH001"),
                                        "/v1/pay-scales",
                                        scale)
                                .getResponse()
                                .getStatus())
                .as("no M-03 CREATE")
                .isEqualTo(403);
        Map<String, Object> fnd = new HashMap<>(scale);
        fnd.put("scaleType", "FND");
        assertThat(post(admin, "/v1/pay-scales", fnd).getResponse().getStatus())
                .as("IDA or CDA only")
                .isEqualTo(400);
        MvcResult defined = post(admin, "/v1/pay-scales", scale);
        assertThat(defined.getResponse().getStatus())
                .as(defined.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT active_flag FROM pay_scale_band WHERE band_code = ?",
                                String.class,
                                code))
                .isEqualTo("N");
        Map<String, Object> again = new HashMap<>(scale);
        again.put("code", code.toLowerCase(java.util.Locale.ROOT));
        assertThat(code(post(admin, "/v1/pay-scales", again)))
                .as("the scale code is unique")
                .isEqualTo("INC-409-SCALE-CODE");
        long approval = body(defined).get("approvalId").asLong();
        assertThat(decide(admin, approval, "APPROVE", null, null))
                .as("the Maker cannot approve the own item")
                .isEqualTo(403);
        assertThat(decide(hqChecker, approval, "APPROVE", null, null)).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT active_flag FROM pay_scale_band WHERE band_code = ?",
                                String.class,
                                code))
                .isEqualTo("Y");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT b.scale_type || '/' || b.band_name || '/' ||"
                                        + " v.pay_commission_ref FROM pay_scale_band b JOIN"
                                        + " pay_scale_band_version v ON v.band_id = b.band_id"
                                        + " WHERE b.band_code = ?",
                                String.class,
                                code))
                .isEqualTo("CDA/CDA IT level/" + revision);
    }

    /** SRS-8-25 steps 5 - 8, with a future effective date so no current Basic changes. */
    @Test
    void SRS_8_25_anApprovedRevisionIsFixedInBulkWithItsExceptions() throws Exception {
        Session admin = login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001");
        Session hqChecker = login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
        Map<String, Object> def = new HashMap<>();
        def.put("name", "Pay revision (IT fixation)");
        def.put("circularId", circular("WAGE_REVISION"));
        def.put("payrollTypes", List.of("STAFF_IDA"));
        def.put("effectiveFrom", LocalDate.now(IST).plusMonths(2).withDayOfMonth(1).toString());
        def.put("fitment", "FACTOR");
        def.put("value", 1.5);
        def.put("rounding", "NEXT_100");
        def.put("reason", "Order (IT)");
        long id = body(post(admin, "/v1/wage-revisions", def)).path("summary").path("id").asLong();
        assertThat(code(post(admin, "/v1/wage-revisions/" + id + "/fixations", Map.of())))
                .as("only an approved revision is fixed")
                .isEqualTo("INC-409-STATE");
        post(admin, "/v1/wage-revisions/" + id + "/simulations", Map.of());
        long approval =
                body(post(
                                admin,
                                "/v1/wage-revisions/" + id + "/submissions",
                                Map.of("remark", "Order")))
                        .get("approvalId")
                        .asLong();
        assertThat(decide(hqChecker, approval, "APPROVE", null, "WAGE_REVISION_HQ")).isEqualTo(200);
        MvcResult fixed = post(admin, "/v1/wage-revisions/" + id + "/fixations", Map.of());
        assertThat(fixed.getResponse().getStatus())
                .as(fixed.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode f = body(fixed);
        assertThat(f.path("fixed").asInt() + f.path("exceptions").asInt()).isPositive();
        assertThat(f.path("exceptions").asInt())
                .as("x1.5 pushes some above the scale maximum")
                .isPositive();
        assertThat(f.path("arrears").asInt()).as("a future date raises no arrear").isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revision_status FROM wage_revision WHERE wage_revision_id ="
                                        + " ?",
                                String.class,
                                id))
                .isEqualTo("PAY_FIXED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM wage_revision_member WHERE wage_revision_id ="
                                    + " ? AND fixation_status = 'EXCEPTION' AND exception_reason ="
                                    + " 'MAXIMUM_SCALE'",
                                Integer.class,
                                id))
                .isPositive();
        // SC-13-0058 (l.3159): old and new pay, the fitment and the order are kept for every
        // member of the revision; a fixed member's new pay follows the fitment (x1.5, next 100).
        List<Map<String, Object>> members =
                jdbc.queryForList(
                        "SELECT old_pay, new_pay, fixation_status FROM wage_revision_member WHERE"
                                + " wage_revision_id = ?",
                        id);
        assertThat(members).hasSize(f.path("fixed").asInt() + f.path("exceptions").asInt());
        assertThat(members).allSatisfy(m -> assertThat(m.get("OLD_PAY")).isNotNull());
        assertThat(members)
                .filteredOn(m -> "FIXED".equals(m.get("FIXATION_STATUS")))
                .allSatisfy(
                        m -> {
                            BigDecimal old = (BigDecimal) m.get("OLD_PAY");
                            BigDecimal expected =
                                    old.multiply(new BigDecimal("1.5"))
                                            .divide(
                                                    new BigDecimal("100"),
                                                    0,
                                                    java.math.RoundingMode.CEILING)
                                            .multiply(new BigDecimal("100"));
                            assertThat((BigDecimal) m.get("NEW_PAY"))
                                    .isEqualByComparingTo(expected);
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT circular_order_id FROM wage_revision WHERE"
                                        + " wage_revision_id = ?",
                                Long.class,
                                id))
                .as("the order")
                .isNotNull();
        assertThat(
                        jdbc.queryForList(
                                "SELECT fitment_rule_type FROM wage_revision_component WHERE"
                                        + " wage_revision_id = ?",
                                String.class,
                                id))
                .as("the fitment rule")
                .isNotEmpty()
                .containsOnly("FACTOR");
        String report = csv(admin, "/v1/wage-revisions/" + id + "/status-report?format=CSV");
        assertThat(report.lines().findFirst().orElseThrow())
                .contains("fixed")
                .contains("exception");
        assertThat(report.lines().count())
                .as("one row per office below the header")
                .isGreaterThan(1);
    }

    /**
     * A member of this test alone copied from 109991 (EIS {@code eis}), due this month, in the
     * employment status given.
     */
    private long copyDueThisMonth(String eis, String pan, String status) {
        jdbc.update(
                "INSERT INTO employee (emp_num, first_name, last_name, birth_date, gender,"
                        + " comp_joining_date, employee_type, emp_lbr_flag, pan_number,"
                        + " designation_id, grade_id, location_id, department_id, band_id,"
                        + " pay_pattern, pay_pattern_source, provident_fund_type, cpf_code,"
                        + " cpf_code_origin, parent_zone_id, employment_status, record_status,"
                        + " current_basic, mobile_number, email_address, bank_account_number,"
                        + " bank_branch_id, record_origin) SELECT ?, 'Copy', 'Member',"
                        + " birth_date, gender, comp_joining_date, employee_type, emp_lbr_flag,"
                        + " ?, designation_id, grade_id, location_id, department_id,"
                        + " band_id, pay_pattern, pay_pattern_source, provident_fund_type,"
                        + " ?, cpf_code_origin, parent_zone_id, ?, record_status, 62400, ?, ?, ?,"
                        + " bank_branch_id, record_origin FROM employee WHERE emp_num = '109991'",
                eis,
                pan,
                "CPF/" + eis,
                status,
                "90000" + eis.substring(1),
                "copy." + eis + "@fci-demo.test",
                "00000" + eis.substring(1));
        long id = employee(eis);
        jdbc.update(
                "INSERT INTO employee_increment_due (employee_id, next_due_date, source_code,"
                        + " updated_by) VALUES (?, ?, 'HRMS', 'it.fixture')",
                id,
                java.sql.Date.valueOf(YearMonth.now(IST).atDay(1)));
        return id;
    }

    /**
     * SRS l.3729 / I-05 (US-GAP-290): only members in service are proposed for the increment - a
     * separated and a dismissed member due the same month are left off the due list, and a run
     * naming them proposes nothing for them. (A suspended member waits for FCI: CLR-105.)
     */
    @Test
    void SRS_l3729_onlyInServiceMembersAreProposedASeparatedAndADismissedMemberAreLeftOut()
            throws Exception {
        long separated = copyDueThisMonth("109994", "ABCPM2004S", "SEPARATED");
        long dismissed = copyDueThisMonth("109995", "ABCPM2005S", "DISMISSED");
        Session hr = hr();
        YearMonth month = YearMonth.now(IST);
        List<String> listed =
                all(hr, "/v1/increments/due?month=" + month).findValuesAsString("eis");
        assertThat(listed).doesNotContain("109994", "109995");
        assertThat(listed).as("an in-service member due the month is listed").isNotEmpty();
        MvcResult run =
                post(
                        hr,
                        "/v1/increments/submissions",
                        Map.of(
                                "employeeIds",
                                List.of(separated, dismissed),
                                "remark",
                                "Increment run (IT)"));
        if (run.getResponse().getStatus() == 200) {
            assertThat(body(run).path("approvalIds")).as("nothing proposed").isEmpty();
        } else {
            assertThat(run.getResponse().getStatus()).isBetween(400, 499);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM pay_increment_cycle WHERE employee_id IN (?,"
                                        + " ?)",
                                Integer.class,
                                separated,
                                dismissed))
                .isZero();
    }

    /**
     * SRS-13-23 / FRS l.6519 (US-GAP-287): a fixation is worked out on the scale version in force
     * on its effective date - the day before the member's earliest scale version is refused as
     * flagged (no scale), the first day of it is worked out.
     */
    @Test
    // SC-17-0029: the scale version governs fixation only for periods on or after its date.
    void SRS_13_23_aFixationDatedBeforeTheScalesEffectiveFromIsRefused() throws Exception {
        LocalDate earliest =
                jdbc.queryForObject(
                                "SELECT MIN(v.effective_from) FROM pay_scale_band_version v JOIN"
                                        + " employee e ON e.band_id = v.band_id LEFT JOIN"
                                        + " approval_queue q ON q.approval_id = v.approval_id WHERE"
                                        + " e.employee_id = ? AND (v.approval_id IS NULL OR"
                                        + " q.status = 'APPROVED')",
                                java.sql.Date.class,
                                employee("109992"))
                        .toLocalDate();
        Session hr = hr();
        Map<String, Object> before = fixationCase("INDIVIDUAL_REVISION", earliest.minusDays(1));
        before.put("basic", 51000);
        MvcResult refused = post(hr, "/v1/pay-fixation-cases/previews", before);
        assertThat(code(refused)).isEqualTo("INC-422-FLAGGED");
        assertThat(body(refused).path("args").toString()).contains("NO_SCALE");
        Map<String, Object> onTheDay = fixationCase("INDIVIDUAL_REVISION", earliest);
        onTheDay.put("basic", 51000);
        MvcResult worked = post(hr, "/v1/pay-fixation-cases/previews", onTheDay);
        assertThat(body(worked).path("args").toString())
                .as(worked.getResponse().getContentAsString())
                .doesNotContain("NO_SCALE");
    }
}
