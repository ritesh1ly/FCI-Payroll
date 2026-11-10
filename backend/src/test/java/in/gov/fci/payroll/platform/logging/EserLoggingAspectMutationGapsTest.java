package in.gov.fci.payroll.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

/** Kills the surviving PIT mutants of {@link EserLoggingAspect}; ESER pattern (SRS-LOG). */
@ResourceLock("thread-context")
class EserLoggingAspectMutationGapsTest {

    public record L3(String v) {}

    public record L2(L3 c) {}

    public record L1(L2 b) {}

    public record L0(L1 a, String name, int count) {}

    /** Annotated holder of a method, used as the "declaring class" of a join point. */
    @Loggable(outcome = "HOLDER")
    public static class AnnotatedHolder {
        public String run() {
            return "x";
        }
    }

    public static class PlainHolder {
        public String run2() {
            return "y";
        }
    }

    @Loggable(outcome = "TYPE")
    public static class AnnotatedTarget {}

    public static class PlainTarget {}

    public static class Slow {
        @Loggable
        public String slowFail() throws InterruptedException {
            Thread.sleep(80);
            throw new IllegalStateException("slow");
        }
    }

    @AfterEach
    void clear() {
        MDC.clear();
    }

    private static EserLoggingAspect aspect() {
        return new EserLoggingAspect(new ResultSummarizer(), 128);
    }

    private static ProceedingJoinPoint joinPoint(Class<?> declaringType, Method method)
            throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getTarget()).thenReturn(null);
        when(signature.getMethod()).thenReturn(method);
        doReturn(declaringType).when(signature).getDeclaringType();
        when(joinPoint.proceed()).thenReturn("ok");
        return joinPoint;
    }

    @Test
    void recordsRenderEveryComponentSeparatedByCommaSpaceAndStopAtDepthTwo() {
        String rendered = aspect().renderValue(new L0(new L1(new L2(new L3("deep"))), "FCI", 7), 0);
        // First component has no leading separator, later ones are joined by ", ", and the
        // third nesting level (depth 2) collapses to the simple class name.
        assertThat(rendered).isEqualTo("L0{a=L1{b=L2}, name=FCI, count=7}");
    }

    @Test
    void aMethodDeclaredInAnAnnotatedClassIsLoggedEvenWhenTheTargetClassIsNot() throws Throwable {
        Method method = AnnotatedHolder.class.getMethod("run");
        ProceedingJoinPoint joinPoint = joinPoint(PlainTarget.class, method);
        try (LogCapture logs = LogCapture.of(PlainTarget.class, Level.DEBUG)) {
            assertThat(aspect().around(joinPoint)).isEqualTo("ok");
            assertThat(logs.messages())
                    .anyMatch(m -> m.startsWith("event=SUCCESS method=PlainTarget.run"))
                    .anyMatch(m -> m.contains("outcome=HOLDER"));
        }
    }

    @Test
    void theTargetClassAnnotationWinsOverTheDeclaringClassFallback() throws Throwable {
        Method method = PlainHolder.class.getMethod("run2");
        ProceedingJoinPoint joinPoint = joinPoint(AnnotatedTarget.class, method);
        try (LogCapture logs = LogCapture.of(AnnotatedTarget.class, Level.DEBUG)) {
            assertThat(aspect().around(joinPoint)).isEqualTo("ok");
            assertThat(logs.messages()).anyMatch(m -> m.contains("outcome=TYPE"));
        }
    }

    @Test
    void theDurationOfAFailingCallIsTheElapsedMillisecondsNotZeroNorHuge() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new Slow());
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect());
        Slow proxy = factory.getProxy();
        assertThatThrownBy(proxy::slowFail).isInstanceOf(IllegalStateException.class);
        long duration = Long.parseLong(MDC.get(MdcKeys.DURATION_MS));
        assertThat(duration).isBetween(60L, 30_000L);
    }
}
