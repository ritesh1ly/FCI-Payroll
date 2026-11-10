package in.gov.fci.payroll.jobs.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * ADR-0025 (online / batch profiles; SRS-15-25): the online instance refuses to start with any
 * launch machinery or a task scheduler; under the local resilience profile (chaos-monkey) the
 * scheduler Chaos Monkey brings is allowed, never a job launcher.
 */
class OnlineBatchReadOnlyConfigTest {

    private final ConfigurableListableBeanFactory beans =
            mock(ConfigurableListableBeanFactory.class);
    private final Environment env = mock(Environment.class);

    private void present(Class<?> type) {
        when(beans.getBeanNamesForType(eq(type), anyBoolean(), anyBoolean()))
                .thenReturn(new String[] {"bean"});
    }

    private void absentAll() {
        when(beans.getBeanNamesForType(any(Class.class), anyBoolean(), anyBoolean()))
                .thenReturn(new String[0]);
    }

    private void start() {
        OnlineBatchReadOnlyConfig.onlineModeGuard(beans, env).afterSingletonsInstantiated();
    }

    @Test
    void SRS_15_25_anOnlineInstanceWithoutLaunchMachineryOrSchedulerStarts() {
        absentAll();
        assertThatCode(this::start).doesNotThrowAnyException();
    }

    @Test
    void SRS_15_25_aSchedulerOrAJobLauncherOnTheOnlineInstanceRefusesTheStart() {
        absentAll();
        present(ScheduledAnnotationBeanPostProcessor.class);
        assertThatThrownBy(this::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ScheduledAnnotationBeanPostProcessor");
        absentAll();
        present(JobOperator.class);
        present(JobRepository.class);
        assertThatThrownBy(this::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JobOperator")
                .hasMessageContaining("JobRepository");
    }

    @Test
    void SRS_15_25_underTheResilienceProfileOnlyTheSchedulerIsAllowed() {
        when(env.matchesProfiles("chaos-monkey")).thenReturn(true);
        absentAll();
        present(ScheduledAnnotationBeanPostProcessor.class);
        assertThatCode(this::start).doesNotThrowAnyException();
        verify(beans, never())
                .getBeanNamesForType(
                        eq(ScheduledAnnotationBeanPostProcessor.class), anyBoolean(), anyBoolean());
        present(JobOperator.class);
        assertThatThrownBy(this::start).isInstanceOf(IllegalStateException.class);
    }
}
