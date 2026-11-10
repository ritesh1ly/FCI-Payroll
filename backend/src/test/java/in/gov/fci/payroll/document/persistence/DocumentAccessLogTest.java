package in.gov.fci.payroll.document.persistence;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SRS-G-31: every view or download of an attachment is logged with who, when and from where, each
 * value cut to its column (accessed_by 50, source_ip 45) so a long login or address never loses the
 * log row.
 */
class DocumentAccessLogTest {

    private static final Instant NOW = Instant.parse("2026-10-23T05:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DocumentAccessLog log =
            new DocumentAccessLog(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void SRS_G_31_aViewIsLoggedWithWhoWhenAndFromWhere() {
        log.record(5L, "checker@fci.gov.in", DocumentAccessLog.AccessType.VIEW, "10.0.0.7");
        verify(jdbc)
                .update(
                        anyString(),
                        org.mockito.ArgumentMatchers.eq(5L),
                        org.mockito.ArgumentMatchers.eq("checker@fci.gov.in"),
                        org.mockito.ArgumentMatchers.eq("VIEW"),
                        org.mockito.ArgumentMatchers.eq("10.0.0.7"),
                        org.mockito.ArgumentMatchers.eq(Timestamp.from(NOW)));
    }

    @Test
    void SRS_G_31_valuesAtTheirColumnLengthAreKeptAndLongerOnesCut() {
        String login50 = "a".repeat(50);
        String ip45 = "f".repeat(45);
        log.record(5L, login50, DocumentAccessLog.AccessType.DOWNLOAD, ip45);
        verify(jdbc)
                .update(
                        anyString(),
                        org.mockito.ArgumentMatchers.eq(5L),
                        org.mockito.ArgumentMatchers.eq(login50),
                        org.mockito.ArgumentMatchers.eq("DOWNLOAD"),
                        org.mockito.ArgumentMatchers.eq(ip45),
                        org.mockito.ArgumentMatchers.eq(Timestamp.from(NOW)));
        log.record(6L, login50 + "b", DocumentAccessLog.AccessType.DELETE_ATTEMPT, ip45 + "0");
        verify(jdbc)
                .update(
                        anyString(),
                        org.mockito.ArgumentMatchers.eq(6L),
                        org.mockito.ArgumentMatchers.eq(login50),
                        org.mockito.ArgumentMatchers.eq("DELETE_ATTEMPT"),
                        org.mockito.ArgumentMatchers.eq(ip45),
                        org.mockito.ArgumentMatchers.eq(Timestamp.from(NOW)));
        // A request without a known address is logged without one.
        log.record(7L, "batch", DocumentAccessLog.AccessType.VIEW, null);
        verify(jdbc)
                .update(
                        anyString(),
                        org.mockito.ArgumentMatchers.eq(7L),
                        org.mockito.ArgumentMatchers.eq("batch"),
                        org.mockito.ArgumentMatchers.eq("VIEW"),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.eq(Timestamp.from(NOW)));
    }
}
