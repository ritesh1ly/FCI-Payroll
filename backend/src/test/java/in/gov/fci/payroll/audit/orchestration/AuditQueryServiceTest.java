package in.gov.fci.payroll.audit.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuditQueryServiceTest {

    @Test
    void csvCellsAreQuotedAndFormulaSafe() {
        assertThat(AuditQueryService.cell(null)).isEmpty();
        assertThat(AuditQueryService.cell("")).isEqualTo("\"\"");
        assertThat(AuditQueryService.cell("plain")).isEqualTo("\"plain\"");
        assertThat(AuditQueryService.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(AuditQueryService.cell("=HYPERLINK(1)")).isEqualTo("\"'=HYPERLINK(1)\"");
        assertThat(AuditQueryService.cell("+1")).startsWith("\"'");
        assertThat(AuditQueryService.cell("-1")).startsWith("\"'");
        assertThat(AuditQueryService.cell("@x")).startsWith("\"'");
    }
}
