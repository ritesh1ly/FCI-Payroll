package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.payroll.web.PayslipController;
import in.gov.fci.payroll.settlement.web.SettlementController;
import in.gov.fci.payroll.tax.web.StatutoryOutputController;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * R-RBAC, OD-144: every PDF download carries the gate of the screen whose data it prints, the same
 * as its JSON read: E-01 SELF for the member's own payslip, E-01 VIEW for the support preview, O-01
 * VIEW for the members and the bulk job files, E-01 SELF or C-10 VIEW for Form 16, O-05 VIEW for
 * the settlement statement. The own-record and scope checks are the services'
 * (PayslipPdfServiceTest, Form16ServiceTest); the gate refuses a role without the right before any
 * read.
 */
class PdfDownloadGatesTest {

    private static final Map<String, String> EXPECTED =
            Map.ofEntries(
                    Map.entry("/v1/payslips/mine/{yymm}/pdf", "@authz.gate('E-01','SELF')"),
                    Map.entry("/v1/payslips/mine/zip", "@authz.gate('E-01','SELF')"),
                    Map.entry("/v1/payslips/mine/bulk-settings", "@authz.gate('E-01','SELF')"),
                    Map.entry(
                            "/v1/payslips/previews/{session}/{yymm}/pdf",
                            "@authz.gate('E-01','VIEW')"),
                    Map.entry(
                            "/v1/payslips/members/{eis}/{yymm}/pdf", "@authz.gate('O-01','VIEW')"),
                    Map.entry("/v1/payslip-bulk-files", "@authz.gate('O-01','VIEW')"),
                    Map.entry("/v1/payslip-bulk-files/{id}/file", "@authz.gate('O-01','VIEW')"),
                    Map.entry(
                            "/v1/tax/form16-certificates/{id}/part-b",
                            "@authz.gateEither('E-01','SELF','C-10','VIEW')"),
                    Map.entry("/v1/tax/form16-bulk-files", "@authz.gate('C-10','VIEW')"),
                    Map.entry("/v1/tax/form16-bulk-files/{id}/file", "@authz.gate('C-10','VIEW')"),
                    Map.entry("/v1/settlement-cases/{id}/statement", "@authz.gate('O-05','VIEW')"));

    @Test
    void R_RBAC_everyPdfDownloadIsGatedByTheScreenOfItsData() {
        Map<String, String> found = new java.util.HashMap<>();
        for (Class<?> controller :
                new Class<?>[] {
                    PayslipController.class,
                    StatutoryOutputController.class,
                    SettlementController.class
                }) {
            for (Method m : controller.getDeclaredMethods()) {
                GetMapping get = m.getAnnotation(GetMapping.class);
                if (get == null) {
                    continue;
                }
                Arrays.stream(get.value())
                        .filter(EXPECTED::containsKey)
                        .forEach(p -> found.put(p, m.getAnnotation(PreAuthorize.class).value()));
            }
        }
        assertThat(found).isEqualTo(EXPECTED);
    }
}
