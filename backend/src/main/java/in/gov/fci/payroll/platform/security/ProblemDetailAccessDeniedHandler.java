package in.gov.fci.payroll.platform.security;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Answers requests denied by the filter chain (including CSRF failures) with a 403 problem. */
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponder responder;

    public ProblemDetailAccessDeniedHandler(ProblemResponder responder) {
        this.responder = responder;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {
        responder.write(accessDeniedException, CommonErrorCode.ACCESS_DENIED, request, response);
    }
}
