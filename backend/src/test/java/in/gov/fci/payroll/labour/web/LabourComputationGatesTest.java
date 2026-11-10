package in.gov.fci.payroll.labour.web;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.reporting.web.ReportScreensController;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * R-RBAC on the C-03 to C-06, O-03 and O-06 reads (Wireframes v6.1, OD-121): each read carries its
 * own screen's VIEW gate, and neither controller maps anything but GET (nothing here writes; the
 * runs wait for FCI).
 */
class LabourComputationGatesTest {

    private static Map<String, String> gates(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PreAuthorize.class))
                .collect(
                        Collectors.toMap(
                                m -> m.getAnnotation(GetMapping.class).value()[0],
                                LabourComputationGatesTest::gate));
    }

    private static String gate(Method m) {
        return m.getAnnotation(PreAuthorize.class).value();
    }

    @Test
    void SRS_G_54_eachComputationReadIsItsScreensView() {
        assertThat(gates(LabourComputationController.class))
                .hasSize(10)
                .containsEntry(
                        "/v1/labour/computations/departmental/gangs", "@authz.gate('C-03','VIEW')")
                .containsEntry("/v1/labour/computations/dps/gangs", "@authz.gate('C-04','VIEW')")
                .containsEntry("/v1/labour/computations/nwnp/gangs", "@authz.gate('C-05','VIEW')")
                .containsEntry("/v1/labour/incentives/basics", "@authz.gate('C-06','VIEW')");
    }

    @Test
    void SRS_G_54_theReportReadsAreO03AndO06View() {
        assertThat(gates(ReportScreensController.class))
                .hasSize(10)
                .containsEntry("/v1/remittances/overview", "@authz.gate('O-11','VIEW')")
                .containsEntry("/v1/reports/labour-catalogue", "@authz.gate('O-03','VIEW')")
                .containsEntry("/v1/reports/cpf-eseva", "@authz.gate('O-07','VIEW')")
                .containsEntry("/v1/reports/cpf-eseva/months", "@authz.gate('O-07','VIEW')")
                .containsEntry("/v1/reports/cpf-reconciliation", "@authz.gate('O-08','VIEW')")
                .containsEntry("/v1/reports/invoices/lines", "@authz.gate('O-06','VIEW')");
    }
}
