package in.gov.fci.payroll.audit.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * One audit row per changed field (SRS-20-2, BR-AUDIT-001 / 003): the before and after images of a
 * record compared field by field. A field unchanged writes nothing; a record without a before image
 * (a first version) writes its initial values.
 */
public final class FieldChanges {

    private FieldChanges() {}

    /**
     * The entries for {@code before} → {@code after}, in field-name order. {@code before} null is a
     * new record: every non-null field is an {@link ChangeType#INITIAL_VALUE}. Values are the
     * display text the screens show (never secrets).
     */
    public static List<AuditService.Entry> between(
            String entity,
            String key,
            Map<String, String> before,
            Map<String, String> after,
            String reason) {
        Objects.requireNonNull(after, "after");
        List<AuditService.Entry> entries = new ArrayList<>();
        TreeSet<String> fields = new TreeSet<>(after.keySet());
        if (before != null) {
            fields.addAll(before.keySet());
        }
        for (String field : fields) {
            String old = before == null ? null : before.get(field);
            String now = after.get(field);
            if (Objects.equals(old, now) || (before == null && now == null)) {
                continue;
            }
            AuditService.Entry entry =
                    AuditService.Entry.change(entity, key, field, old, now, reason);
            entries.add(before == null ? entry.type(ChangeType.INITIAL_VALUE) : entry);
        }
        return entries;
    }
}
