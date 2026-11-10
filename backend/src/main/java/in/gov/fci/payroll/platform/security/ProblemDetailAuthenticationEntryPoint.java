package in.gov.fci.payroll.platform.security;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/** Answers unauthenticated requests with a 401 problem response and no {@code WWW-Authenticate}. */
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemResponder responder;

    public ProblemDetailAuthenticationEntryPoint(ProblemResponder responder) {
        this.responder = responder;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        responder.write(authException, CommonErrorCode.AUTHENTICATION_REQUIRED, request, response);
    }
}
