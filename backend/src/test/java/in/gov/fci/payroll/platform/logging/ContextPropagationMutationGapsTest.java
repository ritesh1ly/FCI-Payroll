package in.gov.fci.payroll.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/** The context executor and the security-context restore of {@link ContextPropagation}. */
@ResourceLock("thread-context")
class ContextPropagationMutationGapsTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theContextExecutorRunsDaemonThreadsUpToTheGivenConcurrencyUnderItsPrefix() {
        SimpleAsyncTaskExecutor executor = ContextPropagation.contextExecutor("tl-", 3);
        assertThat(executor.isDaemon()).isTrue();
        assertThat(executor.getConcurrencyLimit()).isEqualTo(3);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("tl-");
    }

    @Test
    void restoringSomethingThatIsNoSecurityContextClearsTheCurrentOne() throws Exception {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("e104882", "n/a", List.of()));
        SecurityContextHolder.setContext(context);
        Class<?> support =
                Class.forName(
                        "in.gov.fci.payroll.platform.logging.ContextPropagation"
                                + "$SecurityContextSupport");
        Method restore = support.getDeclaredMethod("restore", Object.class);
        restore.setAccessible(true);

        restore.invoke(null, new Object[] {null});

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
