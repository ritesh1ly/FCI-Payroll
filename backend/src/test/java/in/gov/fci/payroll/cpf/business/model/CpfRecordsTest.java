package in.gov.fci.payroll.cpf.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.cpf.business.SanctionRules.Stage;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * What the CPF rows record when they are created or corrected: the borrowing organisation of a
 * deputation (SRS-6-27), a purpose's document checklist (SRS-6-14), a monthly ledger row
 * (BR-CPF-017), an opening-balance correction part (SRS-6-43) and the tier 3 sanction (SRS-6-4).
 */
class CpfRecordsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final Instant AT = Instant.parse("2026-10-23T05:00:00Z");

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    @Test
    void SRS_6_27_aNewBorrowingOrganisationIsActive() {
        BorrowingOrganisation o = BorrowingOrganisation.of("CWC", "Central Warehousing", "Delhi");
        assertThat(o.getOrgCode()).isEqualTo("CWC");
        assertThat(o.getOrgName()).isEqualTo("Central Warehousing");
        assertThat(o.getContactDetail()).isEqualTo("Delhi");
        assertThat(o.isActive()).isTrue();
        assertThat(BorrowingOrganisation.of("CWC", "Central Warehousing", null).getContactDetail())
                .isNull();
    }

    @Test
    void SRS_6_14_aChecklistDocumentKeepsItsTypeWhetherMandatoryAndItsVerifyingTier() {
        CpfPurposeDocument mandatory = CpfPurposeDocument.of(5L, "SALE_DEED", true, "UNIT");
        assertThat(mandatory.getPurposeId()).isEqualTo(5L);
        assertThat(mandatory.getDocumentTypeCode()).isEqualTo("SALE_DEED");
        assertThat(mandatory.isMandatory()).isTrue();
        assertThat(mandatory.getVerificationTier()).isEqualTo("UNIT");
        assertThat(CpfPurposeDocument.of(5L, "PHOTO", false, null).isMandatory()).isFalse();
    }

    @Test
    void BR_CPF_017_aPostedContributionIsOneMonthlyLedgerRowOfANonZeroSalaryMember() {
        EmployeePfDetail d =
                EmployeePfDetail.of(
                        3L,
                        7L,
                        "202610",
                        EmployeePfDetail.PaymentType.ARREAR,
                        n("15000"),
                        n("1800"),
                        n("550"),
                        n("200"),
                        n("1250"),
                        n("0"),
                        n("0.40"),
                        11L,
                        DAY);
        assertThat(d.getPfHeaderId()).isEqualTo(3L);
        assertThat(d.getEmployeeId()).isEqualTo(7L);
        assertThat(d.getPayrollYymm()).isEqualTo("202610");
        assertThat(d.getPaymentType()).isEqualTo("ARREAR");
        assertThat(d.getEpfWageBase()).isEqualByComparingTo("15000");
        assertThat(d.getEmployeeContribution()).isEqualByComparingTo("1800");
        assertThat(d.getEmployerContribution()).isEqualByComparingTo("550");
        assertThat(d.getVpfContribution()).isEqualByComparingTo("200");
        assertThat(d.getEpsContribution()).isEqualByComparingTo("1250");
        assertThat(d.getPohwContribution()).isEqualByComparingTo("0");
        assertThat(d.getRoundingAdjustment()).isEqualByComparingTo("0.40");
        assertThat(d.getZoneId()).isEqualTo(11L);
        assertThat(d.isIsZeroSalaryMember()).isFalse();
        assertThat(d.getPeriodDate()).isEqualTo(DAY);
        // Interest is credited by the year-end run, never by the posting.
        assertThat(d.getInterestAmount()).isNull();
    }

    @Test
    void SRS_6_43_aCorrectionPartKeepsTheOldAmountBesideTheNewOne() {
        OpeningBalanceCorrection c =
                OpeningBalanceCorrection.raise(
                        "OBC-1",
                        7L,
                        "2026-27",
                        DAY,
                        "EMPLOYEE",
                        n("1000"),
                        n("1500"),
                        "DATA_ENTRY",
                        "Opening balance keyed short",
                        DAY.plusDays(5),
                        44L,
                        OpeningBalanceCorrection.Status.PENDING_AGM_CPF,
                        null,
                        "mgrcpf",
                        AT);
        assertThat(c.getCorrectionRef()).isEqualTo("OBC-1");
        assertThat(c.getEmployeeId()).isEqualTo(7L);
        assertThat(c.getBalanceDomain()).isEqualTo("CPF");
        assertThat(c.getFinYear()).isEqualTo("2026-27");
        assertThat(c.getAsAtDate()).isEqualTo(DAY);
        assertThat(c.getBalanceComponent()).isEqualTo("EMPLOYEE");
        assertThat(c.getOldAmount()).isEqualByComparingTo("1000");
        assertThat(c.getNewAmount()).isEqualByComparingTo("1500");
        assertThat(c.getReasonCode()).isEqualTo("DATA_ENTRY");
        assertThat(c.getReason()).isEqualTo("Opening balance keyed short");
        assertThat(c.getInterestEffectiveDate()).isEqualTo(DAY.plusDays(5));
        assertThat(c.getEvidenceAttachmentId()).isEqualTo(44L);
        assertThat(c.getCorrectionStatus()).isEqualTo("PENDING_AGM_CPF");
        assertThat(c.getStatusReason()).isNull();
        assertThat(c.getRaisedBy()).isEqualTo("mgrcpf");
        assertThat(c.getRaisedAt()).isEqualTo(AT);
        // No tier has decided it yet.
        assertThat(c.getCheckerUser()).isNull();
        assertThat(c.getHqApproverUser()).isNull();
    }

    @Test
    void SRS_6_27_aReturnedEntryCorrectedByTheMakerCarriesTheNewAmountsAndNoBalanceYet() {
        CpfDeputationRecovery r =
                CpfDeputationRecovery.keyed(
                        7L,
                        2L,
                        CpfDeputationRecovery.EntryType.RECOVERY_INSTALMENT,
                        4L,
                        DAY,
                        DAY.plusYears(1),
                        "202610",
                        n("0"),
                        n("1800"),
                        n("1800"),
                        n("0"));
        r.submitted(90L);
        r.approved(n("5000"), n("5000"));
        r.returned();
        r.corrected(n("100"), n("1900"), n("1850"), n("50"));
        assertThat(r.getInstalmentRemitted()).isEqualByComparingTo("100");
        assertThat(r.getContributionEmployee()).isEqualByComparingTo("1900");
        assertThat(r.getContributionEmployer()).isEqualByComparingTo("1850");
        assertThat(r.getContributionVpf()).isEqualByComparingTo("50");
        // The balance falls only on the Checker's approval, never on the Maker's save.
        assertThat(r.getAdvanceOutstanding()).isNull();
        assertThat(r.getBalanceAfter()).isNull();
        assertThat(r.status()).isEqualTo(CpfDeputationRecovery.Status.MAKER_SAVED);
    }

    @Test
    void SRS_6_4_tier3SanctionsWithItsNumberOrBlocksThePaymentTheBalanceCannotCarry() {
        PfSanction paid = PfSanction.requested(7L, "ADVANCE", 5L, n("50000"), 11L);
        paid.sanctioned("NZ/2026/0001", DAY, 300L, false);
        assertThat(paid.stage()).isEqualTo(Stage.SANCTIONED);
        assertThat(paid.getSystemSanctionNumber()).isEqualTo("NZ/2026/0001");
        assertThat(paid.getSanctionDate()).isEqualTo(DAY);
        PfSanction blocked = PfSanction.requested(7L, "ADVANCE", 5L, n("50000"), 11L);
        blocked.sanctioned("NZ/2026/0002", DAY, 300L, true);
        assertThat(blocked.stage()).isEqualTo(Stage.PAYMENT_BLOCKED);
    }
}
