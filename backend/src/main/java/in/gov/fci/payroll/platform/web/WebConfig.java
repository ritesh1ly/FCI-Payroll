package in.gov.fci.payroll.platform.web;

import in.gov.fci.payroll.platform.error.ProblemResponder;
import in.gov.fci.payroll.platform.idempotency.IdempotencyFilter;
import in.gov.fci.payroll.platform.idempotency.IdempotencyService;
import in.gov.fci.payroll.platform.security.CurrentUser;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Servlet filters of the online profile that run after Spring Security (order -100). */
@Configuration(proxyBeanMethods = false)
@org.springframework.context.annotation.Profile("online")
public class WebConfig {

    /** After the security chain and the MDC enrichment filter (-99). */
    static final int IDEMPOTENCY_FILTER_ORDER = -98;

    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(
            IdempotencyService idempotency, CurrentUser currentUser, ProblemResponder responder) {
        FilterRegistrationBean<IdempotencyFilter> registration =
                new FilterRegistrationBean<>(
                        new IdempotencyFilter(idempotency, currentUser, responder));
        registration.setOrder(IDEMPOTENCY_FILTER_ORDER);
        registration.setName("fciIdempotencyFilter");
        return registration;
    }
}
