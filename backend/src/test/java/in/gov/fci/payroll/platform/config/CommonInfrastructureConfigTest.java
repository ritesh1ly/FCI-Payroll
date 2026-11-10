package in.gov.fci.payroll.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.error.DataAccessExceptionHandler;
import in.gov.fci.payroll.platform.error.GlobalExceptionHandler;
import in.gov.fci.payroll.platform.error.ProblemDetailFactory;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import in.gov.fci.payroll.platform.error.SecurityExceptionHandler;
import in.gov.fci.payroll.platform.logging.EserLoggingAspect;
import in.gov.fci.payroll.platform.logging.MdcFilter;
import in.gov.fci.payroll.platform.logging.SecurityAuditLogger;
import in.gov.fci.payroll.platform.security.JsonKeyGuardFilter;
import in.gov.fci.payroll.platform.security.ProblemDetailAccessDeniedHandler;
import in.gov.fci.payroll.platform.security.ProblemDetailAuthenticationEntryPoint;
import jakarta.validation.Configuration;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;

class CommonInfrastructureConfigTest {

    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(CommonInfrastructureConfig.class))
                    .withPropertyValues("spring.application.name=fci-api");

    /** The order date of today (SRS-G-30) at 01:30 IST, when the UTC day is still yesterday. */
    private record Dated(@jakarta.validation.constraints.PastOrPresent LocalDate orderDate) {}

    @Test
    void SRS_G_30_aDateConstraintReadsTheIndianDayWhateverTheZoneOfTheJvm() {
        Clock utc = Clock.fixed(Instant.parse("2026-11-06T20:00:00Z"), ZoneOffset.UTC);
        Configuration<?> configuration = Validation.byDefaultProvider().configure();
        new CommonInfrastructureConfig().fciValidationClock(utc).customize(configuration);
        try (ValidatorFactory factory = configuration.buildValidatorFactory()) {
            assertThat(factory.getValidator().validate(new Dated(LocalDate.of(2026, 11, 7))))
                    .as("today in India")
                    .isEmpty();
            assertThat(factory.getValidator().validate(new Dated(LocalDate.of(2026, 11, 8))))
                    .as("tomorrow in India")
                    .hasSize(1);
        }
    }

    @Test
    void registersInfrastructureInServletApplication() {
        runner.run(
                context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(ProblemDetailFactory.class)
                            .hasSingleBean(ProblemResponder.class)
                            .hasSingleBean(GlobalExceptionHandler.class)
                            .hasSingleBean(DataAccessExceptionHandler.class)
                            .hasSingleBean(SecurityExceptionHandler.class)
                            .hasSingleBean(ProblemDetailAuthenticationEntryPoint.class)
                            .hasSingleBean(ProblemDetailAccessDeniedHandler.class)
                            .hasSingleBean(EserLoggingAspect.class)
                            .hasSingleBean(SecurityAuditLogger.class)
                            .hasSingleBean(TaskDecorator.class)
                            .hasSingleBean(Clock.class);
                    FilterRegistrationBean<?> mdc =
                            context.getBean("fciMdcFilter", FilterRegistrationBean.class);
                    assertThat(mdc.getFilter()).isInstanceOf(MdcFilter.class);
                    assertThat(mdc.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
                    FilterRegistrationBean<?> guard =
                            context.getBean("fciJsonKeyGuardFilter", FilterRegistrationBean.class);
                    assertThat(guard.getFilter()).isInstanceOf(JsonKeyGuardFilter.class);
                    assertThat(guard.getOrder())
                            .isLessThan(CommonInfrastructureConfig.SECURITY_FILTER_ORDER);
                    assertThat(
                                    context.getBean(
                                                    "fciMdcSecurityContextFilter",
                                                    FilterRegistrationBean.class)
                                            .getOrder())
                            .isGreaterThan(CommonInfrastructureConfig.SECURITY_FILTER_ORDER);
                });
    }

    @Test
    void bindsAndValidatesProperties() {
        runner.withPropertyValues("fci.common.app-name=api", "fci.common.management-port=8081")
                .run(
                        context -> {
                            FciCommonProperties properties =
                                    context.getBean(FciCommonProperties.class);
                            assertThat(properties.appName()).isEqualTo("api");
                            assertThat(properties.managementPort()).isEqualTo(8081);
                            assertThat(properties.maxJsonBodyBytes()).isEqualTo(1_048_576);
                            assertThat(properties.errorTypeBaseUri())
                                    .isEqualTo("https://errors.fci.gov.in/");
                        });
        runner.withPropertyValues("fci.common.max-json-nesting-depth=1000")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void backsOffWhenApplicationDefinesItsOwnBeans() {
        runner.withUserConfiguration(CustomClock.class)
                .run(
                        context ->
                                assertThat(context.getBean(Clock.class))
                                        .isSameAs(CustomClock.CLOCK));
    }

    @Test
    void skipsSecurityAndDataAccessIntegrationWhenAbsent() {
        runner.withClassLoader(
                        new FilteredClassLoader(
                                "org.springframework.security.web", "org.springframework.dao"))
                .run(
                        context ->
                                assertThat(context)
                                        .hasNotFailed()
                                        .doesNotHaveBean(SecurityExceptionHandler.class)
                                        .doesNotHaveBean(DataAccessExceptionHandler.class)
                                        .hasSingleBean(GlobalExceptionHandler.class));
    }

    @Test
    void inactiveOutsideServletApplications() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonInfrastructureConfig.class))
                .run(context -> assertThat(context).doesNotHaveBean(ProblemResponder.class));
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class CustomClock {
        static final Clock CLOCK = Clock.systemDefaultZone();

        @Bean
        Clock customClock() {
            return CLOCK;
        }
    }
}
