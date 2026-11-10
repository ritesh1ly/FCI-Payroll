package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.orchestration.CircularService.NewCircular;
import in.gov.fci.payroll.configuration.orchestration.CircularService.OrderCopy;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * SRS-G-38 (M-25): a circular is recorded only with a number, date, authority and category from the
 * reference lists, at least one known payroll type, its offices (all, or named ones that exist), a
 * consistent effective window and a PDF copy. Every refusal happens before anything is proposed:
 * the repositories and the maker-checker are never touched.
 */
class CircularRecordRulesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj".getBytes(StandardCharsets.US_ASCII);

    private final ConfigurationRepositories.Circulars circulars =
            mock(ConfigurationRepositories.Circulars.class);
    private final ReferenceData reference = mock(ReferenceData.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final MakerChecker approvals = mock(MakerChecker.class);

    private final CircularService service =
            new CircularService(
                    circulars,
                    mock(ConfigurationRepositories.CircularScopes.class),
                    mock(ConfigurationRepositories.CircularLinks.class),
                    mock(ConfigurationRepositories.Operations.class),
                    mock(ConfigurationRepositories.RateSets.class),
                    reference,
                    org,
                    mock(DocumentStore.class),
                    approvals,
                    mock(Authorization.class),
                    mock(SystemParameters.class),
                    mock(AuditService.class),
                    Clock.fixed(Instant.parse("2026-10-22T06:00:00Z"), ZoneOffset.UTC));

    private final AccessContext maker =
            new AccessContext(
                    1L,
                    "maker@x",
                    "Maker",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    {
        when(reference.contains(anyString(), any())).thenReturn(true);
        when(reference.contains(eq(CircularService.AUTHORITY), eq("NOBODY"))).thenReturn(false);
        when(org.location(1L)).thenReturn(Optional.of(mock(OrgLocation.class)));
        when(org.location(9L)).thenReturn(Optional.empty());
    }

    private static NewCircular circular(
            LocalDate date,
            List<String> types,
            boolean all,
            List<Long> offices,
            LocalDate from,
            LocalDate to) {
        return new NewCircular(
                "HQ/2026/1",
                date,
                "HQ_PERSONNEL",
                "Subject",
                "LABOUR_NORM",
                types,
                all,
                offices,
                from,
                to,
                null,
                "r");
    }

    private static NewCircular valid() {
        return circular(DAY, List.of("STAFF"), true, null, DAY, null);
    }

    private static ErrorCode code(Throwable t) {
        return ((FciException) t).getErrorCode();
    }

    private void refused(NewCircular request, OrderCopy copy, ErrorCode expected) {
        assertThatThrownBy(() -> service.record(maker, request, copy, null))
                .as(String.valueOf(request))
                .satisfies(e -> assertThat(code(e)).isEqualTo(expected));
    }

    @Test
    void SRS_G_38_aMissingRequestOrAnUnknownAuthorityIsRefused() {
        refused(null, new OrderCopy("o.pdf", PDF), CommonErrorCode.VALIDATION_FAILED);
        NewCircular unknown =
                new NewCircular(
                        "HQ/2026/2",
                        DAY,
                        "NOBODY",
                        "Subject",
                        "LABOUR_NORM",
                        List.of("STAFF"),
                        true,
                        null,
                        DAY,
                        null,
                        null,
                        "r");
        assertThatThrownBy(() -> service.record(maker, unknown, new OrderCopy("o.pdf", PDF), null))
                .satisfies(
                        e -> {
                            assertThat(code(e)).isEqualTo(ApiErrorCode.MASTER_UNKNOWN_VALUE);
                            assertThat(((FciException) e).getMessageArgs())
                                    .containsExactly(CircularService.AUTHORITY, "NOBODY");
                        });
    }

    @Test
    void SRS_G_38_theDatesTheWindowAndThePayrollTypesMustBeConsistent() {
        OrderCopy copy = new OrderCopy("o.pdf", PDF);
        for (NewCircular bad :
                List.of(
                        circular(null, List.of("STAFF"), true, null, DAY, null),
                        circular(DAY, List.of("STAFF"), true, null, null, null),
                        circular(DAY, List.of("STAFF"), true, null, DAY, DAY.minusDays(1)),
                        circular(DAY, null, true, null, DAY, null),
                        circular(DAY, List.of(), true, null, DAY, null),
                        circular(DAY, List.of("STAFF", "PENSIONER"), true, null, DAY, null))) {
            refused(bad, copy, ApiErrorCode.CIRCULAR_INVALID);
        }
        // The window may close on its first day; a type listed twice counts once.
        NewCircular oneDay = circular(DAY, List.of("STAFF", "STAFF"), true, null, DAY, DAY);
        assertThatThrownBy(() -> service.record(maker, oneDay, new OrderCopy("o.pdf", null), null))
                .satisfies(
                        e ->
                                assertThat(code(e))
                                        .isEqualTo(ApiErrorCode.CIRCULAR_DOCUMENT_REQUIRED));
    }

    @Test
    void SRS_G_38_theOfficesAreAllOrNamedOnesThatExist() {
        OrderCopy copy = new OrderCopy("o.pdf", PDF);
        for (NewCircular bad :
                List.of(
                        circular(DAY, List.of("STAFF"), true, List.of(1L), DAY, null),
                        circular(DAY, List.of("STAFF"), false, null, DAY, null),
                        circular(DAY, List.of("STAFF"), false, List.of(), DAY, null),
                        circular(DAY, List.of("STAFF"), false, Arrays.asList(1L, null), DAY, null),
                        circular(DAY, List.of("STAFF"), false, List.of(1L, 9L), DAY, null))) {
            refused(bad, copy, ApiErrorCode.CIRCULAR_INVALID);
        }
    }

    @Test
    void SRS_G_38_aCopyIsRequiredAndMustBeAPdfWhateverItsName() {
        for (OrderCopy bad :
                Arrays.asList(
                        null,
                        new OrderCopy("o.pdf", null),
                        new OrderCopy("o.pdf", new byte[0]),
                        new OrderCopy("o.pdf", "MZ\u0090".getBytes(StandardCharsets.ISO_8859_1)),
                        new OrderCopy("o.docx", "PK".getBytes(StandardCharsets.US_ASCII)))) {
            refused(valid(), bad, ApiErrorCode.CIRCULAR_DOCUMENT_REQUIRED);
        }
        verifyNoInteractions(approvals);
    }

    @Test
    void SRS_G_38_aNumberAlreadyRecordedIsRefusedWithThatNumber() {
        when(circulars.findByCircularNumber("HQ/2026/1"))
                .thenReturn(
                        Optional.of(
                                mock(
                                        in.gov.fci.payroll.configuration.business.model
                                                .CircularOrder.class)));
        assertThatThrownBy(() -> service.record(maker, valid(), new OrderCopy("o.pdf", PDF), null))
                .satisfies(
                        e -> {
                            assertThat(code(e)).isEqualTo(ApiErrorCode.CIRCULAR_NUMBER_TAKEN);
                            assertThat(((FciException) e).getMessageArgs())
                                    .containsExactly("HQ/2026/1");
                        });
        verifyNoInteractions(approvals);
    }
}
