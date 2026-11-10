package in.gov.fci.payroll.increment.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * M-03 pay scales on X-04 and the M-03 History button (SRS-20-29, SRS-20-3; SRS M-03): a scale is
 * its approved versions with their stages (a version loaded without an approval item included, a
 * pending one left out), its audit rows found under every version id and every "CODE from DATE"
 * approval key; a scale without a version is a record without history; an unknown code none.
 */
class PayScaleHistoryTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final PayScaleHistory history = new PayScaleHistory(db.named);

    private static java.util.Map<String, Object> version(
            long id, int no, LocalDate from, LocalDate to, Long approval, String status) {
        return row(
                "band_version_id",
                id,
                "version_no",
                no,
                "min_basic",
                new BigDecimal("40000.00"),
                "max_basic",
                new BigDecimal("140000"),
                "stagnation_increment_amt",
                null,
                "effective_from",
                Date.valueOf(from),
                "effective_to",
                to == null ? null : Date.valueOf(to),
                "approval_id",
                approval,
                "status",
                status);
    }

    @Test
    void SRS_20_29_aScaleIsItsApprovedVersionsWithTheirStages() {
        db.rows(
                        "FROM pay_scale_band b",
                        version(
                                1L,
                                1,
                                LocalDate.of(2017, 1, 1),
                                LocalDate.of(2026, 12, 31),
                                null,
                                null),
                        version(2L, 2, LocalDate.of(2027, 1, 1), null, 70L, "APPROVED"),
                        version(3L, 3, LocalDate.of(2028, 1, 1), null, 71L, "PENDING_APPROVAL"))
                .rows(
                        "FROM pay_scale_band_stage",
                        row("stage_basic", new BigDecimal("40000")),
                        row("stage_basic", new BigDecimal("41200")));
        HistoryRecord record = history.find("E-4").orElseThrow();
        assertThat(history.entity()).isEqualTo("pay_scale_band_version");
        assertThat(record.isVersioned()).isTrue();
        assertThat(record.screen()).isEqualTo("M-03");
        assertThat(record.locationId()).isNull();
        assertThat(record.auditKeys())
                .containsExactly(
                        "1",
                        "E-4 from 2017-01-01",
                        "2",
                        "E-4 from 2027-01-01",
                        "3",
                        "E-4 from 2028-01-01");
        assertThat(record.versions())
                .extracting(v -> v.versionNo() + ":" + v.approvalId())
                .as("the pending version is not history")
                .containsExactly("1:null", "2:70");
        assertThat(record.versions().get(0).effectiveTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(record.versions().get(1).values())
                .containsEntry("min_basic", "40000.00")
                .containsEntry("max_basic", "140000")
                .containsEntry("stagnation_increment_amt", null)
                .containsEntry("stages", "40000,41200");
    }

    @Test
    void SRS_20_29_aScaleWithoutAVersionHasNoHistoryAndAnUnknownCodeIsNoRecord() {
        assertThat(history.find("NONE")).isEmpty();
        db.rows("FROM pay_scale_band b", row("band_version_id", null, "version_no", null));
        HistoryRecord record = history.find("E-9").orElseThrow();
        assertThat(record.versions()).isEmpty();
        assertThat(record.auditKeys()).isEmpty();
    }
}
