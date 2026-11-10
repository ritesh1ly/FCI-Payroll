package in.gov.fci.payroll.cpf.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.cpf.orchestration.CpfSanctionService;
import in.gov.fci.payroll.cpf.orchestration.WithdrawalPurposeService;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * The CPF controllers' mapping of a request body (SRS-6-5, SRS-6-14, SRS-6-39): an M-19 limit rule
 * key left blank is none and a checklist not sent is empty; an E-01 / I-04 file needs its
 * particulars (a count that differs is malformed) and a blank document reference is none.
 */
class CpfControllersBranchGapsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final AccessContext user =
            new AccessContext(
                    1L,
                    "rule.maker@x",
                    "Maker",
                    "s",
                    "P",
                    1L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(1L));

    CpfControllersBranchGapsTest() {
        when(contexts.current()).thenReturn(user);
    }

    // ---------------------------------------------------------------- M-19

    private static WithdrawalPurposeController.PurposeRequest purpose(
            String limitRuleKey, List<WithdrawalPurposeController.DocumentRequest> documents) {
        return new WithdrawalPurposeController.PurposeRequest(
                "MARRIAGE",
                "Marriage",
                "ADVANCE",
                limitRuleKey,
                3,
                false,
                false,
                DAY,
                true,
                documents,
                "New version",
                null);
    }

    @Test
    void SRS_6_39_aBlankLimitRuleKeyIsNoneAndAChecklistNotSentIsEmpty() {
        WithdrawalPurposeService service = mock(WithdrawalPurposeService.class);
        WithdrawalPurposeController controller = new WithdrawalPurposeController(contexts, service);
        controller.propose(purpose(null, null));
        controller.propose(purpose("   ", List.of()));
        controller.propose(
                purpose(
                        "CPF.LIMIT.MGW.MARRIAGE",
                        List.of(
                                new WithdrawalPurposeController.DocumentRequest(
                                        "CERTIFICATE", true, "UNIT_CPF_ADMIN"))));
        ArgumentCaptor<WithdrawalPurposeService.Proposal> sent = ArgumentCaptor.captor();
        verify(service, org.mockito.Mockito.times(3))
                .propose(eq(user), sent.capture(), eq("New version"), isNull());
        List<WithdrawalPurposeService.Proposal> all = sent.getAllValues();
        assertThat(all.get(0).limitRuleKey()).isNull();
        assertThat(all.get(0).documents()).isEmpty();
        assertThat(all.get(1).limitRuleKey()).isNull();
        assertThat(all.get(2).limitRuleKey()).isEqualTo("CPF.LIMIT.MGW.MARRIAGE");
        assertThat(all.get(2).documents())
                .containsExactly(
                        new WithdrawalPurposeService.DocumentView(
                                "CERTIFICATE", true, "UNIT_CPF_ADMIN"));
    }

    // ---------------------------------------------------------------- E-01 / I-04

    private static CpfSanctionController.DocumentParticulars particulars(String reference) {
        return new CpfSanctionController.DocumentParticulars(
                "CERTIFICATE", reference, DAY, "Registrar");
    }

    private static MultipartFile file(String name) {
        return new MockMultipartFile("file", name, "application/pdf", new byte[] {1, 2});
    }

    private static CpfSanctionController.SanctionRequest request(
            List<CpfSanctionController.DocumentParticulars> documents) {
        return new CpfSanctionController.SanctionRequest(
                "MARRIAGE", new BigDecimal("50000"), null, null, documents);
    }

    @Test
    void SRS_6_14_aFileWithoutItsParticularsIsMalformed() {
        CpfSanctionService service = mock(CpfSanctionService.class);
        CpfSanctionController controller = new CpfSanctionController(contexts, service, null);
        assertThatThrownBy(() -> controller.request(request(null), List.of(file("a.pdf"))))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
        verify(service, never()).request(any(), any(), anyList());
    }

    @Test
    void SRS_6_14_aBlankDocumentReferenceIsNoneAndAGivenOneIsStripped() {
        CpfSanctionService service = mock(CpfSanctionService.class);
        CpfSanctionController controller = new CpfSanctionController(contexts, service, null);
        controller.request(
                request(List.of(particulars(null), particulars("  "), particulars(" C/1 "))),
                List.of(file("a.pdf"), file("b.pdf"), file("c.pdf")));
        ArgumentCaptor<List<CpfSanctionService.Document>> sent = ArgumentCaptor.captor();
        verify(service).request(eq(user), any(), sent.capture());
        assertThat(sent.getValue())
                .extracting(CpfSanctionService.Document::referenceNumber)
                .containsExactly(null, null, "C/1");
        assertThat(sent.getValue())
                .extracting(CpfSanctionService.Document::fileName)
                .containsExactly("a.pdf", "b.pdf", "c.pdf");
        // No file and no particulars: a request without attachments.
        controller.request(request(null), null);
        verify(service).request(eq(user), any(), eq(List.of()));
    }
}
