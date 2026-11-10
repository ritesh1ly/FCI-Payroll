package in.gov.fci.payroll.platform.config;

import in.gov.fci.payroll.platform.error.DataAccessExceptionHandler;
import in.gov.fci.payroll.platform.error.GlobalExceptionHandler;
import in.gov.fci.payroll.platform.error.ProblemDetailFactory;
import in.gov.fci.payroll.platform.error.ProblemDetailWriter;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import in.gov.fci.payroll.platform.error.SecurityExceptionHandler;
import in.gov.fci.payroll.platform.logging.ContextPropagation;
import in.gov.fci.payroll.platform.logging.EserLoggingAspect;
import in.gov.fci.payroll.platform.logging.MdcFilter;
import in.gov.fci.payroll.platform.logging.MdcSecurityContextFilter;
import in.gov.fci.payroll.platform.logging.ResultSummarizer;
import in.gov.fci.payroll.platform.logging.SecurityAuditLogger;
import in.gov.fci.payroll.platform.security.JsonKeyGuardFilter;
import in.gov.fci.payroll.platform.security.ManagementPortFilter;
import in.gov.fci.payroll.platform.security.ProblemDetailAccessDeniedHandler;
import in.gov.fci.payroll.platform.security.ProblemDetailAuthenticationEntryPoint;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskDecorator;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared web infrastructure of the backend (formerly the fci-common library): MDC and
 * management-port filters, the JSON key guard, the ESER aspect, the RFC 9457 error handling and the
 * security audit logger. Every bean backs off if the application defines its own.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(FciCommonProperties.class)
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class CommonInfrastructureConfig {

    /**
     * Order of the Spring Security filter chain ({@code SecurityProperties.DEFAULT_FILTER_ORDER}).
     */
    static final int SECURITY_FILTER_ORDER = -100;

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    static final int MDC_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE;
    static final int MANAGEMENT_PORT_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 1;
    static final int JSON_KEY_GUARD_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
    static final int MDC_SECURITY_FILTER_ORDER = SECURITY_FILTER_ORDER + 1;

    @Bean
    @ConditionalOnMissingBean
    Clock fciClock() {
        return Clock.systemUTC();
    }

    /**
     * The business day of the date constraints ({@code @PastOrPresent} on an order date, SRS-G-30)
     * is the Indian day, whatever zone the JVM runs in: with the system zone, an order dated today
     * was refused as future between 00:00 and 05:30 IST on a server running in UTC.
     */
    @Bean
    ValidationConfigurationCustomizer fciValidationClock(Clock clock) {
        Clock india = clock.withZone(BUSINESS_ZONE);
        return configuration -> configuration.clockProvider(() -> india);
    }

    @Bean
    @ConditionalOnMissingBean
    ProblemDetailFactory problemDetailFactory(
            FciCommonProperties properties,
            ObjectProvider<MessageSource> messageSource,
            Clock clock) {
        return new ProblemDetailFactory(
                properties.errorTypeBaseUri(), messageSource.getIfAvailable(), clock);
    }

    @Bean
    @ConditionalOnMissingBean
    ProblemDetailWriter problemDetailWriter(ObjectProvider<ObjectMapper> jsonMapper) {
        return new ProblemDetailWriter(jsonMapper.getIfAvailable(ObjectMapper::new));
    }

    @Bean
    @ConditionalOnMissingBean
    ProblemResponder problemResponder(ProblemDetailFactory factory, ProblemDetailWriter writer) {
        return new ProblemResponder(factory, writer);
    }

    @Bean
    @ConditionalOnMissingBean
    GlobalExceptionHandler fciGlobalExceptionHandler(ProblemResponder responder) {
        return new GlobalExceptionHandler(responder);
    }

    @Bean
    FilterRegistrationBean<MdcFilter> fciMdcFilter(
            FciCommonProperties properties, Environment environment) {
        String appName =
                properties.appName() != null
                        ? properties.appName()
                        : environment.getProperty("spring.application.name", "app");
        FilterRegistrationBean<MdcFilter> registration =
                new FilterRegistrationBean<>(new MdcFilter(appName));
        registration.setOrder(MDC_FILTER_ORDER);
        registration.setName("fciMdcFilter");
        registration.setDispatcherTypes(
                DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ManagementPortFilter> fciManagementPortFilter(
            FciCommonProperties properties) {
        FilterRegistrationBean<ManagementPortFilter> registration =
                new FilterRegistrationBean<>(
                        new ManagementPortFilter(
                                properties.managementPort(), properties.actuatorBasePath()));
        registration.setOrder(MANAGEMENT_PORT_FILTER_ORDER);
        registration.setName("fciManagementPortFilter");
        return registration;
    }

    @Bean
    FilterRegistrationBean<JsonKeyGuardFilter> fciJsonKeyGuardFilter(
            FciCommonProperties properties, ProblemResponder responder) {
        FilterRegistrationBean<JsonKeyGuardFilter> registration =
                new FilterRegistrationBean<>(
                        new JsonKeyGuardFilter(
                                responder,
                                properties.maxJsonBodyBytes(),
                                properties.maxJsonNestingDepth()));
        registration.setOrder(JSON_KEY_GUARD_FILTER_ORDER);
        registration.setName("fciJsonKeyGuardFilter");
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    ResultSummarizer resultSummarizer() {
        return new ResultSummarizer();
    }

    @Bean
    @ConditionalOnMissingBean
    EserLoggingAspect eserLoggingAspect(
            ResultSummarizer resultSummarizer, FciCommonProperties properties) {
        return new EserLoggingAspect(resultSummarizer, properties.logValueMaxLength());
    }

    @Bean
    @ConditionalOnMissingBean
    SecurityAuditLogger securityAuditLogger() {
        return new SecurityAuditLogger();
    }

    /** Propagates MDC and security context into {@code @Async} and scheduled tasks. */
    @Bean
    @ConditionalOnMissingBean
    TaskDecorator fciContextPropagatingTaskDecorator() {
        return ContextPropagation.taskDecorator();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.dao.DataAccessException")
    static class DataAccessConfiguration {

        @Bean
        @ConditionalOnMissingBean
        DataAccessExceptionHandler fciDataAccessExceptionHandler(
                ProblemResponder responder,
                org.springframework.context.ApplicationEventPublisher events) {
            return new DataAccessExceptionHandler(responder, events);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.web.SecurityFilterChain")
    static class SecurityConfiguration {

        @Bean
        @ConditionalOnMissingBean
        SecurityExceptionHandler fciSecurityExceptionHandler(ProblemResponder responder) {
            return new SecurityExceptionHandler(responder);
        }

        @Bean
        @ConditionalOnMissingBean
        ProblemDetailAuthenticationEntryPoint problemDetailAuthenticationEntryPoint(
                ProblemResponder responder) {
            return new ProblemDetailAuthenticationEntryPoint(responder);
        }

        @Bean
        @ConditionalOnMissingBean
        ProblemDetailAccessDeniedHandler problemDetailAccessDeniedHandler(
                ProblemResponder responder) {
            return new ProblemDetailAccessDeniedHandler(responder);
        }

        @Bean
        FilterRegistrationBean<MdcSecurityContextFilter> fciMdcSecurityContextFilter() {
            FilterRegistrationBean<MdcSecurityContextFilter> registration =
                    new FilterRegistrationBean<>(new MdcSecurityContextFilter());
            registration.setOrder(MDC_SECURITY_FILTER_ORDER);
            registration.setName("fciMdcSecurityContextFilter");
            return registration;
        }
    }
}
