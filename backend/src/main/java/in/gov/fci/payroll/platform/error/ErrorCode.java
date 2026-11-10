package in.gov.fci.payroll.platform.error;

import org.springframework.http.HttpStatus;

/**
 * A catalogued error. Each module defines an enum implementing this interface; the {@code code} is
 * stable and documented in {@code docs/api/errors.md}. Messages are resolved from the {@code
 * error.<code>.title} and {@code error.<code>.detail} keys, with English defaults.
 */
public interface ErrorCode {

    /** Stable machine-readable code, e.g. {@code COM-400-VALIDATION}. */
    String code();

    HttpStatus status();

    /** Path appended to the type base URI, e.g. {@code request/validation-failed}. */
    String type();

    String defaultTitle();

    /** User-safe explanation. Must never contain request input. */
    String defaultDetail();

    default String titleKey() {
        return "error." + code() + ".title";
    }

    default String detailKey() {
        return "error." + code() + ".detail";
    }

    /** SRS-EH-1: the severity held against the message (by default from its HTTP status). */
    default MessageSeverity severity() {
        return MessageSeverity.ofStatus(status().value());
    }

    /**
     * SRS-EH-2: the UI key of the message's corrective action, {@code error.<code>.action}. A code
     * without its own action text falls back to the action of its HTTP status, {@code
     * error.action.<status>}, in the user interface.
     */
    default String actionKey() {
        return "error." + code() + ".action";
    }
}
