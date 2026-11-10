package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.loans.business.LoanRules;
import in.gov.fci.payroll.loans.orchestration.LoanLedgerEffect;
import in.gov.fci.payroll.loans.orchestration.LoanWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Foreclosure and reschedule of an I-04 loan (plan 09 phase 09e; SRS-12-4, SRS-12-16, SRS-12-21,
 * SRS-12-22): the foreclosure needs the payment receipt and the Checker's LOAN_FORECLOSURE OTP and
 * clears the outstanding; the reschedule needs the signed request and recomputes on the remaining
 * balance; a loan with a change waiting takes no other; an employee loan from HRMS is changed only
 * through HRMS. DH001 members near the end of the list.
 */
class LoanChangeIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private LoanWriter writer;
    @Autowired private LoanLedgerEffect ledger;
    @Autowired private PlatformTransactionManager transactionManager;

    private long member(int fromEnd) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE location_id = ? AND record_status ="
                    + " 'ACTIVE' AND employment_status = 'IN_SERVICE' ORDER BY emp_num DESC OFFSET "
                        + fromEnd
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                Long.class,
                locationId("DH001"));
    }

    private static String next() {
        YearMonth m = YearMonth.now(IST).plusMonths(1);
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }

    private long loan(long employee, LoanWriter.Source source) {
        return loan(employee, source, next());
    }

    private long loan(long employee, LoanWriter.Source source, String start) {
        long typeId =
                jdbc.queryForObject(
                        "SELECT loan_type_id FROM loan_type_master WHERE loan_type_code ="
                                + " 'FESTIVAL_ADVANCE'",
                        Long.class);
        LoanRules.Terms t =
                new LoanRules.Terms(
                        new BigDecimal("6000"),
                        3,
                        new BigDecimal("2000"),
                        null,
                        null,
                        0,
                        null,
                        start);
        return new TransactionTemplate(transactionManager)
                .execute(
                        s ->
                                writer.create(
                                                employee,
                                                typeId,
                                                "FESTIVAL_ADVANCE",
                                                t,
                                                LoanRules.schedule(t),
                                                LocalDate.now(IST),
                                                source,
                                                source == LoanWriter.Source.HRMS
                                                        ? "IT-CH-" + System.nanoTime()
                                                        : null,
                                                null,
                                                null,
                                                "it.maker",
                                                "it.checker",
                                                Instant.now())
                                        .loanId());
    }

    private MvcResult post(Session s, String path, Map<String, Object> schedule, boolean withFile)
            throws Exception {
        return post(s, path, schedule, withFile, true);
    }

    /** US-GAP-202 (SRS-12-18): the order travels beside the receipt or the signed request. */
    private MvcResult post(
            Session s,
            String path,
            Map<String, Object> schedule,
            boolean withFile,
            boolean withOrder)
            throws Exception {
        var request =
                multipart(path)
                        .file(
                                new MockMultipartFile(
                                        "file",
                                        "receipt.pdf",
                                        "application/pdf",
                                        withFile
                                                ? "%PDF-1.7 receipt"
                                                        .getBytes(StandardCharsets.US_ASCII)
                                                : new byte[0]))
                        .param("documentType", "SANCTION_ORDER")
                        .param("reference", "DH/LN/IT")
                        .param("orderDate", LocalDate.now(IST).minusDays(1).toString())
                        .param("issuingAuthority", "AGM (Pay) Delhi");
        if (withOrder) {
            request.file(
                            new MockMultipartFile(
                                    "orderFile",
                                    "order.pdf",
                                    "application/pdf",
                                    "%PDF-1.7 order".getBytes(StandardCharsets.US_ASCII)))
                    .param("orderDocumentType", "SANCTION_ORDER")
                    .param("orderReference", "DH/LN/ORD/IT")
                    .param("orderIssuedOn", LocalDate.now(IST).minusDays(2).toString())
                    .param("orderAuthority", "AGM (Pay) Delhi");
        }
        if (schedule != null) {
            request.file(
                    new MockMultipartFile(
                            "schedule", "", "application/json", JSON.writeValueAsBytes(schedule)));
        }
        return mvc.perform(
                        userRequest(s, request)
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
    }

    private static String code(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString()).path("errorCode").asString();
    }

    /** SRS-12-10: the member's LOAN_INSTALMENT notices of the loan whose message matches. */
    private int notices(long loanId, String like) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification n JOIN loan_header h ON h.loan_number ="
                        + " n.related_entity_key AND h.employee_id = n.recipient_employee_id WHERE"
                        + " h.loan_id = ? AND n.notification_type = 'LOAN_INSTALMENT' AND"
                        + " n.channel = 'IN_APP' AND n.message LIKE ?",
                Integer.class,
                loanId,
                like);
    }

    private int events(long loanId, String type) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM loan_event WHERE loan_id = ? AND event_type = ?",
                Integer.class,
                loanId,
                type);
    }

    private Session maker() throws Exception {
        return login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
    }

    private Session checker() throws Exception {
        return login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
    }

    /**
     * Wave D review 4 (SRS-G-30, SRS-12-18): an order file sent without its document type or its
     * issue date is refused (LOAN-422-SANCTION ORDER_PARTICULARS), never stored as an unchecked
     * system file.
     */
    @Test
    void SRS_G_30_anOrderWithoutItsTypeOrIssueDateIsRefused() throws Exception {
        long loanId = loan(member(19), LoanWriter.Source.LABOUR_ENT);
        Session maker = maker();
        String path = "/v1/loans/" + loanId + "/foreclosures";
        for (String omitted : new String[] {"orderDocumentType", "orderIssuedOn"}) {
            var request =
                    multipart(path)
                            .file(
                                    new MockMultipartFile(
                                            "file",
                                            "receipt.pdf",
                                            "application/pdf",
                                            "%PDF-1.7 receipt".getBytes(StandardCharsets.US_ASCII)))
                            .file(
                                    new MockMultipartFile(
                                            "orderFile",
                                            "order.pdf",
                                            "application/pdf",
                                            "%PDF-1.7 order".getBytes(StandardCharsets.US_ASCII)))
                            .param("documentType", "SANCTION_ORDER")
                            .param("orderDate", LocalDate.now(IST).minusDays(1).toString())
                            .param("issuingAuthority", "AGM (Pay) Delhi")
                            .param("orderAuthority", "AGM (Pay) Delhi");
            if (!"orderDocumentType".equals(omitted)) {
                request.param("orderDocumentType", "SANCTION_ORDER");
            }
            if (!"orderIssuedOn".equals(omitted)) {
                request.param("orderIssuedOn", LocalDate.now(IST).minusDays(2).toString());
            }
            MvcResult refused =
                    mvc.perform(
                                    userRequest(maker, request)
                                            .header(
                                                    "Idempotency-Key",
                                                    UUID.randomUUID().toString()))
                            .andReturn();
            assertThat(code(refused)).as(omitted).isEqualTo("LOAN-422-SANCTION");
            assertThat(body(refused).path("args").get(0).asString())
                    .as(omitted)
                    .isEqualTo("ORDER_PARTICULARS");
        }
    }

    // SC-09-0027: a foreclosure clears the full outstanding balance.
    @Test
    void SRS_12_21_BR_LOAN_011_aForeclosureNeedsTheReceiptAndTheCheckersOtp() throws Exception {
        long loanId = loan(member(19), LoanWriter.Source.LABOUR_ENT);
        Session maker = maker();
        String path = "/v1/loans/" + loanId + "/foreclosures";
        // BMAD TR-023 (SRS-12-21 l.2674): the refusal names the missing receipt; nothing is
        // written.
        MvcResult noReceipt = post(maker, path, null, false);
        assertThat(code(noReceipt)).isEqualTo("LOAN-422-SANCTION");
        assertThat(body(noReceipt).path("args").get(0).asString()).isEqualTo("RECEIPT_MISSING");
        assertThat(events(loanId, "FORECLOSURE")).isZero();
        MvcResult noOrder = post(maker, path, null, true, false);
        assertThat(code(noOrder)).as("US-GAP-202: the order").isEqualTo("LOAN-422-SANCTION");
        assertThat(body(noOrder).path("args").get(0).asString()).isEqualTo("ORDER_MISSING");
        MvcResult sent = post(maker, path, null, true);
        assertThat(sent.getResponse().getStatus()).isEqualTo(202);
        long approval = body(sent).path("id").asLong();
        assertThat(post(maker, path, null, true).getResponse().getStatus())
                .as("one change at a time")
                .isEqualTo(409);
        assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(428);
        // SRS-12-10 (l.2662, l.2692; BMAD TR-024): no notice before the Checker's approval.
        assertThat(notices(loanId, "%FORECLOSURE%")).isZero();
        assertThat(decide(checker(), approval, "APPROVE", null, "LOAN_FORECLOSURE")).isEqualTo(200);
        assertThat(notices(loanId, "%FORECLOSURE: instalment 0%")).isEqualTo(1);
        Map<String, Object> l =
                jdbc.queryForMap(
                        "SELECT loan_status, principal_outstanding FROM loan_header WHERE loan_id ="
                                + " ?",
                        loanId);
        assertThat(l.get("LOAN_STATUS")).isEqualTo("FORECLOSED");
        assertThat((BigDecimal) l.get("PRINCIPAL_OUTSTANDING")).isEqualByComparingTo("0");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT otp_challenge_id FROM loan_event WHERE loan_id = ? AND"
                                        + " event_type = 'FORECLOSURE'",
                                Long.class,
                                loanId))
                .isNotNull();
    }

    /**
     * SRS-12-4 / SRS-12-1 (I-04 Interest Instalments): a loan that still owes interest is
     * rescheduled with the remaining interest spread over its own instalments after the principal;
     * without them, or with interest terms that do not add up, it is refused.
     */
    @Test
    void SRS_12_4_aLoanWithInterestOutstandingIsRescheduledWithItsInterestInstalments()
            throws Exception {
        long loanId = loan(member(24), LoanWriter.Source.LABOUR_ENT);
        jdbc.update(
                "UPDATE loan_header SET principal_outstanding = 4000, interest_amount = 900,"
                        + " interest_outstanding = 600 WHERE loan_id = ?",
                loanId);
        Session maker = maker();
        String path = "/v1/loans/" + loanId + "/reschedules";
        Map<String, Object> noInterest =
                Map.of(
                        "principalInstalments",
                        4,
                        "instalment",
                        new BigDecimal("1000"),
                        "interestInstalments",
                        0,
                        "startYymm",
                        next());
        assertThat(code(post(maker, path, noInterest, true)))
                .as("the interest outstanding needs its instalments")
                .isEqualTo("LOAN-422-SANCTION");
        // Edge: interest terms that leave a residual larger than allowed (2 x 200 on 600).
        Map<String, Object> tooSmall = new java.util.HashMap<>(noInterest);
        tooSmall.put("interestInstalments", 2);
        tooSmall.put("interestInstalment", new BigDecimal("200"));
        assertThat(code(post(maker, path, tooSmall, true))).isEqualTo("LOAN-422-SANCTION");
        Map<String, Object> withInterest = new java.util.HashMap<>(noInterest);
        withInterest.put("interestInstalments", 2);
        withInterest.put("interestInstalment", new BigDecimal("300"));
        MvcResult sent = post(maker, path, withInterest, true);
        assertThat(sent.getResponse().getStatus())
                .as(sent.getResponse().getContentAsString())
                .isEqualTo(202);
        assertThat(decide(checker(), body(sent).path("id").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT SUM(l.interest_amount) FROM loan_recovery_schedule l JOIN"
                                    + " loan_schedule_version v ON v.schedule_version_id ="
                                    + " l.schedule_version_id WHERE v.loan_id = ? AND v.reason ="
                                    + " 'RESCHEDULE'",
                                BigDecimal.class,
                                loanId))
                .isEqualByComparingTo("600");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM loan_recovery_schedule l JOIN"
                                    + " loan_schedule_version v ON v.schedule_version_id ="
                                    + " l.schedule_version_id WHERE v.loan_id = ? AND v.reason ="
                                    + " 'RESCHEDULE'",
                                Long.class,
                                loanId))
                .isEqualTo(6L);
    }

    // SC-09-0028: a reschedule carries the employee's signed request.
    @Test
    void SRS_12_22_BR_LOAN_012_aRescheduleNeedsTheSignedRequestAndRecomputesOnTheBalance()
            throws Exception {
        long loanId = loan(member(20), LoanWriter.Source.LABOUR_ENT);
        jdbc.update(
                "UPDATE loan_header SET principal_outstanding = 4000 WHERE loan_id = ?", loanId);
        Session maker = maker();
        String path = "/v1/loans/" + loanId + "/reschedules";
        Map<String, Object> schedule =
                Map.of(
                        "principalInstalments",
                        4,
                        "instalment",
                        new BigDecimal("1000"),
                        "interestInstalments",
                        0,
                        "startYymm",
                        next());
        MvcResult unsigned = post(maker, path, schedule, false);
        assertThat(code(unsigned)).isEqualTo("LOAN-422-SANCTION");
        assertThat(body(unsigned).path("args").get(0).asString())
                .as("SRS-12-22: the signed request")
                .isEqualTo("SIGNED_REQUEST_MISSING");
        MvcResult noOrder = post(maker, path, schedule, true, false);
        assertThat(body(noOrder).path("args").get(0).asString())
                .as("US-GAP-202: the order")
                .isEqualTo("ORDER_MISSING");
        Map<String, Object> notAddingUp =
                Map.of(
                        "principalInstalments",
                        2,
                        "instalment",
                        new BigDecimal("1000"),
                        "interestInstalments",
                        0,
                        "startYymm",
                        next());
        assertThat(code(post(maker, path, notAddingUp, true)))
                .as("SRS-12-4: on the remaining 4,000")
                .isEqualTo("LOAN-422-SANCTION");
        MvcResult sent = post(maker, path, schedule, true);
        assertThat(sent.getResponse().getStatus()).isEqualTo(202);
        // SRS-12-10 (l.2662, l.2693; BMAD TR-024): the member is told only once it is approved.
        assertThat(notices(loanId, "%RESCHEDULE%")).isZero();
        assertThat(decide(checker(), body(sent).path("id").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        assertThat(notices(loanId, "%RESCHEDULE: instalment 1000% from " + next())).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT loan_status FROM loan_header WHERE loan_id = ?",
                                String.class,
                                loanId))
                .isEqualTo("RESCHEDULED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM loan_recovery_schedule l JOIN"
                                    + " loan_schedule_version v ON v.schedule_version_id ="
                                    + " l.schedule_version_id WHERE v.loan_id = ? AND v.reason ="
                                    + " 'RESCHEDULE'",
                                Long.class,
                                loanId))
                .isEqualTo(4L);
        JsonNode nextLine =
                body(mvc.perform(userGet(maker, "/v1/loans/" + loanId)).andReturn())
                        .path("nextInstalment");
        assertThat(nextLine.path("payrollYymm").asString())
                .as("SRS-12-23: the next instalment comes from the version in force")
                .isEqualTo(next());
        assertThat(nextLine.path("principal").decimalValue()).isEqualByComparingTo("1000");
    }

    @Test
    void SRS_12_17_anHrmsLoanIsChangedOnlyThroughHrms() throws Exception {
        long loanId = loan(member(21), LoanWriter.Source.HRMS);
        MvcResult r = post(maker(), "/v1/loans/" + loanId + "/foreclosures", null, true);
        assertThat(code(r)).isEqualTo("LOAN-422-SANCTION");
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        assertThat(
                        post(hr, "/v1/loans/" + loanId + "/foreclosures", null, true)
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
    }

    private static String yymm(YearMonth m) {
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }

    /** A DH001 cycle of the month with a current Actual result of the member; its id. */
    private long computed(long employee, YearMonth month) {
        long unit = locationId("DH001");
        String m = yymm(month);
        int start = month.getMonthValue() >= 4 ? month.getYear() : month.getYear() - 1;
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, initiated_by) SELECT ?, ?, 'DO', ?, 'COMPUTED',"
                        + " 'it.fixture' FROM dual WHERE NOT EXISTS (SELECT 1 FROM"
                        + " payroll_cycle_instance WHERE payroll_yymm = ? AND org_unit_id = ?)",
                m,
                unit,
                String.format("%d-%02d", start, (start + 1) % 100),
                m,
                unit);
        long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " ? AND org_unit_id = ?",
                        Long.class,
                        m,
                        unit);
        jdbc.update(
                "INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id,"
                        + " payroll_type, payroll_yymm, period_date, run_type, gross_earnings,"
                        + " total_deductions, net_pay, result_status, is_locked) VALUES (?, ?, ?,"
                        + " 'STAFF', ?, ?, 'ACTUAL', 40000, 7000, 33000, 'CURRENT', 'N')",
                cycle,
                unit,
                employee,
                m,
                java.sql.Date.valueOf(month.atDay(1)));
        return cycle;
    }

    /**
     * US-GAP-208 (SRS-12-16, BR-LOAN-005): a foreclosure approved after the month's cycle was
     * computed and before its lock clears the full outstanding (the month's instalment not taken
     * off it) in one event, and the lock afterwards records no further recovery of the foreclosed
     * loan. What becomes of the instalment the computed result already carries is FCI's to say
     * (CLR-62).
     */
    @Test
    void SRS_12_16_aForeclosureBetweenComputationAndLockClearsTheFullOutstandingOnce()
            throws Exception {
        YearMonth month = YearMonth.now(IST).plusMonths(80);
        long employee = member(22);
        long loanId = loan(employee, LoanWriter.Source.LABOUR_ENT, yymm(month));
        long cycle = computed(employee, month);
        MvcResult sent = post(maker(), "/v1/loans/" + loanId + "/foreclosures", null, true);
        assertThat(sent.getResponse().getStatus()).isEqualTo(202);
        assertThat(
                        decide(
                                checker(),
                                body(sent).path("id").asLong(),
                                "APPROVE",
                                null,
                                "LOAN_FORECLOSURE"))
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT settlement_amount FROM loan_event WHERE loan_id = ? AND"
                                        + " event_type = 'FORECLOSURE'",
                                BigDecimal.class,
                                loanId))
                .as("the full outstanding, the computed instalment not taken off")
                .isEqualByComparingTo("6000");
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        s ->
                                ledger.on(
                                        new in.gov.fci.payroll.payroll.api.CycleLocked(
                                                cycle,
                                                locationId("DH001"),
                                                yymm(month),
                                                "it.checker",
                                                Instant.now())));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM loan_monthly_balance WHERE loan_id = ?",
                                Long.class,
                                loanId))
                .as("nothing recovered again at the lock")
                .isZero();
        Map<String, Object> l =
                jdbc.queryForMap(
                        "SELECT loan_status, principal_outstanding FROM loan_header WHERE loan_id ="
                                + " ?",
                        loanId);
        assertThat(l.get("LOAN_STATUS")).isEqualTo("FORECLOSED");
        assertThat((BigDecimal) l.get("PRINCIPAL_OUTSTANDING")).isEqualByComparingTo("0");
    }

    /**
     * US-GAP-207 (SRS-12-26, BR-LOAN-010): a transfer while a reschedule waits for the Checker
     * moves the outstanding through IOGA as it stands; the reschedule approved afterwards
     * recomputes on that same balance and moves nothing again.
     */
    @Test
    void SRS_12_26_aTransferWhileARescheduleWaitsMovesTheOutstandingOnce() throws Exception {
        long employee = member(23);
        long loanId = loan(employee, LoanWriter.Source.LABOUR_ENT);
        Map<String, Object> schedule =
                Map.of(
                        "principalInstalments",
                        4,
                        "instalment",
                        new BigDecimal("1500"),
                        "interestInstalments",
                        0,
                        "startYymm",
                        next());
        MvcResult sent = post(maker(), "/v1/loans/" + loanId + "/reschedules", schedule, true);
        assertThat(sent.getResponse().getStatus()).isEqualTo(202);
        ledger.on(
                new EmployeeEvents.EmployeeTransferred(
                        employee,
                        1L,
                        locationId("CH001"),
                        locationId("DH001"),
                        LocalDate.now(IST),
                        "TR/IT/207",
                        "it.checker"));
        assertThat(decide(checker(), body(sent).path("id").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForList(
                                "SELECT settlement_amount FROM loan_event WHERE loan_id = ? AND"
                                        + " event_type = 'UNIT_TRANSFER'",
                                BigDecimal.class,
                                loanId))
                .singleElement()
                .satisfies(a -> assertThat(a).isEqualByComparingTo("6000"));
        Map<String, Object> l =
                jdbc.queryForMap(
                        "SELECT loan_status, principal_outstanding FROM loan_header WHERE loan_id ="
                                + " ?",
                        loanId);
        assertThat(l.get("LOAN_STATUS")).isEqualTo("RESCHEDULED");
        assertThat((BigDecimal) l.get("PRINCIPAL_OUTSTANDING")).isEqualByComparingTo("6000");
    }

    /**
     * SRS-G-22 (l.538) / SRS-12-21: the Checker's LOAN_FORECLOSURE code answered after its validity
     * ran out is refused (AUTH-400-OTP, challenge EXPIRED) and the loan stays ACTIVE with its
     * outstanding; a fresh code then forecloses it.
     */
    @Test
    void SRS_G_22_LOAN_FORECLOSURE_withAnExpiredOtpIsRefusedAndTheLoanRunsOn() throws Exception {
        long loanId = loan(member(25), LoanWriter.Source.LABOUR_ENT);
        MvcResult sent = post(maker(), "/v1/loans/" + loanId + "/foreclosures", null, true);
        assertThat(sent.getResponse().getStatus()).isEqualTo(202);
        long approval = body(sent).path("id").asLong();
        Session checker = checker();
        Map.Entry<MvcResult, Long> late =
                decideAfterOtpExpiry(checker, approval, "APPROVE", null, "LOAN_FORECLOSURE");
        assertThat(late.getKey().getResponse().getStatus()).isEqualTo(400);
        assertThat(code(late.getKey())).isEqualTo("AUTH-400-OTP");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT outcome FROM otp_challenge WHERE otp_challenge_id = ?",
                                String.class,
                                late.getValue()))
                .isEqualTo("EXPIRED");
        Map<String, Object> l =
                jdbc.queryForMap(
                        "SELECT loan_status, principal_outstanding FROM loan_header WHERE loan_id ="
                                + " ?",
                        loanId);
        assertThat(l.get("LOAN_STATUS")).isEqualTo("ACTIVE");
        assertThat((BigDecimal) l.get("PRINCIPAL_OUTSTANDING")).isEqualByComparingTo("6000");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                approval))
                .isEqualTo("PENDING_APPROVAL");
        assertThat(decide(checker, approval, "APPROVE", null, "LOAN_FORECLOSURE")).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT loan_status FROM loan_header WHERE loan_id = ?",
                                String.class,
                                loanId))
                .isEqualTo("FORECLOSED");
    }

    /**
     * SRS-12-17 (l.2670) / SRS-G-1, R-MC (BMAD TR-025): the Payroll Maker who sent a foreclosure or
     * a reschedule never approves it, even holding the Checker role of the office (maker.checker,
     * seed V015_9): WF-403-SOD, the loan stays ACTIVE with its outstanding, no event and no notice;
     * another Checker then rejects it.
     */
    @Test
    void SRS_12_17_SRS_G_1_theMakerNeverApprovesTheOwnForeclosureOrReschedule() throws Exception {
        Session both = login("maker.checker@fci-demo.test", "PAYROLL_MAKER", "DH001");
        long foreclosed = loan(member(26), LoanWriter.Source.LABOUR_ENT);
        long rescheduled = loan(member(27), LoanWriter.Source.LABOUR_ENT);
        jdbc.update(
                "UPDATE loan_header SET principal_outstanding = 4000 WHERE loan_id = ?",
                rescheduled);
        Map<String, Object> schedule =
                Map.of(
                        "principalInstalments",
                        4,
                        "instalment",
                        new BigDecimal("1000"),
                        "interestInstalments",
                        0,
                        "startYymm",
                        next());
        MvcResult f = post(both, "/v1/loans/" + foreclosed + "/foreclosures", null, true);
        assertThat(f.getResponse().getStatus())
                .as(f.getResponse().getContentAsString())
                .isEqualTo(202);
        MvcResult r = post(both, "/v1/loans/" + rescheduled + "/reschedules", schedule, true);
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(202);
        actAs(both, "PAYROLL_CHECKER");
        for (Map.Entry<Long, Long> item :
                Map.of(
                                foreclosed, body(f).path("id").asLong(),
                                rescheduled, body(r).path("id").asLong())
                        .entrySet()) {
            MvcResult own =
                    mvc.perform(
                                    userPost(
                                            both,
                                            "/v1/approvals/" + item.getValue() + "/decision",
                                            Map.of("decision", "APPROVE")))
                            .andReturn();
            assertThat(own.getResponse().getStatus())
                    .as(own.getResponse().getContentAsString())
                    .isEqualTo(403);
            assertThat(code(own)).isEqualTo("WF-403-SOD");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT loan_status FROM loan_header WHERE loan_id = ?",
                                    String.class,
                                    item.getKey()))
                    .isEqualTo("ACTIVE");
            assertThat(notices(item.getKey(), "%")).isZero();
            assertThat(decide(checker(), item.getValue(), "REJECT", "IT: own-approval check", null))
                    .isEqualTo(200);
        }
        assertThat(events(foreclosed, "FORECLOSURE")).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT principal_outstanding FROM loan_header WHERE loan_id = ?",
                                BigDecimal.class,
                                foreclosed))
                .isEqualByComparingTo("6000");
    }
}
