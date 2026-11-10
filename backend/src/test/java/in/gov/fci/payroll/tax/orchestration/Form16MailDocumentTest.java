package in.gov.fci.payroll.tax.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.notification.MemberDocuments;
import in.gov.fci.payroll.platform.notification.MemberDocumentsReleased;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import in.gov.fci.payroll.tax.business.model.Form16Certificate;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Form 16 by e-mail on its release (OD-148, PENDING F16-5; SRS-7-9, SRS-17-12): the approved C-10
 * release announces its merged (or just released) certificates, keyed by their Part B generation so
 * a reissue is e-mailed again; any other decision announces nothing; the PDF is the member's own
 * certificate, nothing when it can no longer be read as released.
 */
class Form16MailDocumentTest {

    private static final Instant AT = Instant.parse("2026-06-15T05:00:00Z");

    private final Form16Service form16 = mock(Form16Service.class);
    private final TaxRepositories.Form16s certificates = mock(TaxRepositories.Form16s.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ObjectMapper json = JsonMapper.builder().build();
    private final Form16MailDocument mail =
            new Form16MailDocument(form16, certificates, events, json);

    private static Form16Certificate certificate(long id, long employee, String state) {
        Form16Certificate f =
                Form16Certificate.generated(employee, "2025-26", null, null, null, 1L, AT);
        ReflectionTestUtils.setField(f, "form16Id", id);
        if (!"PENDING_PART_A".equals(state)) {
            f.partAMerged(4L, AT);
        }
        if ("RELEASED".equals(state)) {
            f.released(AT);
        }
        return f;
    }

    private ApprovalDecided decision(String module, String entity, ApprovalStatus outcome) {
        String payload =
                json.writeValueAsString(
                        new Form16Service.ReleaseProposal(
                                40L, "2025-26", List.of(1L, 2L, 3L), "m"));
        return new ApprovalDecided(
                9L, module, entity, "40|2025-26", outcome, null, payload, "checker", AT);
    }

    @Test
    void SRS_7_9_theApprovedReleaseAnnouncesItsMergedOrReleasedCertificates() {
        assertThat(mail.documentType()).isEqualTo("FORM16");
        when(certificates.findById(1L)).thenReturn(Optional.of(certificate(1L, 7L, "MERGED")));
        when(certificates.findById(2L)).thenReturn(Optional.of(certificate(2L, 8L, "RELEASED")));
        when(certificates.findById(3L))
                .thenReturn(Optional.of(certificate(3L, 9L, "PENDING_PART_A")));
        mail.on(decision("C-10", "form16", ApprovalStatus.APPROVED));
        ArgumentCaptor<MemberDocumentsReleased> event =
                ArgumentCaptor.forClass(MemberDocumentsReleased.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().documentType()).isEqualTo("FORM16");
        assertThat(event.getValue().orgUnitId()).isEqualTo(40L);
        assertThat(event.getValue().actor()).isEqualTo("checker");
        assertThat(event.getValue().items())
                .containsExactly(
                        new MemberDocumentsReleased.Item(7L, "2025-26", "1@" + AT.toEpochMilli()),
                        new MemberDocumentsReleased.Item(8L, "2025-26", "2@" + AT.toEpochMilli()));
    }

    @Test
    void SRS_17_12_anyOtherDecisionOrNothingReleasedAnnouncesNothing() {
        mail.on(decision("C-02", "form16", ApprovalStatus.APPROVED));
        mail.on(decision("C-10", "statutory_return", ApprovalStatus.APPROVED));
        mail.on(decision("C-10", "form16", ApprovalStatus.REJECTED));
        when(certificates.findById(any())).thenReturn(Optional.empty());
        mail.on(decision("C-10", "form16", ApprovalStatus.APPROVED));
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void SRS_7_9_aReissueIsANewSourceKeyAndTheKeyNamesTheCertificate() {
        Form16Certificate f = certificate(5L, 7L, "RELEASED");
        String first = Form16MailDocument.sourceKey(f);
        f.regenerate(null, null, null, 2L, AT.plusSeconds(60));
        assertThat(Form16MailDocument.sourceKey(f)).isNotEqualTo(first);
        assertThat(Form16MailDocument.certificateOf(first)).isEqualTo(5L);
        assertThat(Form16MailDocument.certificateOf("12")).isEqualTo(12L);
        ReflectionTestUtils.setField(f, "partBGeneratedAt", null);
        assertThat(Form16MailDocument.sourceKey(f)).isEqualTo("5@0");
    }

    @Test
    void SRS_17_1_thePdfIsTheMembersOwnReleasedCertificate() {
        when(form16.certificatePdf(any(AccessContext.class), isNull(), eq(5L)))
                .thenReturn(
                        new Form16Service.CertificatePdf("form16-2025-26", new byte[] {1}, 2, true))
                .thenReturn(new Form16Service.CertificatePdf("f.pdf", new byte[] {1}, 2, true));
        MemberDocuments.Rendered r = mail.render(7L, "2025-26", "5@1").orElseThrow();
        assertThat(r.attachment().fileName()).isEqualTo("form16-2025-26.pdf");
        assertThat(r.values()).containsEntry("year", "2025-26");
        assertThat(mail.render(7L, "2025-26", "5").orElseThrow().attachment().fileName())
                .isEqualTo("f.pdf");
        ArgumentCaptor<AccessContext> context = ArgumentCaptor.forClass(AccessContext.class);
        verify(form16, org.mockito.Mockito.times(2))
                .certificatePdf(context.capture(), isNull(), eq(5L));
        assertThat(context.getValue().employeeId()).isEqualTo(7L);
        assertThat(context.getValue().permissionKeys()).containsExactly("E-01:SELF");
        assertThat(context.getValue().loginId()).isEqualTo("system.document-mail");
    }

    @Test
    void SRS_17_12_aCertificateNoLongerReleasedIsNothingAndOtherRefusalsAreFailures() {
        when(form16.certificatePdf(any(AccessContext.class), isNull(), eq(6L)))
                .thenThrow(new FciException(CommonErrorCode.NOT_FOUND));
        assertThat(mail.render(7L, "2025-26", "6@1")).isEmpty();
        when(form16.certificatePdf(any(AccessContext.class), isNull(), eq(8L)))
                .thenThrow(new FciException(TaxErrorCode.EARNINGS_NOT_AVAILABLE));
        assertThatThrownBy(() -> mail.render(7L, "2025-26", "8@1"))
                .isInstanceOf(FciException.class);
    }
}
