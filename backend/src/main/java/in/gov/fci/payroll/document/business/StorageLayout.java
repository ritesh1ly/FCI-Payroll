package in.gov.fci.payroll.document.business;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Where a stored file goes inside its store: the "folder" of {@code
 * document_attachment.storage_object_key} (DDL S25), built from two deployment settings, {@code
 * fci.documents.storage-key-prefix} and {@code fci.documents.storage-key-layout}. The HLD
 * (deployment, "report output storage") names OCI Object Storage for generated report output, and
 * an object store has no folders, only key prefixes, so the folder is this key: one setting moves
 * every Form 16, return file and payslip to another prefix without a code change. The in-database
 * store (decision D-1) records the same key, so moving the bytes to object storage later keeps
 * every key.
 *
 * <p>Layout tokens: {@code {prefix}}, {@code {entity}} (the owning record type, e.g. {@code
 * statutory_return}), {@code {yyyy}} and {@code {mm}} (the month stored, India time) and {@code
 * {uuid}} (required: the DDL key is unique per provider and bucket). Pure rules, no I/O.
 */
public final class StorageLayout {

    /** The DDL column length of storage_object_key. */
    public static final int MAX_KEY = 500;

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    // Lower-case ASCII segments split by '/': no '..', no empty segment, no leading '/'.
    private static final Pattern SEGMENT = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    private static final Pattern TOKEN =
            Pattern.compile("[{](?!prefix}|entity}|yyyy}|mm}|uuid})[^}]*}");

    private final String prefix;
    private final String layout;

    private StorageLayout(String prefix, String layout) {
        this.prefix = prefix;
        this.layout = layout;
    }

    /**
     * Checks the two settings once, at start-up: a prefix of lower-case ASCII segments (at most 100
     * characters), a layout of known tokens that includes {@code {uuid}}.
     *
     * @throws IllegalStateException naming the setting at fault
     */
    public static StorageLayout of(String prefix, String layout) {
        if (!validPrefix(prefix)) {
            throw new IllegalStateException(
                    "fci.documents.storage-key-prefix must be lower-case ASCII segments split by"
                            + " '/', at most 100 characters: "
                            + prefix);
        }
        if (layout == null || !layout.contains("{uuid}") || TOKEN.matcher(layout).find()) {
            throw new IllegalStateException(
                    "fci.documents.storage-key-layout must include {uuid} and use only {prefix},"
                            + " {entity}, {yyyy}, {mm}, {uuid}: "
                            + layout);
        }
        if (layout.startsWith("/") || layout.contains("//") || layout.contains("..")) {
            throw new IllegalStateException(
                    "fci.documents.storage-key-layout must not start with '/' or hold '//' or"
                            + " '..': "
                            + layout);
        }
        return new StorageLayout(prefix, layout);
    }

    /** Segment by segment, so no backtracking regex sees the whole value (SpotBugs REDOS). */
    private static boolean validPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty() || prefix.length() > 100) {
            return false;
        }
        for (String segment : prefix.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches()) {
                return false;
            }
        }
        return true;
    }

    /** The object key of a file of {@code entityName} stored at {@code at}. */
    public String keyOf(String entityName, Instant at, UUID id) {
        ZonedDateTime when = at.atZone(INDIA);
        String entity = entityName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        String key =
                layout.replace("{prefix}", prefix)
                        .replace("{entity}", entity)
                        .replace("{yyyy}", String.format(Locale.ROOT, "%04d", when.getYear()))
                        .replace("{mm}", String.format(Locale.ROOT, "%02d", when.getMonthValue()))
                        .replace("{uuid}", id.toString());
        if (key.length() > MAX_KEY) {
            throw new IllegalStateException("storage object key longer than " + MAX_KEY);
        }
        return key;
    }
}
