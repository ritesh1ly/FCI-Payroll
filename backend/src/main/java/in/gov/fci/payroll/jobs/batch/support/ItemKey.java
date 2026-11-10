package in.gov.fci.payroll.jobs.batch.support;

/** An item that can name itself in logs and in the failure log without personal data (an id). */
public interface ItemKey {

    String itemKey();

    static String of(Object item) {
        return item instanceof ItemKey key
                ? key.itemKey()
                : (item == null ? null : item.getClass().getSimpleName());
    }
}
