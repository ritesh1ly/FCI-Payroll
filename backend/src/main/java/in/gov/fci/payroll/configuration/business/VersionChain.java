package in.gov.fci.payroll.configuration.business;

import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The versions of one master key, newest first, with the status of each version's approval: which
 * version is in force on a day, which one awaits the checker, whether a new start date is free, and
 * closing the versions an approved successor replaces (SRS-16-22, SRS-G-37: effective dated, never
 * overlapping, never deleted). Pure rules over rows already read.
 *
 * @param <V> the version row
 */
public final class VersionChain<V extends DatedVersion> {

    private final List<V> versions;
    private final Map<Long, ApprovalStatus> status;

    public VersionChain(List<V> versions, Map<Long, ApprovalStatus> status) {
        this.versions = List.copyOf(versions);
        this.status = Map.copyOf(status);
    }

    /** The approval ids a status lookup needs. */
    public static List<Long> approvalIds(List<? extends DatedVersion> versions) {
        return versions.stream().map(DatedVersion::getApprovalId).filter(Objects::nonNull).toList();
    }

    public ApprovalStatus statusOf(V version) {
        // An immutable map refuses a null key: a version without an approval has no status.
        return version == null || version.getApprovalId() == null
                ? null
                : status.get(version.getApprovalId());
    }

    public boolean isApproved(V version) {
        return statusOf(version) == ApprovalStatus.APPROVED;
    }

    /** The approved version whose dates cover {@code day}, if any. */
    public Optional<V> inForceOn(LocalDate day) {
        return versions.stream().filter(v -> isApproved(v) && v.coversDay(day)).findFirst();
    }

    /** The version awaiting the checker, if any. */
    public Optional<V> pending() {
        return versions.stream()
                .filter(v -> statusOf(v) == ApprovalStatus.PENDING_APPROVAL)
                .findFirst();
    }

    /**
     * A new version starts after every approved one and on a date no other version holds
     * (MASTER_DATE_NOT_AFTER, MASTER_DATE_TAKEN).
     */
    public void requireFreeDate(LocalDate from) {
        MasterVersioning.requireFreeDate(
                from,
                versions.stream().filter(this::isApproved).map(V::getEffectiveFrom).toList(),
                versions.stream().map(V::getEffectiveFrom).toList());
    }

    /**
     * Ends every approved, open version that starts before {@code approved} the day before it
     * starts; each changed row goes to {@code save}.
     *
     * @return the versions closed, newest first (the first is the one in force before)
     */
    public List<V> closeBefore(V approved, Consumer<V> save) {
        List<V> closed = new ArrayList<>();
        for (V previous : versions) {
            if (previous != approved
                    && !Objects.equals(previous.getApprovalId(), approved.getApprovalId())
                    && isApproved(previous)
                    && previous.getEffectiveTo() == null
                    && previous.getEffectiveFrom().isBefore(approved.getEffectiveFrom())) {
                previous.endBefore(approved.getEffectiveFrom());
                save.accept(previous);
                closed.add(previous);
            }
        }
        return closed;
    }

    /** The next version number (1 for a first version). */
    public static int nextNumber(List<Integer> numbers) {
        return numbers.stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
    }
}
