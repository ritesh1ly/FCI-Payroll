package in.gov.fci.payroll.jobs.batch.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The failure log (batch_skipped_item.item_key) names a skipped record by its key, or by its type
 * when it has none, and never by its content: no personal data reaches the log.
 */
class ItemKeyTest {

    private record Member(long employeeId, String name) implements ItemKey {
        @Override
        public String itemKey() {
            return String.valueOf(employeeId);
        }
    }

    private record Row(String pan) {}

    @Test
    void aKeyedItemIsNamedByItsKey() {
        assertThat(ItemKey.of(new Member(104882L, "A. Kumar"))).isEqualTo("104882");
    }

    @Test
    void anItemWithoutAKeyIsNamedByItsTypeNeverItsContent() {
        assertThat(ItemKey.of(new Row("ABCDE1234F"))).isEqualTo("Row");
    }

    @Test
    void aMissingItemHasNoKey() {
        assertThat(ItemKey.of(null)).isNull();
    }
}
