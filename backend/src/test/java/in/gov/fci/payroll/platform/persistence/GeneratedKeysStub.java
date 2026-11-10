package in.gov.fci.payroll.platform.persistence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.SqlProvider;
import org.springframework.jdbc.support.KeyHolder;

/**
 * Mocked {@code JdbcTemplate}s and {@link GeneratedKeys#insert}: the key the database would
 * generate, and the values an insert bound.
 */
public final class GeneratedKeysStub {

    private GeneratedKeysStub() {}

    /**
     * Every {@link GeneratedKeys#insert} on {@code jdbc} whose SQL contains {@code fragment}
     * answers {@code id}; {@code null} = the database returned no key.
     */
    public static void answer(JdbcOperations jdbc, String fragment, Long id) {
        doAnswer(
                        inv -> {
                            if (id != null) {
                                KeyHolder key = inv.getArgument(1);
                                key.getKeyList().add(Map.of("id", id));
                            }
                            return 1;
                        })
                .when(jdbc)
                .update(argThat(insertOf(fragment)), any(KeyHolder.class));
    }

    /** An insert whose SQL contains {@code fragment}. */
    public static ArgumentMatcher<PreparedStatementCreator> insertOf(String fragment) {
        return p ->
                p instanceof SqlProvider s && s.getSql() != null && s.getSql().contains(fragment);
    }

    /** The values the last insert whose SQL contains {@code fragment} bound, in order. */
    public static List<Object> args(JdbcOperations jdbc, String fragment) {
        ArgumentCaptor<PreparedStatementCreator> sent =
                ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(jdbc, atLeastOnce()).update(sent.capture(), any(KeyHolder.class));
        return sent.getAllValues().stream()
                .filter(insertOf(fragment)::matches)
                .map(p -> java.util.Arrays.asList(((GeneratedKeys.Insert) p).args()))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("No insert of " + fragment));
    }
}
