package in.gov.fci.payroll.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import jakarta.validation.constraints.NotNull;
import java.lang.reflect.Method;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

@ResourceLock("thread-context")
class EserLoggingAspectTest {

    public record CreateUserRequest(
            String username, String password, @Sensitive String mobile, Nested nested) {}

    public record Nested(String officeId, String otp) {}

    public record Created(String id) implements Identifiable {}

    @Loggable(outcome = "USER_CREATED")
    public static class UserService {

        public Created create(
                CreateUserRequest request, String newPassword, @Sensitive String captcha) {
            return new Created("u-1");
        }

        public void fail() {
            throw new IllegalStateException("domain failure");
        }

        @Loggable(level = Loggable.Level.DEBUG, logArgs = false, logResult = false)
        public List<String> internal(String officeId) {
            return List.of("a", "b");
        }

        public String tag(@NotNull String officeId, @Sensitive String answer) {
            return officeId;
        }

        public String notIntercepted() {
            return "ok";
        }
    }

    public static class Unannotated {
        public String call() {
            return "plain";
        }
    }

    @AfterEach
    void clear() {
        MDC.clear();
    }

    private static <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new EserLoggingAspect(new ResultSummarizer(), 128));
        return factory.getProxy();
    }

    @Test
    void logsEntrySuccessAndReturnWithoutSecrets() {
        UserService service = proxy(new UserService());
        try (LogCapture logs = LogCapture.of(UserService.class, Level.DEBUG)) {
            service.create(
                    new CreateUserRequest(
                            "ritesh",
                            "Hunter2!Hunter2",
                            "+919876543210",
                            new Nested("RO-LKO", "123456")),
                    "N3wPassw0rd!",
                    "XK7P2Q");

            List<String> messages = logs.messages();
            assertThat(messages).hasSize(3);
            assertThat(messages.get(0))
                    .startsWith(
                            "event=ENTRY method=UserService.create"
                                    + " args={request=CreateUserRequest{")
                    .contains("username=ritesh", "officeId=RO-LKO")
                    .contains(
                            "password=***",
                            "mobile=***",
                            "otp=***",
                            "newPassword=***",
                            "captcha=***")
                    .doesNotContain("Hunter2", "9876543210", "123456", "N3wPassw0rd", "XK7P2Q");
            assertThat(messages.get(1))
                    .matches(
                            "event=SUCCESS method=UserService.create outcome=USER_CREATED"
                                    + " durationMs=\\d+");
            assertThat(messages.get(2))
                    .isEqualTo(
                            "event=RETURN method=UserService.create summary={type=Created,"
                                    + " id=u-1}");
            assertThat(logs.events().get(0).getLevel()).isEqualTo(Level.INFO);
            assertThat(logs.events().get(2).getLevel()).isEqualTo(Level.DEBUG);
        }
    }

    @Test
    void exceptionIsNotLoggedButDurationIsRecordedAndRethrown() {
        UserService service = proxy(new UserService());
        try (LogCapture logs = LogCapture.of(UserService.class, Level.DEBUG)) {
            assertThatThrownBy(service::fail)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("domain failure");

            assertThat(logs.messages()).hasSize(1).first().asString().startsWith("event=ENTRY");
            assertThat(logs.events()).noneMatch(e -> e.getLevel() == Level.ERROR);
            assertThat(MDC.get(MdcKeys.DURATION_MS)).matches("\\d+");
        }
    }

    @Test
    void methodLevelAnnotationOverridesTypeLevel() {
        UserService service = proxy(new UserService());
        try (LogCapture logs = LogCapture.of(UserService.class, Level.INFO)) {
            service.internal("RO-LKO");
            assertThat(logs.messages()).isEmpty();
        }
        try (LogCapture logs = LogCapture.of(UserService.class, Level.DEBUG)) {
            service.internal("RO-LKO");
            assertThat(logs.events()).extracting(ILoggingEvent::getLevel).containsOnly(Level.DEBUG);
            assertThat(logs.messages().get(0))
                    .isEqualTo("event=ENTRY method=UserService.internal args={}");
            assertThat(logs.messages()).hasSize(2);
        }
    }

    @Test
    void entryAndSuccessSkippedWhenLevelDisabled() {
        UserService service = proxy(new UserService());
        try (LogCapture logs = LogCapture.of(UserService.class, Level.WARN)) {
            assertThat(service.notIntercepted()).isEqualTo("ok");
            assertThat(logs.messages()).isEmpty();
        }
    }

    @Test
    void unannotatedBeansAreNotLogged() {
        Unannotated plain = proxy(new Unannotated());
        try (LogCapture logs = LogCapture.of(Unannotated.class, Level.DEBUG)) {
            assertThat(plain.call()).isEqualTo("plain");
            assertThat(logs.messages()).isEmpty();
        }
    }

    @Test
    void rendersValuesWithoutCallingToString() {
        EserLoggingAspect aspect = new EserLoggingAspect(new ResultSummarizer(), 8);
        Object secretHolder =
                new Object() {
                    @Override
                    public String toString() {
                        return "password=leak";
                    }
                };
        assertThat(aspect.renderValue(secretHolder, 0)).doesNotContain("leak");
        assertThat(aspect.renderValue(List.of(1, 2, 3), 0)).isEqualTo("[size=3]");
        assertThat(aspect.renderValue(java.util.Map.of("k", "v"), 0)).isEqualTo("{size=1}");
        assertThat(aspect.renderValue(new int[] {1, 2}, 0)).isEqualTo("[length=2]");
        assertThat(aspect.renderValue("a\nbcdefghijk", 0)).isEqualTo("a\\nbcdefg…(12)");
        assertThat(aspect.renderValue(null, 0)).isEqualTo("null");
        assertThat(aspect.renderValue(new Nested("RO-1", "1"), 2)).isEqualTo("Nested");
    }

    @Test
    void rendersEveryScalarKindAndExtraArgumentsWithoutASecret() throws Exception {
        EserLoggingAspect aspect = new EserLoggingAspect(new ResultSummarizer(), 64);
        assertThat(aspect.renderValue('x', 0)).isEqualTo("x");
        assertThat(aspect.renderValue(Boolean.TRUE, 0)).isEqualTo("true");
        assertThat(aspect.renderValue(Loggable.Level.DEBUG, 0)).isEqualTo("DEBUG");
        assertThat(aspect.renderValue(java.time.LocalDate.of(2026, 10, 24), 0))
                .isEqualTo("2026-10-24");
        assertThat(
                        aspect.renderValue(
                                java.util.UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
                                0))
                .isEqualTo("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");

        Method tag = UserService.class.getMethod("tag", String.class, String.class);
        assertThat(aspect.renderArguments(tag, null)).isEqualTo("{}");
        assertThat(aspect.renderArguments(tag, new Object[0])).isEqualTo("{}");
        // A constraint annotation is not @Sensitive; an argument beyond the signature (a proxy
        // passing more than declared) is named by position, never dropped nor trusted.
        assertThat(aspect.renderArguments(tag, new Object[] {"RO-LKO", "maiden name", 7}))
                .isEqualTo("{officeId=RO-LKO, answer=***, arg2=7}");
    }

    @Test
    void aJoinPointWithoutATargetOrAnAnnotationProceedsUnlogged() throws Throwable {
        EserLoggingAspect aspect = new EserLoggingAspect(new ResultSummarizer(), 64);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getTarget()).thenReturn(null);
        when(signature.getMethod()).thenReturn(Unannotated.class.getMethod("call"));
        doReturn(Unannotated.class).when(signature).getDeclaringType();
        when(joinPoint.proceed()).thenReturn("plain");
        try (LogCapture logs = LogCapture.of(Unannotated.class, Level.DEBUG)) {
            assertThat(aspect.around(joinPoint)).isEqualTo("plain");
            assertThat(logs.messages()).isEmpty();
        }
    }
}
