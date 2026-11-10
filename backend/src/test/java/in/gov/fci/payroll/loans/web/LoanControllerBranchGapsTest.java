package in.gov.fci.payroll.loans.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.loans.orchestration.LoanChangeService;
import in.gov.fci.payroll.loans.orchestration.LoanOrderService;
import in.gov.fci.payroll.loans.orchestration.LoanQueryService;
import in.gov.fci.payroll.loans.orchestration.LoanSanctionService;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * US-GAP-202 / SRS-12-18: a foreclosure whose order file is absent or empty carries no order (the
 * service then blocks it); one with an order file carries it with its particulars.
 */
class LoanControllerBranchGapsTest {

    private static final LocalDate ISSUED = LocalDate.of(2026, 10, 1);

    private final LoanChangeService changes = mock(LoanChangeService.class);
    private final LoanController controller =
            new LoanController(
                    mock(CurrentAccess.class),
                    mock(LoanSanctionService.class),
                    changes,
                    mock(LoanQueryService.class),
                    mock(LoanOrderService.class),
                    mock(Paging.class),
                    mock(ExportStreaming.class));

    private static MockMultipartFile receipt() {
        return new MockMultipartFile("file", "receipt.pdf", "application/pdf", new byte[] {1});
    }

    @Test
    void SRS_12_18_anEmptyOrMissingOrderFileIsNoOrderAndAGivenOneIsKept() {
        MockMultipartFile empty =
                new MockMultipartFile("orderFile", "o.pdf", "application/pdf", new byte[0]);
        controller.foreclose(
                5L,
                "SANCTION_ORDER",
                "O-1",
                ISSUED,
                "AGM",
                empty,
                "RECEIPT",
                "R-1",
                ISSUED,
                "AGM",
                receipt());
        controller.foreclose(
                6L, null, null, null, null, null, "RECEIPT", null, ISSUED, "AGM", receipt());
        verify(changes).foreclose(any(), eq(5L), isNull(), any());
        verify(changes).foreclose(any(), eq(6L), isNull(), any());

        MockMultipartFile order =
                new MockMultipartFile("orderFile", "o.pdf", "application/pdf", new byte[] {2});
        assertThat(
                        controller
                                .foreclose(
                                        7L,
                                        "SANCTION_ORDER",
                                        "O-1",
                                        ISSUED,
                                        "AGM",
                                        order,
                                        "RECEIPT",
                                        "R-1",
                                        ISSUED,
                                        "AGM",
                                        receipt())
                                .getStatusCode()
                                .value())
                .isEqualTo(202);
        verify(changes)
                .foreclose(
                        any(),
                        anyLong(),
                        argThat(
                                d ->
                                        d != null
                                                && "O-1".equals(d.reference())
                                                && "o.pdf".equals(d.fileName())),
                        any());
    }
}
