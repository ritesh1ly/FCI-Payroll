package in.gov.fci.payroll.document.business;

import in.gov.fci.payroll.document.business.model.DocumentType;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Pure rules of the order control (SRS-G-30, SRS-G-31, SRS-DM-13; KB order-control OC-3, OC-5). No
 * framework and no I/O, so every rule is unit-tested directly.
 */
public final class AttachmentRules {

    /** The accepted file types of SRS-G-30 ("attach PDF, JPG or PNG files"). */
    public static final List<String> ACCEPTED_TYPES =
            List.of("application/pdf", "image/jpeg", "image/png");

    private static final int MAX_FILE_NAME = 255;

    private AttachmentRules() {}

    /**
     * The content type recognised from the first bytes (the "magic number"), never from the file
     * name or the client's Content-Type header, which a caller controls: a script renamed {@code
     * order.pdf} is refused.
     *
     * <ul>
     *   <li>PDF: {@code %PDF-}
     *   <li>PNG: {@code 89 50 4E 47 0D 0A 1A 0A}
     *   <li>JPEG: {@code FF D8 FF}
     * </ul>
     */
    public static Optional<String> detectContentType(byte[] content) {
        if (content == null) {
            return Optional.empty();
        }
        if (startsWith(content, 0x25, 0x50, 0x44, 0x46, 0x2D)) {
            return Optional.of("application/pdf");
        }
        if (startsWith(content, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of("image/png");
        }
        if (startsWith(content, 0xFF, 0xD8, 0xFF)) {
            return Optional.of("image/jpeg");
        }
        return Optional.empty();
    }

    /**
     * Checks size and type: non-empty, at most {@code maxBytes}, and one of the accepted types.
     *
     * @return the detected content type
     */
    public static String requireAcceptedFile(byte[] content, long maxBytes) {
        if (content == null || content.length == 0) {
            throw new FciException(DocumentErrorCode.EMPTY_FILE);
        }
        if (content.length > maxBytes) {
            // The message names the limit in whole MB; the limit itself is a setting (CF-13).
            throw new FciException(
                    DocumentErrorCode.FILE_SIZE,
                    new Object[] {maxBytes / (1024 * 1024)},
                    List.of(),
                    null);
        }
        return detectContentType(content)
                .orElseThrow(() -> new FciException(DocumentErrorCode.FILE_TYPE));
    }

    /**
     * Checks a system-captured source file (an X-07 HRMS export): not empty, within the size limit,
     * and plain text - printable US-ASCII with line breaks and tabs only, no NUL or other control
     * bytes (the database character set is US7ASCII, R-ASCII). Such a file is never an order, so
     * the order types (PDF / JPG / PNG) do not apply to it.
     *
     * @return {@code text/csv}
     */
    public static String requireTextSourceFile(byte[] content, long maxBytes) {
        if (content == null || content.length == 0) {
            throw new FciException(DocumentErrorCode.EMPTY_FILE);
        }
        if (content.length > maxBytes) {
            throw new FciException(
                    DocumentErrorCode.FILE_SIZE,
                    new Object[] {maxBytes / (1024 * 1024)},
                    List.of(),
                    null);
        }
        for (byte b : content) {
            int c = b & 0xFF;
            boolean text =
                    (c >= 0x20 && c < 0x7F) || c == 0x0A || c == 0x0D || c == 0x09; // LF, CR, TAB
            if (!text) {
                throw new FciException(DocumentErrorCode.FILE_TYPE);
            }
        }
        return "text/csv";
    }

    /** A transaction carries at most {@code maxFiles} active files (CF-13 setting). */
    public static void requireRoomForAnother(int activeFiles, int maxFiles) {
        if (activeFiles >= maxFiles) {
            throw new FciException(
                    DocumentErrorCode.FILE_COUNT, new Object[] {maxFiles}, List.of(), null);
        }
    }

    /**
     * The order particulars of SRS-G-30 / SRS-DM-13 for a typed document (an order): the order date
     * and the issuing authority are always required, the date may not lie in the future, and the
     * reference number is required where the type says so (Sanction Order, Circular; OC-3). A
     * system-captured file without a type (an X-07 source file) carries no particulars.
     */
    public static void requireOrderParticulars(
            DocumentType type,
            String referenceNumber,
            LocalDate orderDate,
            String issuingAuthority,
            LocalDate today) {
        if (type == null) {
            return;
        }
        if (orderDate == null || orderDate.isAfter(today) || blank(issuingAuthority)) {
            throw new FciException(DocumentErrorCode.ORDER_PARTICULARS);
        }
        if (type.requiresReferenceNumber() && blank(referenceNumber)) {
            throw new FciException(DocumentErrorCode.REFERENCE_REQUIRED);
        }
    }

    /**
     * A file name safe to store and to send back in a Content-Disposition header: the last path
     * segment only (no {@code ../}), printable ASCII only (the database character set is US7ASCII,
     * R-ASCII), no quotes or control characters, at most 255 characters. An empty result becomes
     * {@code document} with the extension of the detected type.
     */
    public static String safeFileName(String original, String contentType) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        StringBuilder out = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            // Letters, digits and a few separators; everything else (incl. non-ASCII) becomes '_'.
            out.append(
                    (c < 128
                                    && (Character.isLetterOrDigit(c)
                                            || c == '.'
                                            || c == '-'
                                            || c == '_'
                                            || c == ' '))
                            ? c
                            : '_');
        }
        String safe = out.toString().strip();
        while (safe.startsWith(".")) {
            safe = safe.substring(1); // no hidden files, no "..", no leading-dot tricks
        }
        if (safe.isEmpty() || safe.chars().allMatch(ch -> ch == '_')) {
            safe = "document" + extension(contentType);
        }
        return safe.substring(Math.max(0, safe.length() - MAX_FILE_NAME));
    }

    /** Lower-case hex SHA-256 of the bytes ({@code document_attachment.checksum_sha256}). */
    public static String sha256(byte[] content) {
        return SecureTokens.sha256Hex(content);
    }

    /** Printable ASCII only (R-ASCII: the database character set is US7ASCII); null stays null. */
    public static String ascii(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(stripped)
                || stripped.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
            throw new FciException(DocumentErrorCode.ORDER_PARTICULARS);
        }
        return stripped.substring(0, Math.min(maxLength, stripped.length()));
    }

    private static String extension(String contentType) {
        return switch (contentType == null ? "" : contentType) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            default -> "";
        };
    }

    private static boolean startsWith(byte[] content, int... prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((content[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
