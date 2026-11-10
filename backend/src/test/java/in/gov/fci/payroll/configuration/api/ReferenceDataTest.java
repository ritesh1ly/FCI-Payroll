package in.gov.fci.payroll.configuration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.cache.CacheProperties;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.config.ApiProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * The reference lists as the screens and validators read them (SRS-20-5): a value labels in Hindi
 * only when it has a Hindi label, a value no longer in force is kept for old records but offered to
 * nobody, and an absent sort order stays absent.
 */
class ReferenceDataTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private ReferenceData data() {
        ApiProperties properties = mock(ApiProperties.class);
        when(properties.businessZone()).thenReturn(ZoneOffset.UTC);
        CacheRegions regions = mock(CacheRegions.class);
        when(regions.properties())
                .thenReturn(new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15)));
        when(regions.version(ReferenceData.REGION)).thenReturn(1L);
        return new ReferenceData(
                jdbc,
                properties,
                Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC),
                regions);
    }

    private static ResultSet head(String code) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(code);
        when(rs.getString(2)).thenReturn("Claim type");
        when(rs.getString(3)).thenReturn("M-30");
        when(rs.getString(4)).thenReturn("N");
        return rs;
    }

    private static ResultSet value(String list, long id, String code, Integer sort, boolean inForce)
            throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(list);
        when(rs.getLong(2)).thenReturn(id);
        when(rs.getString(3)).thenReturn(code);
        when(rs.getString(4)).thenReturn(code + " label");
        when(rs.getInt(5)).thenReturn(sort == null ? 0 : sort);
        when(rs.wasNull()).thenReturn(sort == null);
        when(rs.getInt(8)).thenReturn(inForce ? 1 : 0);
        return rs;
    }

    /** The database answers one list head and these value rows (no Hindi labels). */
    private void database(ResultSet head, ResultSet... values) {
        doAnswer(
                        invocation -> {
                            RowCallbackHandler each = invocation.getArgument(1);
                            each.processRow(head);
                            return null;
                        })
                .when(jdbc)
                .query(startsWith("SELECT list_code"), any(RowCallbackHandler.class));
        doAnswer(
                        invocation -> {
                            RowCallbackHandler each = invocation.getArgument(1);
                            for (ResultSet row : values) {
                                each.processRow(row);
                            }
                            return null;
                        })
                .when(jdbc)
                .query(
                        startsWith("SELECT l.list_code"),
                        any(RowCallbackHandler.class),
                        any(Object[].class));
    }

    @Test
    void aValueLabelsInHindiOnlyWhenItHasAHindiLabel() {
        ReferenceData.Value both =
                new ReferenceData.Value("MED", "Medical", "Medical (hi)", 1, null, null, true);
        ReferenceData.Value englishOnly =
                new ReferenceData.Value("MED", "Medical", null, 1, null, null, true);
        assertThat(both.label("hi")).isEqualTo("Medical (hi)");
        assertThat(both.label("HI")).isEqualTo("Medical (hi)");
        assertThat(both.label("en")).isEqualTo("Medical");
        assertThat(both.label(null)).isEqualTo("Medical");
        assertThat(englishOnly.label("hi")).isEqualTo("Medical");
    }

    @Test
    void containsAcceptsOnlyAValueInForceTodayAndNeverANullCode() throws SQLException {
        database(
                head("CLAIM_TYPE"),
                value("CLAIM_TYPE", 1L, "MED", 1, true),
                value("CLAIM_TYPE", 2L, "OLD", null, false));
        ReferenceData data = data();

        assertThat(data.contains("CLAIM_TYPE", "MED")).isTrue();
        assertThat(data.contains("CLAIM_TYPE", "OLD")).as("a value no longer in force").isFalse();
        assertThat(data.contains("CLAIM_TYPE", null)).isFalse();
        assertThat(data.contains("NO_SUCH_LIST", "MED")).isFalse();
        assertThat(data.list("CLAIM_TYPE"))
                .hasValueSatisfying(
                        list -> {
                            assertThat(list.system()).isFalse();
                            assertThat(list.values())
                                    .extracting(
                                            ReferenceData.Value::code,
                                            ReferenceData.Value::sortOrder,
                                            ReferenceData.Value::labelHi,
                                            ReferenceData.Value::active)
                                    .containsExactly(
                                            tuple("MED", 1, null, true),
                                            tuple("OLD", null, null, false));
                        });
    }

    /**
     * SRS l.650 (US-GAP-119): a back-dated transaction checks its value against the list version in
     * force on its own effective date - the lists are read for that date (bound as the date, not
     * today), so a value ended since still validates a transaction dated while it was in force, and
     * one not yet in force then does not; no date is today. SC-01-0068 (UC postcondition l.650):
     * the list is versioned and effective-dated, each screen using the version in force on its
     * date.
     */
    @Test
    void aBackDatedTransactionUsesTheListVersionInForceOnItsEffectiveDate() throws SQLException {
        LocalDate effective = TODAY.minusMonths(6);
        ResultSet head = head("CLAIM_TYPE");
        ResultSet endedSince = value("CLAIM_TYPE", 2L, "OLD", null, true);
        ResultSet newer = value("CLAIM_TYPE", 3L, "NEW", null, false);
        java.util.List<Object> boundDates = new java.util.ArrayList<>();
        doAnswer(
                        invocation -> {
                            RowCallbackHandler each = invocation.getArgument(1);
                            each.processRow(head);
                            return null;
                        })
                .when(jdbc)
                .query(startsWith("SELECT list_code"), any(RowCallbackHandler.class));
        doAnswer(
                        invocation -> {
                            boundDates.add(invocation.getArgument(2));
                            RowCallbackHandler each = invocation.getArgument(1);
                            each.processRow(endedSince);
                            each.processRow(newer);
                            return null;
                        })
                .when(jdbc)
                .query(
                        startsWith("SELECT l.list_code"),
                        any(RowCallbackHandler.class),
                        any(Object[].class));
        ReferenceData data = data();

        assertThat(data.contains("CLAIM_TYPE", "OLD", effective)).isTrue();
        assertThat(data.contains("CLAIM_TYPE", "NEW", effective)).isFalse();
        assertThat(data.contains("CLAIM_TYPE", null, effective)).isFalse();
        assertThat(data.contains("NO_SUCH_LIST", "OLD", effective)).isFalse();
        assertThat(data.snapshot(effective).asOf()).isEqualTo(effective);
        assertThat(boundDates).containsExactly(java.sql.Date.valueOf(effective));
        assertThat(data.snapshot(null).asOf()).isEqualTo(TODAY);
    }

    /**
     * SRS-20-5: the Hindi labels are read by value id; a label row whose master code is not a value
     * id (text, blank, null) belongs to another master and is ignored, never failing the load.
     */
    @Test
    void SRS_20_5_aHindiLabelRowWhoseMasterCodeIsNotAValueIdIsIgnoredAndTheLoadSucceeds()
            throws SQLException {
        database(head("CLAIM_TYPE"), value("CLAIM_TYPE", 1L, "MED", 1, true));
        doAnswer(
                        invocation -> {
                            RowCallbackHandler each = invocation.getArgument(1);
                            for (String[] row :
                                    new String[][] {
                                        {"CLAIM-TEXT", "not a value id"},
                                        {"", "blank id"},
                                        {null, "null id"},
                                        {"1", "Medical (hi)"},
                                        {"99", "label of a value that does not exist"}
                                    }) {
                                ResultSet rs = mock(ResultSet.class);
                                when(rs.getString(1)).thenReturn(row[0]);
                                when(rs.getString(2)).thenReturn(row[1]);
                                each.processRow(rs);
                            }
                            return null;
                        })
                .when(jdbc)
                .query(
                        startsWith("SELECT master_code"),
                        any(RowCallbackHandler.class),
                        any(Object[].class));
        ReferenceData data = data();

        assertThat(data.list("CLAIM_TYPE"))
                .hasValueSatisfying(
                        list ->
                                assertThat(list.values())
                                        .extracting(
                                                ReferenceData.Value::code,
                                                ReferenceData.Value::labelHi)
                                        .containsExactly(tuple("MED", "Medical (hi)")));
    }

    @Test
    void SRS_20_5_aListWithNoValuesIsKnownButHasNothingToOffer() throws SQLException {
        database(head("EMPTY_LIST"));
        ReferenceData data = data();
        assertThat(data.list("EMPTY_LIST"))
                .hasValueSatisfying(list -> assertThat(list.values()).isEmpty());
        assertThat(data.contains("EMPTY_LIST", "ANY")).isFalse();
        assertThat(data.contains("EMPTY_LIST", "")).isFalse();
    }

    @Test
    void SRS_20_5_aValueCodeIsMatchedExactlyNotByCaseOrPrefix() throws SQLException {
        database(head("CLAIM_TYPE"), value("CLAIM_TYPE", 1L, "MED", 1, true));
        ReferenceData data = data();
        assertThat(data.contains("CLAIM_TYPE", "med")).isFalse();
        assertThat(data.contains("CLAIM_TYPE", "ME")).isFalse();
        assertThat(data.contains("CLAIM_TYPE", "MED ")).isFalse();
        assertThat(data.contains("claim_type", "MED")).isFalse();
    }
}
