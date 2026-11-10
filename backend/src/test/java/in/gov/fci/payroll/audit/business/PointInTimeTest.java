package in.gov.fci.payroll.audit.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.audit.api.RecordHistorySource.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SRS-20-3, SRS-20-6, BR-AUDIT-007: point-in-time reconstruction of master and rule records. */
class PointInTimeTest {

    private static final LocalDate APR = LocalDate.of(2026, 4, 1);
    private static final LocalDate OCT = LocalDate.of(2026, 10, 1);

    private static Version v(int no, LocalDate from, LocalDate to, String value) {
        return new Version(no, from, to, (long) no, Map.of("value", value));
    }

    @Test
    void SRS_20_6_exactlyTheVersionInForceOnTheDateFromAndToBothInclusive() {
        List<Version> versions = List.of(v(1, APR, OCT.minusDays(1), "5"), v(2, OCT, null, "10"));
        assertThat(PointInTime.asOf(versions, APR))
                .isEqualTo(
                        new PointInTime.Found(1, APR, OCT.minusDays(1), 1L, Map.of("value", "5")));
        assertThat(PointInTime.asOf(versions, OCT.minusDays(1)))
                .isInstanceOfSatisfying(
                        PointInTime.Found.class, f -> assertThat(f.versionNo()).isEqualTo(1));
        assertThat(PointInTime.asOf(versions, OCT))
                .isInstanceOfSatisfying(
                        PointInTime.Found.class, f -> assertThat(f.versionNo()).isEqualTo(2));
        assertThat(PointInTime.asOf(versions, LocalDate.of(2099, 1, 1)))
                .isInstanceOfSatisfying(
                        PointInTime.Found.class, f -> assertThat(f.versionNo()).isEqualTo(2));
    }

    @Test
    void SRS_20_6_beforeTheFirstVersionOrAfterTheLastEndsTheRecordIsNotInForce() {
        assertThat(PointInTime.asOf(List.of(v(1, APR, OCT, "5")), APR.minusDays(1)))
                .isInstanceOf(PointInTime.NotInForce.class);
        assertThat(PointInTime.asOf(List.of(v(1, APR, OCT, "5")), OCT.plusDays(1)))
                .isInstanceOf(PointInTime.NotInForce.class);
        assertThat(PointInTime.asOf(List.of(), APR)).isInstanceOf(PointInTime.NotInForce.class);
    }

    /**
     * SRS-20-6 (US-GAP-132): "exactly one active version per key per date" - on a date before the
     * first version, or in a gap between an ended version and the next, no other version is
     * returned in its place (neither the earlier nor the later one).
     */
    @Test
    void SRS_20_6_beforeTheFirstVersionOrInAGapNoOtherVersionIsReturned() {
        LocalDate firstEnds = APR.plusMonths(2).minusDays(1);
        List<Version> versions = List.of(v(1, APR, firstEnds, "5"), v(2, OCT, null, "10"));
        // the day before the first version, the day after the first ends, the day before the next
        for (LocalDate day : List.of(APR.minusDays(1), firstEnds.plusDays(1), OCT.minusDays(1))) {
            assertThat(PointInTime.asOf(versions, day))
                    .as(day.toString())
                    .isEqualTo(new PointInTime.NotInForce());
        }
        // either edge of the gap still returns its own version, and only that one
        assertThat(PointInTime.asOf(versions, firstEnds))
                .isInstanceOfSatisfying(
                        PointInTime.Found.class, f -> assertThat(f.versionNo()).isEqualTo(1));
        assertThat(PointInTime.asOf(versions, OCT))
                .isInstanceOfSatisfying(
                        PointInTime.Found.class, f -> assertThat(f.versionNo()).isEqualTo(2));
    }

    @Test
    void SRS_20_6_twoVersionsOnOneDateAreReportedAsAnOverlapNeverResolvedByAPick() {
        List<Version> overlapping =
                List.of(v(3, APR, null, "x"), v(1, APR, OCT, "y"), v(2, OCT, null, "z"));
        assertThat(PointInTime.asOf(overlapping, OCT))
                .isEqualTo(new PointInTime.Overlap(List.of(1, 2, 3)));
        assertThat(PointInTime.asOf(overlapping, APR))
                .isEqualTo(new PointInTime.Overlap(List.of(1, 3)));
    }

    private static PointInTime.FieldChange change(
            long id, String field, String old, String now, LocalDate effective, boolean initial) {
        return new PointInTime.FieldChange(
                id, field, old, now, effective, Instant.parse("2026-01-01T00:00:00Z"), initial);
    }

    @Test
    void SRS_20_3_aRecordKeptAsOneRowIsRebuiltBackwardsFromItsFieldRows() {
        Map<String, String> current = new LinkedHashMap<>();
        current.put("mobile", "9000000333");
        current.put("email", "new.mail@fci-demo.test");
        current.put("status", "IN_SERVICE");
        List<PointInTime.FieldChange> changes =
                List.of(
                        change(1, "mobile", "9000000111", "9000000222", APR, false),
                        change(2, "mobile", "9000000222", "9000000333", OCT, false),
                        change(
                                3,
                                "email",
                                "old.mail@fci-demo.test",
                                "new.mail@fci-demo.test",
                                OCT,
                                false));
        // Before every change: the old value of the first change; a field never changed: current.
        assertThat(PointInTime.reconstruct(current, changes, APR.minusDays(1)))
                .contains(
                        Map.of(
                                "email",
                                "old.mail@fci-demo.test",
                                "mobile",
                                "9000000111",
                                "status",
                                "IN_SERVICE"));
        // On a change's effective date the change applies.
        assertThat(PointInTime.reconstruct(current, changes, APR).orElseThrow())
                .containsEntry("mobile", "9000000222")
                .containsEntry("email", "old.mail@fci-demo.test");
        assertThat(PointInTime.reconstruct(current, changes, OCT).orElseThrow())
                .containsEntry("mobile", "9000000333")
                .containsEntry("email", "new.mail@fci-demo.test");
    }

    @Test
    void SRS_20_3_changesOnOneDateApplyInTheOrderTheyWereRecordedAndANullValueStaysNull() {
        Map<String, String> current = new LinkedHashMap<>();
        current.put("label", null);
        Instant early = Instant.parse("2026-10-01T09:00:00Z");
        Instant late = Instant.parse("2026-10-01T17:00:00Z");
        List<PointInTime.FieldChange> changes =
                List.of(
                        new PointInTime.FieldChange(9, "label", "B", null, OCT, late, false),
                        new PointInTime.FieldChange(8, "label", "A", "B", OCT, early, false));
        Map<String, String> onTheDay = PointInTime.reconstruct(current, changes, OCT).orElseThrow();
        assertThat(onTheDay).containsKey("label");
        assertThat(onTheDay.get("label")).isNull();
        assertThat(PointInTime.reconstruct(current, changes, OCT.minusDays(1)).orElseThrow())
                .containsEntry("label", "A");
    }

    @Test
    void SRS_20_3_aRecordWhoseFirstValuesTakeEffectLaterDidNotExistYet() {
        Map<String, String> current = Map.of("name", "Zonal Office");
        List<PointInTime.FieldChange> created =
                List.of(change(1, "name", null, "Zonal Office", OCT, true));
        assertThat(PointInTime.reconstruct(current, created, OCT.minusDays(1))).isEmpty();
        assertThat(PointInTime.reconstruct(current, created, OCT).orElseThrow())
                .containsEntry("name", "Zonal Office");
        // Without an initial-value row the record is taken to have existed.
        assertThat(PointInTime.reconstruct(current, List.of(), APR)).contains(current);
    }
}
