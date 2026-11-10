package in.gov.fci.payroll.cpf.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.cpf.business.PurposeRules.Category;
import in.gov.fci.payroll.cpf.business.PurposeRules.Document;
import in.gov.fci.payroll.cpf.business.PurposeRules.Tier;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** SRS-6-5, SRS-6-14, SRS-6-39, BR-CPF-028: the rules of a withdrawal purpose and its checklist. */
class PurposeRulesTest {

    private static final List<String> TYPES = List.of("CERTIFICATE", "OTHER");

    private static String code(Runnable call) {
        try {
            call.run();
        } catch (FciException e) {
            return e.getErrorCode().code();
        }
        return null;
    }

    @Test
    void SRS_6_5_aPurposeCodeIsUpperCaseAndCovidIsNotOffered() {
        assertThat(code(() -> PurposeRules.requireCode("MARRIAGE"))).isNull();
        assertThat(code(() -> PurposeRules.requireCode("BEFORE_RETIREMENT_90"))).isNull();
        for (String bad :
                new String[] {"marriage", "9LIVES", "M", "A".repeat(31), "WITH SPACE", "", null}) {
            assertThat(code(() -> PurposeRules.requireCode(bad)))
                    .as(String.valueOf(bad))
                    .isEqualTo("CPF-422-PURPOSE-CODE");
        }
        assertThat(code(() -> PurposeRules.requireCode("COVID_19")))
                .isEqualTo("CPF-422-PURPOSE-WITHDRAWN");
        assertThat(code(() -> PurposeRules.requireCode("ADVANCE_COVID")))
                .isEqualTo("CPF-422-PURPOSE-WITHDRAWN");
    }

    @Test
    void SRS_6_2_aLimitKeyIsOneWhoseBasisTheSrsStatesAndAFinalPaymentHasNone() {
        assertThat(code(() -> PurposeRules.requireLimitKey(null, Category.ADVANCE))).isNull();
        assertThat(code(() -> PurposeRules.requireLimitKey(null, Category.FINAL))).isNull();
        assertThat(
                        code(
                                () ->
                                        PurposeRules.requireLimitKey(
                                                "CPF.LIMIT.MGW.MARRIAGE", Category.ADVANCE)))
                .isNull();
        assertThat(
                        code(
                                () ->
                                        PurposeRules.requireLimitKey(
                                                "CPF.LIMIT.MGW.MARRIAGE", Category.FINAL)))
                .isEqualTo("CPF-422-PURPOSE-LIMIT");
        assertThat(code(() -> PurposeRules.requireLimitKey("CPF.VPF.MAX", Category.ADVANCE)))
                .isEqualTo("CPF-422-PURPOSE-LIMIT");
        assertThat(WithdrawalLimit.ofRuleKey("CPF.LIMIT.PAY.RENOVATION"))
                .contains(WithdrawalLimit.RENOVATION);
        assertThat(WithdrawalLimit.RENOVATION.basis())
                .isEqualTo(WithdrawalLimit.Basis.PAY_MULTIPLE_OR_OWN_SHARE);
        assertThat(WithdrawalLimit.BEFORE_RETIREMENT.basis())
                .isEqualTo(WithdrawalLimit.Basis.BALANCE_FRACTION);
        assertThat(WithdrawalLimit.HOUSE.basis()).isEqualTo(WithdrawalLimit.Basis.MGW_MULTIPLE);
        assertThat(WithdrawalLimit.ofRuleKey("CPF.LIMIT.BALANCE.ADVANCE_CAP")).isEmpty();
    }

    @Test
    void SRS_6_5_aQuotaIsFrom1To999OrNone() {
        assertThat(code(() -> PurposeRules.requireQuota(null))).isNull();
        assertThat(code(() -> PurposeRules.requireQuota(1))).isNull();
        assertThat(code(() -> PurposeRules.requireQuota(999))).isNull();
        assertThat(code(() -> PurposeRules.requireQuota(0))).isEqualTo("CPF-422-PURPOSE-QUOTA");
        assertThat(code(() -> PurposeRules.requireQuota(1000))).isEqualTo("CPF-422-PURPOSE-QUOTA");
    }

    @Test
    void SRS_6_14_theChecklistNamesEachKnownDocumentTypeOnce() {
        Document certificate = new Document("CERTIFICATE", true, Tier.UNIT_CPF_ADMIN);
        assertThat(code(() -> PurposeRules.requireDocuments(null, TYPES))).isNull();
        assertThat(code(() -> PurposeRules.requireDocuments(List.of(), TYPES))).isNull();
        assertThat(code(() -> PurposeRules.requireDocuments(List.of(certificate), TYPES))).isNull();
        assertThat(
                        code(
                                () ->
                                        PurposeRules.requireDocuments(
                                                List.of(certificate, certificate), TYPES)))
                .isEqualTo("CPF-422-PURPOSE-DOCUMENT");
        assertThat(
                        code(
                                () ->
                                        PurposeRules.requireDocuments(
                                                List.of(
                                                        new Document(
                                                                "PASSPORT",
                                                                true,
                                                                Tier.UNIT_CPF_ADMIN)),
                                                TYPES)))
                .isEqualTo("CPF-422-PURPOSE-DOCUMENT");
        assertThat(
                        code(
                                () ->
                                        PurposeRules.requireDocuments(
                                                List.of(
                                                        new Document(
                                                                null, true, Tier.UNIT_CPF_ADMIN)),
                                                TYPES)))
                .isEqualTo("CPF-422-PURPOSE-DOCUMENT");
        java.util.List<Document> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        assertThat(code(() -> PurposeRules.requireDocuments(withNull, TYPES)))
                .isEqualTo("CPF-422-PURPOSE-DOCUMENT");
    }

    @Test
    void SRS_6_23_aNewVersionStartsAfterTheVersionInForce() {
        LocalDate current = LocalDate.of(2026, 4, 1);
        assertThat(code(() -> PurposeRules.requireAfter(current, Optional.empty()))).isNull();
        assertThat(code(() -> PurposeRules.requireAfter(current.plusDays(1), Optional.of(current))))
                .isNull();
        assertThat(code(() -> PurposeRules.requireAfter(current, Optional.of(current))))
                .isEqualTo("CPF-422-PURPOSE-DATE");
        assertThat(
                        code(
                                () ->
                                        PurposeRules.requireAfter(
                                                current.minusDays(1), Optional.of(current))))
                .isEqualTo("CPF-422-PURPOSE-DATE");
        assertThat(code(() -> PurposeRules.requireAfter(null, Optional.empty())))
                .isEqualTo("CPF-422-PURPOSE-DATE");
    }
}
