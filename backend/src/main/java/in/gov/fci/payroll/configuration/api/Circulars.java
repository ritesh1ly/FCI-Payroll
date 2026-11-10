package in.gov.fci.payroll.configuration.api;

import java.util.Collection;
import java.util.Map;

/**
 * M-25 circulars for the modules whose rules, master entries and transactions an order drives
 * (SRS-G-38, SRS-G-30): a general order is uploaded once on M-25 and linked wherever it is used,
 * never uploaded again on the transaction. Only an Active circular can be linked; links are kept
 * for ever (where used), so any payment can be traced to its circular.
 */
public interface Circulars {

    /** What a link points at ({@code circular_order_link.link_type}). */
    enum LinkType {
        RULE_VERSION,
        MASTER_ENTRY,
        TRANSACTION
    }

    /**
     * Fails with CIRCULAR_NOT_ACTIVE unless circular {@code circularId} exists and is Active (not
     * found: the same error, so an id guessed from elsewhere reveals nothing more).
     */
    void requireActive(Long circularId);

    /**
     * As {@link #requireActive(Long)}, and of category {@code category} ({@code
     * circular_order.category_code}, reference list CIRCULAR_CATEGORY): fails with
     * CIRCULAR_WRONG_CATEGORY otherwise (e.g. I-25: "an active circular of category Wage Revision",
     * SRS l.3164).
     */
    void requireActive(Long circularId, String category);

    /**
     * Links an Active circular to a record, in the caller's transaction (audited).
     *
     * @param entity the linked table, e.g. {@code operation_master}
     * @param key the linked row's key
     * @param screen the screen the link is made on, e.g. M-27
     */
    void link(
            String actor, Long circularId, LinkType type, String entity, String key, String screen);

    /**
     * Wave D review 6: cancels the active links of a record that did not take effect (its change
     * was rejected or returned), in the caller's transaction and audited; never deleted.
     *
     * @return how many links were cancelled
     */
    int cancelLinks(String actor, String entity, String key, String screen, String reason);

    /**
     * SRS-G-41 (M-27 column Circular): the circular numbers of {@code ids}, by id, for a list that
     * shows the number of each row's governing circular; an unknown id is simply absent.
     */
    Map<Long, String> numbers(Collection<Long> ids);
}
