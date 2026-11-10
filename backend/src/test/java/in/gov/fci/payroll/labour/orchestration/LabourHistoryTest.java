package in.gov.fci.payroll.labour.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The labour masters on X-04 and their History buttons (SRS-20-29, SRS-20-3): an M-02 member and an
 * M-09 gang as one current row at their depot, found under every key their services audit under;
 * M-05 labour and NWNP rate sets and M-06 norm sets as their approved versions (a set loaded
 * without an approval item in force as loaded, a pending or rejected one left out); an unknown key
 * is no record.
 */
class LabourHistoryTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final LabourHistory config = new LabourHistory();

    @Test
    void SRS_20_29_aLabourMemberIsOneCurrentRowAtItsDepot() {
        RecordHistorySource source = config.labourProfileHistory(db.named);
        assertThat(source.entity()).isEqualTo("labour_profile");
        assertThat(source.find("DPS-00001")).isEmpty();
        db.rows(
                "FROM labour_profile p",
                row(
                        "employee_id",
                        501L,
                        "labour_number",
                        "DPS-00001",
                        "payroll_type",
                        "DPS_LABOUR",
                        "depot_location_id",
                        41L,
                        "labour_status",
                        "ACTIVE",
                        "pf_applicable_flag",
                        "Y",
                        "effective_from",
                        Timestamp.valueOf("2024-04-01 00:00:00"),
                        "designation_id",
                        null));
        HistoryRecord record = source.find("DPS-00001").orElseThrow();
        assertThat(record.isVersioned()).isFalse();
        assertThat(record.screen()).isEqualTo("M-02");
        assertThat(record.locationId()).isEqualTo(41L);
        assertThat(record.auditKeys()).containsExactly("DPS-00001", "501");
        assertThat(record.existsFrom()).isEqualTo(LocalDate.of(2024, 4, 1));
        assertThat(record.current())
                .containsEntry("labour_status", "ACTIVE")
                .containsEntry("designation_id", null)
                .containsEntry("pf_applicable_flag", "Y");
    }

    private static Map<String, Object> wage(
            long id, LocalDate from, Long approval, String status, String designation) {
        return row(
                "min_wage_id",
                id,
                "effective_from",
                from,
                "effective_to",
                null,
                "version_no",
                1,
                "approval_id",
                approval,
                "status",
                status,
                "interim_relief",
                new BigDecimal("150.00"),
                "goi_rate",
                null,
                "goi_rate_source",
                null,
                "comparison_grain",
                "MONTH",
                "designation_code",
                designation,
                "min_guaranteed_wage",
                new BigDecimal("700.50"));
    }

    @Test
    void SRS_20_29_aLabourRateSeriesIsItsApprovedSetsWithTheirDayRates() {
        RecordHistorySource source = config.labourRateHistory(db.named);
        assertThat(source.entity()).isEqualTo("labour_min_wage");
        assertThat(source.find("DPS_LABOUR@depot")).as("not a depot id").isEmpty();
        assertThat(source.find("DPS_LABOUR")).isEmpty();
        LocalDate first = LocalDate.of(2026, 4, 1);
        LocalDate second = LocalDate.of(2026, 10, 1);
        db.rows(
                "FROM labour_min_wage w",
                wage(10L, first, null, null, "HL"),
                wage(11L, first, null, null, "SR"),
                wage(20L, second, 90L, "REJECTED", "HL"));
        HistoryRecord all = source.find("DPS_LABOUR").orElseThrow();
        assertThat(all.isVersioned()).isTrue();
        assertThat(all.screen()).isEqualTo("M-05");
        assertThat(all.locationId()).isNull();
        assertThat(all.auditKeys()).containsExactly("10", "20");
        assertThat(all.versions()).hasSize(1);
        assertThat(all.versions().get(0).effectiveFrom()).isEqualTo(first);
        assertThat(all.versions().get(0).values())
                .containsEntry("interim_relief", "150.00")
                .containsEntry("goi_rate", null)
                .containsEntry("mgw.HL", "700.50")
                .containsEntry("mgw.SR", "700.50");
        assertThat(source.find("DPS_LABOUR@41").orElseThrow().key()).isEqualTo("DPS_LABOUR@41");
    }

    @Test
    void SRS_20_29_anNwnpRateSeriesIsItsApprovedSetsWithRatesAndMgw() {
        RecordHistorySource source = config.nwnpRateHistory(db.named);
        assertThat(source.entity()).isEqualTo("nwnp_rate_set");
        assertThat(source.find("NWNP-DP01-ASOR")).isEmpty();
        db.rows(
                        "FROM rate_set WHERE",
                        row(
                                "rate_set_id",
                                7L,
                                "version_no",
                                1,
                                "effective_from",
                                Date.valueOf(LocalDate.of(2026, 4, 1)),
                                "effective_to",
                                Date.valueOf(LocalDate.of(2026, 9, 30)),
                                "approval_id",
                                61L,
                                "approval_state",
                                "APPROVED"),
                        row(
                                "rate_set_id",
                                8L,
                                "version_no",
                                2,
                                "effective_from",
                                Date.valueOf(LocalDate.of(2026, 10, 1)),
                                "effective_to",
                                null,
                                "approval_id",
                                62L,
                                "approval_state",
                                "PENDING_APPROVAL"))
                .rows(
                        "FROM nwnp_sor_rate",
                        row(
                                "rate_set_id",
                                7L,
                                "operation_code",
                                "UNLOAD",
                                "rate_per_unit",
                                new BigDecimal("2.75"),
                                "unit_of_measure",
                                "BAG"))
                .rows(
                        "w.rate_set_id IN",
                        row(
                                "rate_set_id",
                                7L,
                                "designation_code",
                                "HL",
                                "min_guaranteed_wage",
                                new BigDecimal("650")));
        HistoryRecord record = source.find("NWNP-DP01-ASOR").orElseThrow();
        assertThat(record.auditKeys()).containsExactly("7", "8");
        assertThat(record.versions()).hasSize(1);
        assertThat(record.versions().get(0).approvalId()).isEqualTo(61L);
        assertThat(record.versions().get(0).values())
                .containsEntry("rate.UNLOAD", "2.75 / BAG")
                .containsEntry("mgw.HL", "650");
    }

    @Test
    void SRS_20_29_aDepotsNormSeriesIsItsApprovedSetsWithSlabsAtTheDepot() {
        RecordHistorySource source = config.normHistory(db.named);
        assertThat(source.entity()).isEqualTo("labour_norm");
        assertThat(source.find("DPT01")).isEmpty();
        db.rows(
                        "FROM labour_norm n",
                        row(
                                "norm_id", 3L,
                                "depot_location_id", 41L,
                                "version_no", 1,
                                "effective_from", Date.valueOf(LocalDate.of(2026, 4, 1)),
                                "effective_to", null,
                                "approval_id", 80L,
                                "status", "APPROVED",
                                "handling_norm", 90L,
                                "height_norm", 12L,
                                "lead_norm", 30L,
                                "min_labour_number", 10L,
                                "min_labour_slab_weight", new BigDecimal("8.5"),
                                "mou_reference", null),
                        row(
                                "norm_id", 4L,
                                "depot_location_id", 41L,
                                "version_no", 2,
                                "effective_from", Date.valueOf(LocalDate.of(2027, 4, 1)),
                                "effective_to", null,
                                "approval_id", 81L,
                                "status", "PENDING_APPROVAL",
                                "handling_norm", 95L,
                                "height_norm", 12L,
                                "lead_norm", 30L,
                                "min_labour_number", 10L,
                                "min_labour_slab_weight", null,
                                "mou_reference", "MOU-2027"))
                .rows(
                        "FROM labour_above_norm_slab",
                        row(
                                "norm_id",
                                3L,
                                "slab_table_ref",
                                "BAND_1",
                                "band_order",
                                1,
                                "range_from",
                                1L,
                                "range_to",
                                30L,
                                "slab_rate_percent",
                                new BigDecimal("8")),
                        row(
                                "norm_id",
                                3L,
                                "slab_table_ref",
                                "BAND_1",
                                "band_order",
                                2,
                                "range_from",
                                31L,
                                "range_to",
                                null,
                                "slab_rate_percent",
                                new BigDecimal("15")));
        HistoryRecord record = source.find("DPT01").orElseThrow();
        assertThat(record.screen()).isEqualTo("M-06");
        assertThat(record.locationId()).isEqualTo(41L);
        assertThat(record.auditKeys()).containsExactly("3", "4");
        assertThat(record.versions()).hasSize(1);
        assertThat(record.versions().get(0).values())
                .containsEntry("handling_norm", "90")
                .containsEntry("min_labour_slab_weight", "8.5")
                .containsEntry("mou_reference", null)
                .containsEntry("slab.BAND_1.1", "1-30 @ 8%")
                .containsEntry("slab.BAND_1.2", "31- @ 15%");
    }

    @Test
    void SRS_20_29_aGangIsOneCurrentRowFoundUnderItsCodeIdAndMemberKeys() {
        RecordHistorySource source = config.gangHistory(db.named);
        assertThat(source.entity()).isEqualTo("labour_gang");
        assertThat(source.find("DPT01-G001")).isEmpty();
        db.rows(
                        "FROM labour_gang g",
                        row(
                                "gang_id",
                                12L,
                                "gang_name",
                                "Gang A",
                                "depot_location_id",
                                41L,
                                "payroll_type",
                                "DEPT_LABOUR",
                                "gang_type",
                                "HANDLING",
                                "gang_status",
                                "ACTIVE",
                                "effective_from",
                                Date.valueOf(LocalDate.of(2026, 4, 1)),
                                "sanctioned_strength",
                                null))
                .rows("FROM labour_gang_membership", row("employee_id", 501L));
        HistoryRecord record = source.find("DPT01-G001").orElseThrow();
        assertThat(record.isVersioned()).isFalse();
        assertThat(record.screen()).isEqualTo("M-09");
        assertThat(record.locationId()).isEqualTo(41L);
        assertThat(record.existsFrom()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(record.auditKeys())
                .containsExactly(
                        "DPT01-G001",
                        "12",
                        "12:SARDAR",
                        "12:MANDAL",
                        "12:HANDLING",
                        "12:ANCILLARY",
                        "12:501");
        assertThat(record.current())
                .containsEntry("gang_status", "ACTIVE")
                .containsEntry("sanctioned_strength", null);
    }
}
