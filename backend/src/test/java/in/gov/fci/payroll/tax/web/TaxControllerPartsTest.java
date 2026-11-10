package in.gov.fci.payroll.tax.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.tax.business.model.ExternalIncomeDeclaration.Type;
import in.gov.fci.payroll.tax.business.model.ItDeclaration.Kind;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime;
import in.gov.fci.payroll.tax.orchestration.DeclarationService;
import in.gov.fci.payroll.tax.orchestration.Form16Service;
import in.gov.fci.payroll.tax.orchestration.ReturnService;
import in.gov.fci.payroll.tax.orchestration.SavingsService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * What the Module 08 controllers hand to their services from the optional parts of a request
 * (SRS-7-9, SRS-7-31, SRS-7-14): an absent member list is the whole unit, an absent filing evidence
 * sends no file, a blank reference is none, and every proof file belongs to a line naming one.
 */
class TaxControllerPartsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 4, 10);

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final Paging paging = mock(Paging.class);
    private final MockMultipartFile pdf =
            new MockMultipartFile("file", "proof.pdf", "application/pdf", new byte[] {1, 2});

    @Test
    void SRS_7_9_noMemberListIsTheWholeUnitAndAListIsSentAsASet() {
        Form16Service form16 = mock(Form16Service.class);
        StatutoryOutputController c =
                new StatutoryOutputController(
                        contexts,
                        form16,
                        mock(ReturnService.class),
                        paging,
                        mock(in.gov.fci.payroll.platform.web.ExportStreaming.class));

        c.generate(new StatutoryOutputController.GenerateCertificatesRequest("2025-26", null));
        c.generate(
                new StatutoryOutputController.GenerateCertificatesRequest(
                        "2025-26", List.of("870001", "870001")));

        verify(form16).generate(any(), eq("2025-26"), isNull());
        verify(form16).generate(any(), eq("2025-26"), eq(Set.of("870001")));
    }

    @Test
    void SRS_7_31_aFilingWithoutEvidenceSendsNoFileAndWithItSendsBoth() {
        ReturnService returns = mock(ReturnService.class);
        StatutoryOutputController c =
                new StatutoryOutputController(
                        contexts,
                        mock(Form16Service.class),
                        returns,
                        paging,
                        mock(in.gov.fci.payroll.platform.web.ExportStreaming.class));
        ArgumentCaptor<ReturnService.Filing> sent =
                ArgumentCaptor.forClass(ReturnService.Filing.class);
        StatutoryOutputController.DocumentParticulars evidence =
                new StatutoryOutputController.DocumentParticulars("ACK", null, DAY, "TIN-FC");

        c.filing(1L, new StatutoryOutputController.FilingRequest(DAY, "A", "B", null, null), null);
        c.filing(1L, new StatutoryOutputController.FilingRequest(DAY, "A", "B", "T", null), pdf);
        c.filing(
                1L, new StatutoryOutputController.FilingRequest(DAY, "A", "B", "T", evidence), pdf);
        c.filing(
                1L,
                new StatutoryOutputController.FilingRequest(DAY, "A", "B", "T", evidence),
                null);

        verify(returns, org.mockito.Mockito.times(4)).recordFiling(any(), eq(1L), sent.capture());
        List<ReturnService.Filing> all = sent.getAllValues();
        assertThat(all.get(0).evidenceType()).isNull();
        assertThat(all.get(0).fileName()).isNull();
        assertThat(all.get(1).fileName()).isEqualTo("proof.pdf");
        assertThat(all.get(1).content()).as("no particulars: the file is not sent").isNull();
        assertThat(all.get(2).evidenceType()).isEqualTo("ACK");
        assertThat(all.get(2).content()).containsExactly(1, 2);
        assertThat(all.get(3).evidenceDate()).isEqualTo(DAY);
        assertThat(all.get(3).content()).isNull();
    }

    @Test
    void SRS_7_14_aBlankReferenceIsNoneAndAReferenceIsTrimmed() {
        SavingsService savings = mock(SavingsService.class);
        SavingsController c = new SavingsController(contexts, savings, paging);
        ArgumentCaptor<SavingsService.Document> sent =
                ArgumentCaptor.forClass(SavingsService.Document.class);

        for (String reference : new String[] {null, "  ", " R-1 "}) {
            c.save(
                    "870001",
                    new SavingsController.EntryRequest(
                            Type.OTHER_INCOME,
                            null,
                            "Honorarium",
                            null,
                            BigDecimal.TEN,
                            null,
                            new SavingsController.DocumentParticulars(
                                    "ORDER", reference, DAY, "FCI")),
                    pdf);
        }

        verify(savings, org.mockito.Mockito.times(3))
                .save(any(), eq("870001"), any(), sent.capture());
        assertThat(sent.getAllValues())
                .extracting(SavingsService.Document::referenceNumber)
                .containsExactly(null, null, "R-1");
    }

    /** SRS-7-9: the Part A reference is optional; a blank one is none, a given one trimmed. */
    @Test
    void SRS_7_9_aPartAReferenceBlankIsNoneAndAGivenOneIsTrimmed() {
        Form16Service form16 = mock(Form16Service.class);
        StatutoryOutputController c =
                new StatutoryOutputController(
                        contexts,
                        form16,
                        mock(ReturnService.class),
                        paging,
                        mock(in.gov.fci.payroll.platform.web.ExportStreaming.class));
        ArgumentCaptor<Form16Service.PartA> sent =
                ArgumentCaptor.forClass(Form16Service.PartA.class);

        for (String reference : new String[] {null, "   ", " ITD/16A/1 "}) {
            c.partA(
                    3L,
                    new StatutoryOutputController.DocumentParticulars(
                            "CERTIFICATE", reference, DAY, "ITD TRACES"),
                    pdf);
        }

        verify(form16, org.mockito.Mockito.times(3)).uploadPartA(any(), eq(3L), sent.capture());
        assertThat(sent.getAllValues())
                .extracting(Form16Service.PartA::reference)
                .containsExactly(null, null, "ITD/16A/1");
        assertThat(sent.getValue().fileName()).isEqualTo("proof.pdf");
        assertThat(sent.getValue().content()).containsExactly(1, 2);
    }

    @Test
    void SRS_7_14_aFileThatCannotBeReadIsAnIoFailure() throws IOException {
        MultipartFile broken = mock(MultipartFile.class);
        when(broken.getBytes()).thenThrow(new IOException("gone"));
        SavingsController c = new SavingsController(contexts, mock(SavingsService.class), paging);

        assertThatThrownBy(
                        () ->
                                c.save(
                                        "870001",
                                        new SavingsController.EntryRequest(
                                                Type.OTHER_INCOME,
                                                null,
                                                null,
                                                null,
                                                BigDecimal.TEN,
                                                null,
                                                new SavingsController.DocumentParticulars(
                                                        "ORDER", null, DAY, "FCI")),
                                        broken))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void SRS_7_16_eachProofFileBelongsToTheLineNamingOne() {
        DeclarationService service = mock(DeclarationService.class);
        DeclarationController c = new DeclarationController(contexts, service, paging);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeclarationService.NewLine>> sent = ArgumentCaptor.forClass(List.class);
        DeclarationController.ProofParticulars proof =
                new DeclarationController.ProofParticulars("RECEIPT", " RC-9 ", DAY, "LIC");
        DeclarationController.ProofParticulars blank =
                new DeclarationController.ProofParticulars("RECEIPT", " ", DAY, "LIC");
        DeclarationController.ProofParticulars none =
                new DeclarationController.ProofParticulars("RECEIPT", null, DAY, "LIC");

        c.submit(new DeclarationController.DeclarationRequest(Regime.OLD, null), null);
        c.submit(
                new DeclarationController.DeclarationRequest(
                        Regime.OLD,
                        List.of(
                                new DeclarationController.LineRequest(
                                        Kind.INVESTMENT, "80C", BigDecimal.TEN, null),
                                new DeclarationController.LineRequest(
                                        Kind.INVESTMENT, "80D", BigDecimal.ONE, proof),
                                new DeclarationController.LineRequest(
                                        Kind.INVESTMENT, "80E", BigDecimal.ONE, blank),
                                new DeclarationController.LineRequest(
                                        Kind.INVESTMENT, "80G", BigDecimal.ONE, none))),
                List.of(pdf, pdf, pdf));

        verify(service, org.mockito.Mockito.times(2)).submit(any(), eq(Regime.OLD), sent.capture());
        assertThat(sent.getAllValues().get(0)).isEmpty();
        List<DeclarationService.NewLine> lines = sent.getAllValues().get(1);
        assertThat(lines.get(0).proof()).isNull();
        assertThat(lines.get(1).proof().referenceNumber()).isEqualTo("RC-9");
        assertThat(lines.get(2).proof().referenceNumber()).isNull();
        assertThat(lines.get(3).proof().referenceNumber()).isNull();
        assertThat(lines.get(3).proof().fileName()).isEqualTo("proof.pdf");

        // A file without a line naming a proof (or a proof without its file) is refused.
        assertThatThrownBy(
                        () ->
                                c.submit(
                                        new DeclarationController.DeclarationRequest(
                                                Regime.OLD, List.of()),
                                        List.of(pdf)))
                .isInstanceOf(FciException.class);
    }
}
