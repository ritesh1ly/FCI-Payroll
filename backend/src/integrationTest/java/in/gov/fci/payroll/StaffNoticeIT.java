package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.eseva.orchestration.StaffNotices;
import in.gov.fci.payroll.payroll.api.CycleReturned;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Staff notices (owner 2026-10-28; SRS-15-30): a returned cycle of Delhi reaches the Delhi Payroll
 * Maker, who reads it and marks it read; another user cannot mark it, and a user sees only their
 * own.
 */
class StaffNoticeIT extends ApiTestSupport {

    @Autowired private StaffNotices notices;
    @Autowired private TransactionTemplate transactions;

    @Test
    void SRS_15_30_theMakerReadsTheReturnOfTheCycleAndMarksItRead() throws Exception {
        long dh001 =
                jdbc.queryForObject(
                        "SELECT location_id FROM org_location WHERE location_code = 'DH001'",
                        Long.class);
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, initiated_by) VALUES ('199001', ?, 'DO', '1989-90', 'it')",
                dh001);
        long instance =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " '199001' AND org_unit_id = ?",
                        Long.class,
                        dh001);
        transactions.executeWithoutResult(
                s ->
                        notices.on(
                                new CycleReturned(
                                        9_001L,
                                        instance,
                                        "1990-01",
                                        "DH001",
                                        "UNIT",
                                        "IT: pay days wrong",
                                        "ddo.delhi",
                                        Instant.now())));

        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        JsonNode mine = body(mvc.perform(userGet(maker, "/v1/notifications/mine")).andReturn());
        JsonNode notice = null;
        for (JsonNode n : mine.get("items")) {
            if (n.get("message").asString().contains("IT: pay days wrong")) {
                notice = n;
            }
        }
        assertThat(notice).as(mine.toString()).isNotNull();
        assertThat(notice.get("type").asString()).isEqualTo("CYCLE_RETURNED");
        assertThat(notice.get("read").asBoolean()).isFalse();
        // UI audit S11: the time is an instant with its zone (UTC "Z"), never a zone-less local
        // time a browser in India would read 5 h 30 min early; it is the moment the notice came.
        String created = notice.get("createdAt").asString();
        assertThat(created).endsWith("Z");
        assertThat(Instant.parse(created))
                .isBetween(Instant.now().minusSeconds(300), Instant.now().plusSeconds(5));
        long id = notice.get("id").asLong();

        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(
                        mvc.perform(
                                        userPost(
                                                checker,
                                                "/v1/notifications/mine/" + id + "/reads",
                                                null))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("another user's notice reads as unknown")
                .isEqualTo(404);
        assertThat(
                        mvc.perform(
                                        userPost(
                                                maker,
                                                "/v1/notifications/mine/" + id + "/reads",
                                                null))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT is_read_flag FROM notification WHERE notification_id = ?",
                                String.class,
                                id))
                .isEqualTo("Y");
    }
}
