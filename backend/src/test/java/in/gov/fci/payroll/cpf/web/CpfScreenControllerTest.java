package in.gov.fci.payroll.cpf.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.web.RuleCatalogueController;
import in.gov.fci.payroll.cpf.orchestration.CpfScreenService;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * R-RBAC on the reads of the drawn CPF screens (OD-121): each carries its own screen's VIEW gate, a
 * write is not mapped at all (I-12 and I-13 hold no write: PENDING 07h-blocked, 07k-blocked), and
 * the M-18 / M-19 registers are read on their own screen, not on M-08.
 */
class CpfScreenControllerTest {

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final CpfScreenService screens = mock(CpfScreenService.class);
    private final Paging paging = mock(Paging.class);
    private final CpfScreenController controller =
            new CpfScreenController(contexts, screens, paging);

    private static Map<String, String> gates(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PreAuthorize.class))
                .filter(m -> m.isAnnotationPresent(GetMapping.class))
                .filter(m -> m.getAnnotation(GetMapping.class).value().length > 0)
                .collect(
                        Collectors.toMap(
                                m -> m.getAnnotation(GetMapping.class).value()[0],
                                CpfScreenControllerTest::gate));
    }

    private static String gate(Method m) {
        return m.getAnnotation(PreAuthorize.class).value();
    }

    @Test
    void SRS_22_1_eachReadIsItsScreensView() {
        assertThat(gates(CpfScreenController.class))
                .hasSize(8)
                .containsEntry("/v1/cpf/deputations/board", "@authz.gate('I-09','VIEW')")
                .containsEntry("/v1/cpf/deputations/members", "@authz.gate('I-09','VIEW')")
                .containsEntry(
                        "/v1/cpf/opening-balance-corrections/board", "@authz.gate('I-22','VIEW')")
                .containsEntry(
                        "/v1/cpf/opening-balance-corrections/lines", "@authz.gate('I-22','VIEW')")
                .containsEntry("/v1/cpf/supplementary-payments", "@authz.gate('I-12','VIEW')")
                .containsEntry("/v1/cpf/parent-zone-changes", "@authz.gate('I-13','VIEW')")
                .containsEntry(
                        "/v1/cpf/parent-zone-changes/{reference}", "@authz.gate('I-13','VIEW')")
                .containsEntry("/v1/cpf/iocpf-pending", "@authz.gate('I-13','VIEW')");
    }

    @Test
    void SRS_6_49_nothingHereWrites() {
        for (Method m : CpfScreenController.class.getDeclaredMethods()) {
            assertThat(m.isAnnotationPresent(PostMapping.class)).isFalse();
            assertThat(m.isAnnotationPresent(PutMapping.class)).isFalse();
            assertThat(m.isAnnotationPresent(PatchMapping.class)).isFalse();
            assertThat(m.isAnnotationPresent(DeleteMapping.class)).isFalse();
        }
    }

    @Test
    void SRS_6_47_theRegistersAreReadOnTheirOwnScreen() {
        assertThat(gates(RuleCatalogueController.class))
                .containsEntry("/register/m-18", "@authz.gate('M-18','VIEW')")
                .containsEntry("/register/m-19", "@authz.gate('M-19','VIEW')");
    }

    @Test
    void SRS_G_2_theCallerAndThePageReachTheService() {
        AccessContext me = mock(AccessContext.class);
        PageSpec spec = new PageSpec(0, 20);
        when(contexts.current()).thenReturn(me);
        when(paging.resolve(any(), any())).thenReturn(spec);
        controller.deputationBoard("202611");
        controller.deputedMembers(0, 20);
        controller.correctionBoard();
        controller.correctionLines(0, 20);
        controller.supplementaryPayments(0, 20);
        controller.zoneChanges(0, 20);
        controller.zoneChange("HRMS-PZC-0014");
        controller.iocpfPending(0, 20);
        verify(screens).deputationBoard(me, "202611");
        verify(screens).deputedMembers(me, spec);
        verify(screens).correctionBoard(me);
        verify(screens).correctionLines(me, spec);
        verify(screens).supplementaryPayments(me, spec);
        verify(screens).zoneChanges(me, spec);
        verify(screens).zoneChange(me, "HRMS-PZC-0014");
        verify(screens).iocpfPending(me, spec);
    }
}
