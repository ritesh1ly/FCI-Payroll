package in.gov.fci.payroll;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * FCILekha Payroll backend: one WAR ({@code fci-payroll.war}, context {@code /api}) deployed twice
 * on Tomcat 10.1 (ADR-0016). The Spring profile chooses the mode:
 *
 * <ul>
 *   <li>{@code online}: REST controllers, session login, the browser APIs; no scheduler;
 *   <li>{@code batch}: the job-queue poller, Spring Batch jobs, scheduled jobs; no REST API.
 * </ul>
 *
 * Domain services, data access and caches are shared by both.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FciPayrollApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        SpringApplication.run(FciPayrollApplication.class, args);
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(FciPayrollApplication.class);
    }
}
