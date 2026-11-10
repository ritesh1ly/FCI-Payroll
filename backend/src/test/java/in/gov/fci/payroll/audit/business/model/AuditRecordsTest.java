package in.gov.fci.payroll.audit.business.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.audit.api.ChangeType;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * R-AUD: an audit row carries the entity, field, before / after (cut to 4000), who, when, the
 * effective date, the reason and the approval; every mandatory part is required. Security events
 * are cut to their columns.
 */
class AuditRecordsTest {

    private static final Instant NOW = Instant.parse("2026-10-21T05:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);

    private static AuditEvent event(String old, String now) {
        return AuditEvent.of(
                "employee",
                "105220",
                "mobile",
                old,
                now,
                "maker",
                NOW,
                DAY,
                "contact",
                4L,
                ChangeType.CHANGE,
                "10.0.0.1",
                "M-01",
                9L);
    }

    @Test
    void R_AUD_anAuditRowKeepsEveryPartOfTheChange() {
        AuditEvent e = event("9000000001", "9000000002");
        assertThat(e.getEntityName()).isEqualTo("employee");
        assertThat(e.getEntityKey()).isEqualTo("105220");
        assertThat(e.getFieldName()).isEqualTo("mobile");
        assertThat(e.getOldValue()).isEqualTo("9000000001");
        assertThat(e.getNewValue()).isEqualTo("9000000002");
        assertThat(e.getChangedBy()).isEqualTo("maker");
        assertThat(e.getChangedAt()).isEqualTo(NOW);
        assertThat(e.getEffectiveDate()).isEqualTo(DAY);
        assertThat(e.getReason()).isEqualTo("contact");
        assertThat(e.getApprovalId()).isEqualTo(4L);
        assertThat(e.getChangeType()).isEqualTo(ChangeType.CHANGE);
        assertThat(e.getSourceIp()).isEqualTo("10.0.0.1");
        assertThat(e.getModuleCode()).isEqualTo("M-01");
        assertThat(e.getLocationId()).isEqualTo(9L);
    }

    @Test
    void R_AUD_valuesAreCutTo4000AndMayBeAbsent() {
        assertThat(event("o".repeat(4001), "n".repeat(4000)).getOldValue()).hasSize(4000);
        assertThat(event("o".repeat(4001), "n".repeat(4000)).getNewValue()).hasSize(4000);
        assertThat(event(null, null).getOldValue()).isNull();
        assertThat(event(null, null).getNewValue()).isNull();
    }

    @Test
    void R_AUD_everyMandatoryPartIsRequired() {
        Object[][] cases = {
            {null, "k", "f", "by", NOW, DAY, "r", ChangeType.CHANGE},
            {"e", null, "f", "by", NOW, DAY, "r", ChangeType.CHANGE},
            {"e", "k", null, "by", NOW, DAY, "r", ChangeType.CHANGE},
            {"e", "k", "f", null, NOW, DAY, "r", ChangeType.CHANGE},
            {"e", "k", "f", "by", null, DAY, "r", ChangeType.CHANGE},
            {"e", "k", "f", "by", NOW, null, "r", ChangeType.CHANGE},
            {"e", "k", "f", "by", NOW, DAY, null, ChangeType.CHANGE},
            {"e", "k", "f", "by", NOW, DAY, "r", null},
        };
        for (Object[] c : cases) {
            assertThatThrownBy(
                            () ->
                                    AuditEvent.of(
                                            (String) c[0],
                                            (String) c[1],
                                            (String) c[2],
                                            null,
                                            null,
                                            (String) c[3],
                                            (Instant) c[4],
                                            (LocalDate) c[5],
                                            (String) c[6],
                                            null,
                                            (ChangeType) c[7],
                                            null,
                                            null,
                                            null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void securityEventsAreCutToTheirColumns() {
        SecurityEventRecord e =
                SecurityEventRecord.of(
                        "LOGIN_FAILED",
                        "u".repeat(60),
                        "t".repeat(120),
                        "k".repeat(120),
                        "d".repeat(2100),
                        "i".repeat(50),
                        NOW);
        assertThat(e.getEventType()).isEqualTo("LOGIN_FAILED");
        assertThat(e.getActingUser()).hasSize(50);
        assertThat(e.getTargetEntity()).hasSize(100);
        assertThat(e.getTargetKey()).hasSize(100);
        assertThat(e.getDetail()).hasSize(2000);
        assertThat((String) ReflectionTestUtils.getField(e, "sourceIp")).hasSize(45);
        assertThat(e.getOccurredAt()).isEqualTo(NOW);
        SecurityEventRecord shortOne =
                SecurityEventRecord.of("X", "u", null, null, null, null, NOW);
        assertThat(shortOne.getActingUser()).isEqualTo("u");
        assertThat(shortOne.getDetail()).isNull();
        assertThatThrownBy(() -> SecurityEventRecord.of(null, "u", null, null, null, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SecurityEventRecord.of("X", "u", null, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
