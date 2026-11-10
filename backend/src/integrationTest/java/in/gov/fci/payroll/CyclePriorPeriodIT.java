package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Initiation needs the prior period of the cycle instance locked, or no earlier period at all
 * (SRS-15-33, KB CYC-4; plan 05 D-25; Q-05-2). At Ludhiana (LD001), whose cycles no other IT
 * initiates, three to one months back. The requests are sent without the prior-period fixture of
 * {@link ApiTestSupport#userPost}.
 */
class CyclePriorPeriodIT extends ApiTestSupport {

    private static final YearMonth CURRENT =
            YearMonth.from(LocalDate.now(ZoneId.of("Asia/Kolkata")));

    private MvcResult initiate(Session maker, YearMonth period) throws Exception {
        return mvc.perform(
                        as(maker, withCsrf(post("/v1/cycles/" + period + "/initiate")))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andReturn();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    @Test
    void SRS_15_33_theFirstCycleStartsAnywhereThenNoPeriodIsSkippedOrLeftUnlocked()
            throws Exception {
        Session maker = login("tax.maker.ludhiana@fci-demo.test", "PAYROLL_MAKER", "LD001");
        Long unit = locationId("LD001");
        Integer before =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_cycle_instance WHERE org_unit_id = ?",
                        Integer.class,
                        unit);
        assertThat(before).as("Ludhiana has no cycle yet: its first one").isZero();
        YearMonth first = CURRENT.minusMonths(3);
        assertThat(initiate(maker, first).getResponse().getStatus()).isEqualTo(200);

        MvcResult skipped = initiate(maker, CURRENT.minusMonths(1));
        assertThat(skipped.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(skipped)).isEqualTo("CYC-409-PRIOR");
        assertThat(body(skipped).toString()).contains(CURRENT.minusMonths(2).toString());

        MvcResult unlocked = initiate(maker, CURRENT.minusMonths(2));
        assertThat(code(unlocked))
                .as("the prior period is only initiated")
                .isEqualTo("CYC-409-PRIOR");

        // A locked period (inserted locked: the status only moves forward one step at a time):
        // the month after it is initiated.
        YearMonth locked = CURRENT.minusMonths(6);
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, trigger_type, initiated_by) VALUES (?, ?, 'DO',"
                        + " ?, 'LOCKED', 'ON_DEMAND', 'it.prior')",
                locked.toString().replace("-", ""),
                unit,
                in.gov.fci.payroll.payroll.business.CycleKeys.finYear(locked));
        assertThat(initiate(maker, CURRENT.minusMonths(5)).getResponse().getStatus())
                .as("the prior period is locked")
                .isEqualTo(200);
    }
}
