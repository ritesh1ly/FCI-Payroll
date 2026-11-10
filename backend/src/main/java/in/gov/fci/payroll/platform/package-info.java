/**
 * Platform: the shared kernel every module builds on (errors, logging and masking, security
 * utilities, web helpers, persistence configuration, i18n, idempotency, notification). An open
 * module: all its packages may be used; it depends on no business module.
 */
@ApplicationModule(displayName = "Platform", type = ApplicationModule.Type.OPEN)
package in.gov.fci.payroll.platform;

import org.springframework.modulith.ApplicationModule;
