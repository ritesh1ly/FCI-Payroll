package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.cpf.business.LedgerBalances;
import in.gov.fci.payroll.cpf.business.model.EmployeePfDetail.PaymentType;
import in.gov.fci.payroll.cpf.orchestration.CpfLedgerService;
import in.gov.fci.payroll.cpf.orchestration.CpfLedgerService.Posting;
import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The member CPF ledger (docs/plan/07-cpf.md phase 07c) against the real schema: the one posting
 * path and SRS-6-57 (no part negative), the year's ledger opened from the year before, SRS-6-59 (no
 * CPF code from HRMS: refused and raised on X-05), and SRS-6-9 / BR-CPF-016 (the ledger follows
 * separation, reinstatement and deputation). Demo members of zone NA11; every test uses a
 * far-future financial year of its own, so the ledgers never meet.
 */
class CpfLedgerIT extends ApiTestSupport {

    @Autowired private CpfLedgerService ledger;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private TransactionTemplate transactions;

    private long member(String eis) {
        return jdbc.queryForObject(
                "SELECT e.employee_id FROM employee e JOIN org_zone z ON z.zone_id ="
                        + " e.parent_zone_id WHERE e.emp_num = ? AND z.zone_code = 'NA11'",
                Long.class,
                eis);
    }

    private static LedgerBalances b(String e, String r, String v) {
        return new LedgerBalances(new BigDecimal(e), new BigDecimal(r), new BigDecimal(v));
    }

    private static Posting salary(long id, LocalDate day, LedgerBalances change) {
        return new Posting(
                id,
                day,
                PaymentType.SALARY,
                change,
                new BigDecimal("30000"),
                new BigDecimal("1250"),
                null,
                null,
                "it.cpf",
                "IT contribution");
    }

    private static Posting debit(long id, LocalDate day, LedgerBalances change) {
        return new Posting(
                id, day, null, change.negate(), null, null, null, null, "it.cpf", "IT withdrawal");
    }

    private Map<String, Object> header(long id, String year) {
        return jdbc.queryForMap(
                "SELECT * FROM employee_pf_header WHERE employee_id = ? AND fin_year = ? AND"
                        + " fund_type = 'CPF'",
                id,
                year);
    }

    private static String code(Throwable t) {
        return ((FciException) t).getErrorCode().code();
    }

    @Test
    void SRS_6_57_contributionsAccumulateAndNoPostingTakesAPartBelowZero() {
        long id = member("104882");
        LocalDate may = LocalDate.of(2090, 5, 15);
        assertThat(ledger.post(salary(id, may, b("1800", "550", "200"))))
                .isEqualTo(b("1800", "550", "200"));
        assertThat(ledger.post(salary(id, may.plusMonths(1), b("1800.50", "550", "0"))))
                .isEqualTo(b("3600.50", "1100", "200"));
        // The VPF part would go negative: refused, naming it; nothing changes.
        assertThatThrownBy(() -> ledger.post(debit(id, may.plusMonths(2), b("0", "0", "200.01"))))
                .satisfies(
                        t ->
                                assertThat(((FciException) t).getMessageArgs())
                                        .containsExactly("VPF"));
        Map<String, Object> h = header(id, "2090-91");
        assertThat((BigDecimal) h.get("CLOSING_BALANCE_VPF")).isEqualByComparingTo("200");
        // Exactly to zero is allowed.
        assertThat(ledger.post(debit(id, may.plusMonths(2), b("0", "0", "200"))).vpf())
                .isEqualByComparingTo("0");
        // Each contribution is a monthly row booked to the member's zone; a debit writes none.
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT payroll_yymm, payment_type, employee_contribution, zone_id FROM"
                            + " employee_pf_detail WHERE pf_header_id = ? ORDER BY payroll_yymm",
                        h.get("PF_HEADER_ID"));
        assertThat(rows).extracting(r -> r.get("PAYROLL_YYMM")).containsExactly("209005", "209006");
        assertThat(rows.get(0).get("ZONE_ID")).isEqualTo(h.get("ZONE_ID"));
        // Every change of a closing balance is audited with its old and new value.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'employee_pf_header' AND entity_key = ? AND field_name"
                                        + " = 'closing_balance_employee'",
                                Integer.class,
                                id + "|2090-91"))
                .isEqualTo(2);
    }

    @Test
    void SRS_6_57_aNewYearOpensAtTheClosingBalanceOfTheYearBefore() {
        long id = member("105461");
        ledger.post(salary(id, LocalDate.of(2091, 3, 10), b("1000", "300", "50")));
        ledger.post(salary(id, LocalDate.of(2091, 4, 10), b("10", "3", "0")));
        Map<String, Object> next = header(id, "2091-92");
        assertThat((BigDecimal) next.get("OPENING_BALANCE_EMPLOYEE")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) next.get("OPENING_BALANCE_VPF")).isEqualByComparingTo("50");
        assertThat((BigDecimal) next.get("CLOSING_BALANCE_EMPLOYEE")).isEqualByComparingTo("1010");
        assertThat(next.get("OPENING_BALANCE_SOURCE")).isEqualTo("SYSTEM_COMPUTED");
        // The earlier year keeps its own closing balance.
        assertThat((BigDecimal) header(id, "2090-91").get("CLOSING_BALANCE_EMPLOYER"))
                .isEqualByComparingTo("300");
    }

    @Test
    void SRS_6_57_aFinallySettledLedgerTakesNoMorePostings() {
        long id = member("104882");
        LocalDate day = LocalDate.of(2092, 6, 1);
        ledger.post(salary(id, day, b("100", "0", "0")));
        jdbc.update(
                "UPDATE employee_pf_header SET member_status = 'FINALLY_SETTLED' WHERE"
                        + " employee_id = ? AND fin_year = '2092-93'",
                id);
        try {
            assertThatThrownBy(() -> ledger.post(salary(id, day, b("1", "0", "0"))))
                    .satisfies(t -> assertThat(code(t)).isEqualTo("CPF-409-LEDGER-CLOSED"));
        } finally {
            // The ITs share one database: a settlement is for life, so leave the member as found
            // (OpeningBalanceIT corrects the same member and refuses a settled one).
            jdbc.update(
                    "UPDATE employee_pf_header SET member_status = 'ACTIVE' WHERE employee_id = ?"
                            + " AND fin_year = '2092-93'",
                    id);
        }
    }

    // SC-16-0017: the CPF Code is received from HRMS and checked present; a member without it is
    // listed, never posted.
    // SC-18-0013: a member whose CPF Code from HRMS is missing is listed for correction, never
    // posted.
    @Test
    void SRS_6_59_aMemberWithoutACpfCodeGetsNoPostingAndIsListedOnX05() {
        // HRMS owns the CPF code: the X-05 entry is routed upstream, not corrected in Payroll.
        long noCode = member("106004");
        assertThatThrownBy(
                        () ->
                                ledger.post(
                                        salary(noCode, LocalDate.of(2093, 5, 1), b("1", "0", "0"))))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CPF-422-CPF-CODE"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payroll_exception WHERE employee_id = ? AND"
                                    + " exception_code = 'CPF_CODE_MISSING' AND exception_status ="
                                    + " 'ROUTED_UPSTREAM'",
                                Integer.class,
                                noCode))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM employee_pf_header WHERE employee_id = ?",
                                Integer.class,
                                noCode))
                .isZero();
        long nps = member("105220");
        assertThatThrownBy(
                        () -> ledger.post(salary(nps, LocalDate.of(2093, 5, 1), b("1", "0", "0"))))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CPF-422-NOT-MEMBER"));
    }

    // SRS-INT-21: member CPF data is processed in the CPF module (trust administration is outside).
    @Test
    void SRS_6_9_theLedgerFollowsSeparationReinstatementAndDeputation() {
        long id = member("105461");
        LocalDate separated = LocalDate.of(2094, 7, 31);
        event(id, "SEPARATION", separated);
        Map<String, Object> h = header(id, "2094-95");
        assertThat(h.get("MEMBER_STATUS")).isEqualTo("EX_MEMBER");
        assertThat(h.get("INTEREST_END_DATE").toString()).startsWith("2097-07-31");
        assertThat(h.get("INTEREST_END_REASON")).isEqualTo("EX_MEMBER_3_YEARS");
        // The next year's ledger carries the stop.
        ledger.post(salary(id, LocalDate.of(2095, 4, 1), b("1", "0", "0")));
        assertThat(header(id, "2095-96").get("INTEREST_END_DATE").toString())
                .startsWith("2097-07-31");

        event(id, "REINSTATEMENT", LocalDate.of(2095, 5, 1));
        h = header(id, "2095-96");
        assertThat(h.get("MEMBER_STATUS")).isEqualTo("ACTIVE");
        assertThat(h.get("INTEREST_END_DATE")).isNull();
        assertThat(h.get("INTEREST_END_CLEARED_AT")).isNotNull();
        // The year before keeps what it was (history is not rewritten).
        assertThat(header(id, "2094-95").get("MEMBER_STATUS")).isEqualTo("EX_MEMBER");

        // BR-CPF-016: on deputation the three-year stop never applies.
        event(id, "DEPUTATION_OUT", LocalDate.of(2095, 6, 1));
        assertThat(header(id, "2095-96").get("IS_DEPUTATION")).isEqualTo("Y");
        event(id, "SEPARATION", LocalDate.of(2095, 8, 1));
        h = header(id, "2095-96");
        assertThat(h.get("MEMBER_STATUS")).isEqualTo("EX_MEMBER");
        assertThat(h.get("INTEREST_END_DATE")).isNull();
        event(id, "DEPUTATION_IN", LocalDate.of(2095, 9, 1));
        assertThat(header(id, "2095-96").get("IS_DEPUTATION")).isEqualTo("N");
        // An event that does not concern the ledger, and a member who is not a CPF member, change
        // nothing.
        event(id, "SUSPENSION", LocalDate.of(2095, 10, 1));
        assertThat(header(id, "2095-96").get("MEMBER_STATUS")).isEqualTo("ACTIVE");
        event(member("105220"), "SEPARATION", LocalDate.of(2095, 10, 1));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM employee_pf_header WHERE employee_id = ?",
                                Integer.class,
                                member("105220")))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'employee_pf_header' AND entity_key = ? AND field_name"
                                        + " = 'interest_end_date'",
                                Integer.class,
                                id + "|2095-96"))
                .isPositive();
    }

    @Test
    void SRS_6_57_twoPostingsOnOneLedgerAtOnceBothCountInTurn() throws Exception {
        long id = member("104882");
        LocalDate day = LocalDate.of(2096, 5, 1);
        ledger.post(salary(id, day, b("0", "0", "0")));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<LedgerBalances>> both =
                    List.of(
                            pool.submit(
                                    () -> {
                                        start.await();
                                        return ledger.post(
                                                salary(id, day.plusMonths(1), b("100", "0", "0")));
                                    }),
                            pool.submit(
                                    () -> {
                                        start.await();
                                        return ledger.post(
                                                salary(id, day.plusMonths(2), b("50", "0", "0")));
                                    }));
            start.countDown();
            for (Future<LedgerBalances> f : both) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        // The year opens on the balance carried from the member's earlier ledger (the demo ledger
        // of the current year holds postings, seed V077_28); both postings count on top of it.
        Map<String, Object> year = header(id, "2096-97");
        assertThat((BigDecimal) year.get("CLOSING_BALANCE_EMPLOYEE"))
                .isEqualByComparingTo(
                        ((BigDecimal) year.get("OPENING_BALANCE_EMPLOYEE"))
                                .add(new BigDecimal("150")));
    }

    private void event(long employeeId, String type, LocalDate day) {
        transactions.executeWithoutResult(
                s ->
                        publisher.publishEvent(
                                new EmployeeEvents.EmployeeLifecycleApplied(
                                        employeeId, 1L, type, day, null, "it.checker")));
    }
}
