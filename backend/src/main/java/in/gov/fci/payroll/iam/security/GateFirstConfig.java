package in.gov.fci.payroll.iam.security;

import in.gov.fci.payroll.iam.orchestration.AccessPolicy;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers {@link GateFirstInterceptor}: the endpoint gate before the request is read. */
@Configuration(proxyBeanMethods = false)
public class GateFirstConfig implements WebMvcConfigurer {

    private final AccessPolicy policy;

    public GateFirstConfig(AccessPolicy policy) {
        this.policy = policy;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new GateFirstInterceptor(policy));
    }
}
