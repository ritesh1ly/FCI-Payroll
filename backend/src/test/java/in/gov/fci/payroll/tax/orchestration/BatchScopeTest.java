package in.gov.fci.payroll.tax.orchestration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The member loops of C-10 and C-11 (SRS-NF-6: the year end of 50,000 members; profiled in
 * docs/perf/module-08-profile.md): queries stop flushing, and every 200 members the pending writes
 * go out and the loaded entities are dropped; the totals read back see the loop's own writes.
 */
class BatchScopeTest {

    private final EntityManager em = mock(EntityManager.class);
    private final BatchScope scope = new BatchScope();

    BatchScopeTest() {
        ReflectionTestUtils.setField(scope, "em", em);
    }

    @Test
    void SRS_NF_6_everyTwoHundredMembersThePendingWritesGoOutAndTheEntitiesAreDropped() {
        scope.begin();
        verify(em).setFlushMode(FlushModeType.COMMIT);
        scope.checkpoint(0);
        scope.checkpoint(199);
        verify(em, never()).flush();
        scope.checkpoint(BatchScope.EVERY);
        scope.checkpoint(401);
        scope.checkpoint(400);
        verify(em, times(2)).flush();
        verify(em, times(2)).clear();
        scope.flush();
        verify(em, times(3)).flush();
    }
}
