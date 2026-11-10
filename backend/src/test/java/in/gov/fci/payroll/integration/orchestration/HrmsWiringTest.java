package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.integration.api.HrmsGateway;
import in.gov.fci.payroll.integration.api.InterfaceIntake;
import in.gov.fci.payroll.integration.api.InterfaceSettings;
import in.gov.fci.payroll.integration.config.HrmsApiProperties;
import in.gov.fci.payroll.integration.config.HrmsClientProperties;
import in.gov.fci.payroll.integration.config.HrmsCredentialsProperties;
import in.gov.fci.payroll.integration.config.HrmsLogProperties;
import in.gov.fci.payroll.platform.logging.MaskingPolicy;
import in.gov.fci.payroll.platform.logging.MaskingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * SRS-INT-1, R-INT: the gateway and the client that carry Payroll's calls to HRMS are built by
 * Spring from their collaborators. Both classes have more than one constructor (the short ones are
 * for tests); this proves the container picks the full one, which a plain unit test never does, and
 * that a setting that cannot work stops the start.
 */
class HrmsWiringTest {

    private static void register(
            AnnotationConfigApplicationContext context, HrmsApiProperties apis) {
        context.registerBean(HrmsApiProperties.class, () -> apis);
        context.registerBean(
                HrmsClientProperties.class,
                () ->
                        new HrmsClientProperties(
                                null, null, null, null, null, null, null, null, null, null));
        context.registerBean(
                HrmsCredentialsProperties.class,
                () -> new HrmsCredentialsProperties(null, null, null, null));
        context.registerBean(HrmsLogProperties.class, () -> new HrmsLogProperties(null, null));
        context.registerBean(
                MaskingPolicy.class, () -> new MaskingPolicy(new MaskingProperties(null, null)));
        context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        context.registerBean(InterfaceSettings.class, () -> code -> java.util.Optional.empty());
        context.registerBean(InterfaceIntake.class, () -> mock(InterfaceIntake.class));
        context.registerBean(IntegrationStore.class, () -> mock(IntegrationStore.class));
        context.registerBean(FailureAlerts.class, () -> mock(FailureAlerts.class));
        context.registerBean(HrmsRefusals.class, () -> mock(HrmsRefusals.class));
        context.register(HrmsClient.class, HrmsGatewayService.class);
    }

    @Test
    void SRS_INT_1_springBuildsTheClientAndTheGatewayFromTheirCollaborators() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            register(context, new HrmsApiProperties(null, null, null, null));
            context.refresh();
            assertThat(context.getBean(HrmsGateway.class)).isInstanceOf(HrmsGatewayService.class);
            assertThat(context.getBean(HrmsClient.class)).isNotNull();
        }
    }

    @Test
    void R_INT_aSettingThatCannotWorkStopsTheStartAndIsNamed() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            register(
                    context,
                    new HrmsApiProperties(
                            null,
                            Map.of("not_an_api", new HrmsApiProperties.ApiUrl("x")),
                            null,
                            null));
            assertThatThrownBy(context::refresh)
                    .hasStackTraceContaining("invalid fci.integration.hrms settings")
                    .hasStackTraceContaining("not_an_api");
        }
    }
}
