package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.configuration.business.model.LabelMaster;
import in.gov.fci.payroll.configuration.business.model.NetPayFloorRule;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The field rules of the Module 04 masters (M-14, M-15, M-25, M-26, M-27), without a database. */
class MdmRulesTest {

    private static final LocalDate FROM = LocalDate.of(2091, 1, 1);

    private static ApiErrorCode code(Throwable t) {
        return (ApiErrorCode) ((FciException) t).getErrorCode();
    }

    // ---------------------------------------------------------------- M-14

    private static NetPayFloorService.ProposedFloor floor(String basis, String value, String pct) {
        return new NetPayFloorService.ProposedFloor(
                FROM,
                basis,
                value == null ? null : new BigDecimal(value),
                pct == null ? null : new BigDecimal(pct),
                null,
                false,
                "r");
    }

    @Test
    void SRS_16_13_aFloorIsZeroOrMoreAPercentageAtMostAHundredAndNoneTakesNoValue() {
        assertThat(NetPayFloorService.validate(floor("ABSOLUTE_AMOUNT", "0", null)))
                .isEqualTo(NetPayFloorRule.Basis.ABSOLUTE_AMOUNT);
        assertThat(NetPayFloorService.validate(floor("PERCENT_OF_GROSS", "100", "33.3333")))
                .isEqualTo(NetPayFloorRule.Basis.PERCENT_OF_GROSS);
        assertThat(NetPayFloorService.validate(floor("NONE", null, null)))
                .isEqualTo(NetPayFloorRule.Basis.NONE);
        for (NetPayFloorService.ProposedFloor bad :
                List.of(
                        floor("ABSOLUTE_AMOUNT", "-0.01", null),
                        floor("ABSOLUTE_AMOUNT", null, null),
                        floor("ABSOLUTE_AMOUNT", "1.001", null),
                        floor("ABSOLUTE_AMOUNT", "12345678901", null),
                        floor("PERCENT_OF_GROSS", "100.01", null),
                        floor("ABSOLUTE_AMOUNT", "10", "100.0001"),
                        floor("ABSOLUTE_AMOUNT", "10", "-1"),
                        floor("ABSOLUTE_AMOUNT", "10", "1.00001"),
                        floor("PER_DAY", "10", null),
                        floor(null, "10", null))) {
            assertThatThrownBy(() -> NetPayFloorService.validate(bad))
                    .satisfies(
                            e -> assertThat(code(e)).isEqualTo(ApiErrorCode.MASTER_INVALID_FLOOR));
        }
    }

    // ---------------------------------------------------------------- M-15

    private static CeilingNormService.ProposedNorm norm(
            String value, String periodicity, String scope) {
        return new CeilingNormService.ProposedNorm(
                FROM,
                null,
                value == null ? null : new BigDecimal(value),
                periodicity,
                "CAT",
                scope,
                false,
                "r",
                41L);
    }

    @Test
    void SRS_16_14_aCeilingIsAboveZeroWithAPeriodicityAndMemberScopeOfTheDdlLists() {
        CeilingNormService.validate(norm("0.01", "MONTHLY", null));
        CeilingNormService.validate(norm("9999999999.99", "BLOCK_YEAR_4", "LABOUR"));
        CeilingNormService.validate(norm("1", "ONE_TIME_PER_EVENT", " "));
        for (CeilingNormService.ProposedNorm bad :
                List.of(
                        norm("0", "MONTHLY", null),
                        norm(null, "MONTHLY", null),
                        norm("-5", "MONTHLY", null),
                        norm("1.005", "MONTHLY", null),
                        norm("10000000000", "MONTHLY", null),
                        norm("10", "WEEKLY", null),
                        norm("10", null, null),
                        norm("10", "MONTHLY", "STAFF"))) {
            assertThatThrownBy(() -> CeilingNormService.validate(bad))
                    .satisfies(
                            e ->
                                    assertThat(code(e))
                                            .isEqualTo(ApiErrorCode.MASTER_INVALID_CEILING));
        }
    }

    // ---------------------------------------------------------------- M-27

    private static OperationService.ProposedOperation op(
            boolean dept,
            boolean bag,
            String link,
            String norm,
            boolean dusting,
            Integer payCode,
            boolean pbnw) {
        return new OperationService.ProposedOperation(
                FROM, "Op", dept, false, false, bag, norm, link, dusting, payCode, pbnw, 1L, "r");
    }

    @Test
    void SRS_G_41_anOperationAppliesToALabourPayrollAndABagBasedOneLinksANormSlab() {
        OperationService.Particulars p =
                OperationService.validate(
                        op(true, true, "NORM_SLAB", "HANDLING", false, null, false));
        assertThat(p.link())
                .isEqualTo(
                        in.gov.fci.payroll.configuration.business.model.OperationMaster.RateNormLink
                                .NORM_SLAB);
        assertThat(p.normType()).isNotNull();
        assertThat(
                        OperationService.validate(
                                        op(true, false, "TIME_BASED", " ", false, null, false))
                                .normType())
                .isNull();
        OperationService.validate(op(true, false, "ALLOWANCE", null, true, 140, false));
        OperationService.validate(op(true, false, "NONE", null, false, null, true));
        // Any one labour payroll is enough: DPS alone, or NWNP alone.
        OperationService.validate(
                new OperationService.ProposedOperation(
                        FROM,
                        "Op",
                        false,
                        true,
                        false,
                        false,
                        null,
                        "TIME_BASED",
                        false,
                        null,
                        false,
                        1L,
                        "r"));
        OperationService.validate(
                new OperationService.ProposedOperation(
                        FROM,
                        "Op",
                        false,
                        false,
                        true,
                        false,
                        null,
                        "TIME_BASED",
                        false,
                        null,
                        false,
                        1L,
                        "r"));
        for (OperationService.ProposedOperation bad :
                List.of(
                        op(false, false, "TIME_BASED", null, false, null, false),
                        op(true, true, "TIME_BASED", null, false, null, false),
                        op(true, false, null, null, false, null, false),
                        op(true, false, "BY_WEIGHT", null, false, null, false),
                        op(true, false, "TIME_BASED", "VOLUME", false, null, false),
                        op(true, false, "ALLOWANCE", null, true, null, false),
                        op(true, false, "ALLOWANCE", null, true, 140, true))) {
            assertThatThrownBy(() -> OperationService.validate(bad))
                    .satisfies(e -> assertThat(code(e)).isEqualTo(ApiErrorCode.OPERATION_INVALID));
        }
    }

    @Test
    void SRS_G_41_theEndDateProposalCarriesItsLastDayAndNothingElseIsRead() {
        assertThat(OperationService.lastDayOf("{\"lastDay\":\"2095-12-31\"}"))
                .isEqualTo(LocalDate.of(2095, 12, 31));
        assertThat(OperationService.lastDayOf("{}")).isNull();
        assertThat(OperationService.lastDayOf("{\"lastDay\":\"2095-13-40\"}")).isNull();
        assertThat(OperationService.lastDayOf("{\"lastDay\":\"2095-12-31")).isNull();
    }

    // ---------------------------------------------------------------- M-25

    @Test
    void SRS_G_38_theOrderCopyIsAPdfByItsContentNotItsName() {
        assertThat(CircularService.isPdf("%PDF-1.7 x".getBytes(StandardCharsets.US_ASCII)))
                .isTrue();
        assertThat(CircularService.isPdf("%PDF-".getBytes(StandardCharsets.US_ASCII))).isFalse();
        assertThat(
                        CircularService.isPdf(
                                "PK\u0003\u0004 zip".getBytes(StandardCharsets.ISO_8859_1)))
                .isFalse();
        assertThat(CircularService.isPdf(new byte[0])).isFalse();
    }

    // ---------------------------------------------------------------- M-26

    private static LabelService.ProposedLabel label(
            String key, String type, String scope, String text, String entity, String code) {
        return new LabelService.ProposedLabel(
                key, type, entity, code, "HI", scope, text, true, FROM, "r");
    }

    @Test
    void SRS_G_39_aLabelHasAKeyTypeLanguageAndTextAndAMasterValueNamesItsMasterAndCode() {
        LabelService.Checked ui =
                LabelService.validate(label("LBL.A-b_1", "UI_LABEL", null, " पाठ ", "X", "Y"));
        assertThat(ui.scope()).isEqualTo(LabelMaster.Scope.GLOBAL);
        assertThat(LabelService.validate(label("K", "UI_LABEL", "  ", "t", null, null)).scope())
                .isEqualTo(LabelMaster.Scope.GLOBAL);
        assertThatThrownBy(() -> LabelService.validate(null))
                .satisfies(
                        e ->
                                assertThat(((FciException) e).getErrorCode())
                                        .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
        assertThat(ui.text()).isEqualTo("पाठ");
        // Only a master value carries its master, code and the localisable flag.
        assertThat(ui.masterEntity()).isNull();
        assertThat(ui.localisable()).isFalse();
        LabelService.Checked value =
                LabelService.validate(
                        label(
                                "DESIG.SARDAR",
                                "MASTER_VALUE",
                                "OFFICE",
                                "मुकादम",
                                "ORG_DESIGNATION",
                                "SARDAR"));
        assertThat(value.localisable()).isTrue();
        assertThat(value.masterCode()).isEqualTo("SARDAR");
        for (LabelService.ProposedLabel bad :
                List.of(
                        label("bad key", "UI_LABEL", null, "t", null, null),
                        label(".dot", "UI_LABEL", null, "t", null, null),
                        label("K", "LABEL", null, "t", null, null),
                        label("K", "UI_LABEL", "COUNTRY", "t", null, null),
                        label("K", "UI_LABEL", null, " ", null, null),
                        label("K", "UI_LABEL", null, "a\u0007b", null, null),
                        label("K", "UI_LABEL", null, "x".repeat(501), null, null),
                        label("K", "MASTER_VALUE", null, "t", null, "C"),
                        label("K", "MASTER_VALUE", null, "t", "E", "bad code"),
                        label("K", "MASTER_VALUE", null, "t", "bad entity", "C"),
                        label("K", "MASTER_VALUE", null, "t", "E", null),
                        label(null, "UI_LABEL", null, "t", null, null),
                        label("K", "UI_LABEL", null, null, null, null),
                        new LabelService.ProposedLabel(
                                "K", "UI_LABEL", null, null, null, null, "t", false, FROM, "r"),
                        new LabelService.ProposedLabel(
                                "K", "UI_LABEL", null, null, "H I", null, "t", false, FROM, "r"))) {
            assertThatThrownBy(() -> LabelService.validate(bad))
                    .satisfies(e -> assertThat(code(e)).isEqualTo(ApiErrorCode.LABEL_INVALID));
        }
        assertThatThrownBy(
                        () ->
                                LabelService.validate(
                                        label("K", "UI_LABEL", "REGION", "t", null, null)))
                .satisfies(
                        e -> assertThat(code(e)).isEqualTo(ApiErrorCode.LABEL_SCOPE_NOT_ALLOWED));
        assertThatThrownBy(
                        () ->
                                LabelService.validate(
                                        new LabelService.ProposedLabel(
                                                "K",
                                                "UI_LABEL",
                                                null,
                                                null,
                                                "HI",
                                                null,
                                                "t",
                                                false,
                                                null,
                                                "r")))
                .satisfies(
                        e ->
                                assertThat(((FciException) e).getErrorCode())
                                        .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }

    private static LabelMaster version(
            String language,
            LabelMaster.Scope scope,
            Long location,
            String text,
            LocalDate from,
            boolean active) {
        LabelMaster l =
                LabelMaster.propose(
                        new LabelMaster.Particulars(
                                "DESIG.SARDAR",
                                LabelMaster.Type.MASTER_VALUE,
                                "ORG_DESIGNATION",
                                "SARDAR",
                                language,
                                scope,
                                location,
                                text,
                                true,
                                from),
                        1,
                        "it",
                        "r",
                        Instant.EPOCH);
        if (active) {
            l.decided(true, "checker", Instant.EPOCH);
        }
        return l;
    }

    @Test
    void SRS_G_39_aLabelIsResolvedOfficeThenRegionThenGlobalThenEnglishGlobal() {
        LocalDate day = LocalDate.of(2026, 10, 22);
        LocalDate start = LocalDate.of(2026, 4, 1);
        LabelMaster english = version("EN", LabelMaster.Scope.GLOBAL, null, "Sardar", start, true);
        LabelMaster hindi = version("HI", LabelMaster.Scope.GLOBAL, null, "सरदार", start, true);
        LabelMaster region = version("HI", LabelMaster.Scope.REGION, 20L, "मुखिया", start, true);
        LabelMaster office = version("HI", LabelMaster.Scope.OFFICE, 30L, "मुकादम", start, true);
        LabelMaster pending =
                version("HI", LabelMaster.Scope.OFFICE, 31L, "प्रस्तावित", start, false);
        LabelMaster later =
                version("HI", LabelMaster.Scope.OFFICE, 32L, "बाद में", day.plusDays(1), true);
        List<LabelMaster> all = List.of(english, hindi, region, office, pending, later);

        assertThat(LabelService.resolve(all, "HI", 30L, 20L, day)).contains(office);
        assertThat(LabelService.resolve(all, "HI", 99L, 20L, day)).contains(region);
        assertThat(LabelService.resolve(all, "HI", 99L, null, day)).contains(hindi);
        // A pending local name and one not yet in force are never shown.
        assertThat(LabelService.resolve(all, "HI", 31L, null, day)).contains(hindi);
        assertThat(LabelService.resolve(all, "HI", 32L, null, day)).contains(hindi);
        assertThat(LabelService.resolve(all, "TA", 30L, 20L, day)).contains(english);
        assertThat(LabelService.resolve(List.of(hindi), "TA", null, null, day)).isEmpty();
        assertThat(LabelService.pendingKey("K", "HI", "OFFICE", 30L)).isEqualTo("K|HI|OFFICE|30");
        assertThat(LabelService.pendingKey("K", "HI", "GLOBAL", null)).isEqualTo("K|HI|GLOBAL");
    }

    @Test
    void SRS_G_39_aWithdrawnOrSupersededLabelIsEndDatedNeverDeleted() {
        LabelMaster l = version("HI", LabelMaster.Scope.OFFICE, 30L, "मुकादम", FROM, true);
        l.supersede(FROM.plusDays(10), "checker", Instant.EPOCH);
        assertThat(l.status()).isEqualTo(LabelMaster.Status.SUPERSEDED);
        assertThat(l.getEffectiveTo()).isEqualTo(FROM.plusDays(9));
        LabelMaster w = version("HI", LabelMaster.Scope.OFFICE, 30L, "मुकादम", FROM, true);
        w.withdraw(FROM.plusDays(3), "checker", "gone", Instant.EPOCH);
        assertThat(w.status()).isEqualTo(LabelMaster.Status.WITHDRAWN);
        assertThat(w.coversDay(FROM.plusDays(4))).isFalse();
        LabelMaster r = version("HI", LabelMaster.Scope.OFFICE, 30L, "x", FROM, false);
        r.decided(false, "checker", Instant.EPOCH);
        assertThat(r.status()).isEqualTo(LabelMaster.Status.REJECTED);
    }
}
