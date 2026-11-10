package in.gov.fci.payroll.integration.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * BR-INT-014 (FRS l.4675; US-23-02 AC4): an interface is implemented in the integration layer,
 * which maps the external message or file to the Payroll model; a change of the external format
 * changes only the adapter. The adapter contract therefore carries no counterparty type: only plain
 * Java values (the interface code, the staged record's key, type and payload as received). The
 * ArchUnit rule BR_INT_014 of ModularStructureTest keeps every other module off the adapters.
 */
class InterfaceAdapterContractTest {

    @Test
    void BR_INT_014_theAdapterContractCarriesOnlyPlainJavaValues() {
        Method[] methods = InterfaceAdapter.class.getDeclaredMethods();
        assertThat(methods)
                .extracting(Method::getName)
                .containsExactlyInAnyOrder("interfaceCode", "testConnection", "reprocess");
        for (Method m : methods) {
            assertThat(m.getReturnType().isPrimitive() || m.getReturnType() == String.class)
                    .as(m.getName())
                    .isTrue();
            assertThat(
                            Arrays.stream(m.getParameterTypes())
                                    .allMatch(p -> p.isPrimitive() || p == String.class))
                    .as(m.getName())
                    .isTrue();
        }
        assertThat(InterfaceAdapter.class.isInterface()).isTrue();
    }
}
