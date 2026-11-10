package in.gov.fci.payroll.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.ReferenceData;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * M-16 reference lists for the dropdowns: the ETag is the business date and the region version, so
 * a browser holding the current tag gets 304 without a body, and a stale tag gets the lists.
 */
class ReferenceControllerBranchGapsTest {

    @Test
    void referenceListsTheBrowserAlreadyHoldsAreAnswered304WithoutABody() {
        ReferenceData reference = mock(ReferenceData.class);
        LocalDate day = LocalDate.of(2026, 11, 4);
        when(reference.snapshot()).thenReturn(new ReferenceData.Snapshot(day, 7L, Map.of()));
        ReferenceController controller = new ReferenceController(reference);
        ResponseEntity<Map<String, Object>> fresh = controller.lists("\"ref-" + day + "-7\"");
        assertThat(fresh.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(fresh.getBody()).isNull();
        assertThat(fresh.getHeaders().getETag()).isEqualTo("\"ref-" + day + "-7\"");
        ResponseEntity<Map<String, Object>> stale = controller.lists("\"ref-old\"");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stale.getBody()).containsEntry("asOf", day.toString());
        assertThat(controller.lists(null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
