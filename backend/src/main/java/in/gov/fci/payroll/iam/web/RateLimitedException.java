package in.gov.fci.payroll.iam.web;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;

/** A sign-in or password-reset rate limit was hit; answered 429 with {@code Retry-After}. */
public class RateLimitedException extends FciException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super(CommonErrorCode.TOO_MANY_REQUESTS);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
