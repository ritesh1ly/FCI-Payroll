package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * X-02 Approvals Inbox, SRS-22-4 / SRS-22-16: an item is due by the target turnaround its workflow
 * action sets (a business target, not a service level); its age is shown against it and an item
 * past it stays flagged until actioned. An action without a target gives no due time (none is
 * guessed).
 */
class InboxTargetIT extends ApiTestSupport {

    private static final String ACTION = "A07_EDIT";

    private JsonNode inboxItem(Session checker, long approvalId) throws Exception {
        for (JsonNode i : all(checker, "/v1/inbox")) {
            if (i.get("approvalId").asLong() == approvalId) {
                return i;
            }
        }
        return null;
    }

    private long propose(Session admin, int value) throws Exception {
        return body(mvc.perform(
                                userPut(
                                        admin,
                                        "/v1/admin/parameters/MAX_RETRO_PERIOD",
                                        Map.of("value", value, "reason", "IT target")))
                        .andReturn())
                .path("pending")
                .path("approvalId")
                .asLong();
    }

    @Test
    void SRS_22_4_anItemIsDueByItsActionsTargetAndFlaggedOncePastIt() throws Exception {
        Session admin = login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
        Session checker = login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
        jdbc.update(
                "UPDATE approval_workflow_action SET target_tat_hours = 48 WHERE action_code = ?",
                ACTION);
        try {
            long current =
                    jdbc.queryForObject(
                            "SELECT v.scalar_value FROM rule_version v JOIN rule_definition d ON"
                                + " d.rule_id = v.rule_id WHERE d.rule_key = 'MAX_RETRO_PERIOD' AND"
                                + " v.approval_state = 'APPROVED' AND v.effective_to IS NULL",
                            Long.class);
            long approval = propose(admin, (int) (current == 60 ? 59 : current - 1));
            assertThat(approval).isPositive();
            JsonNode item = inboxItem(checker, approval);
            assertThat(item).isNotNull();
            assertThat(item.get("targetTatHours").asInt()).isEqualTo(48);
            Instant submitted = Instant.parse(item.get("submittedAt").asString());
            Instant due = Instant.parse(item.get("slaDueAt").asString());
            assertThat(Duration.between(submitted, due)).isEqualTo(Duration.ofHours(48));
            assertThat(item.get("ageDays").asLong()).isZero();
            assertThat(item.get("slaBreached").asBoolean()).isFalse();
            // A parameter change carries no amount.
            assertThat(item.get("impactValue").isNull()).isTrue();

            // Three days later the item is past its target and stays flagged until actioned.
            Instant threeDaysAgo = Instant.now().minus(3, ChronoUnit.DAYS);
            jdbc.update(
                    "UPDATE approval_queue SET submitted_at = ? WHERE approval_id = ?",
                    Timestamp.from(threeDaysAgo),
                    approval);
            jdbc.update(
                    "UPDATE workflow_inbox_item SET sla_due_at = ? WHERE approval_id = ?",
                    Timestamp.from(threeDaysAgo.plus(48, ChronoUnit.HOURS)),
                    approval);
            JsonNode late = inboxItem(checker, approval);
            assertThat(late.get("ageDays").asLong()).isEqualTo(3);
            assertThat(late.get("slaBreached").asBoolean()).isTrue();
            assertThat(decide(checker, approval, "REJECT", "IT: not needed", null)).isEqualTo(200);
            assertThat(inboxItem(checker, approval)).isNull();
        } finally {
            jdbc.update(
                    "UPDATE approval_workflow_action SET target_tat_hours = NULL WHERE action_code"
                            + " = ?",
                    ACTION);
        }
    }
}
