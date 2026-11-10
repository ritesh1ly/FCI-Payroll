package in.gov.fci.payroll.platform.notification;

import java.util.Objects;

/**
 * One file attached to a member e-mail (OD-148, PENDING MAIL-1): the member's own payslip or Form
 * 16 PDF. The content is never logged nor stored with the e-mail; {@link #toString()} names the
 * file and its size only (R-PII).
 *
 * @param fileName the file name the member sees, printable ASCII
 * @param contentType the media type, e.g. {@code application/pdf}
 * @param content the bytes
 */
public record MailAttachment(String fileName, String contentType, byte[] content) {

    public static final String PDF = "application/pdf";

    public MailAttachment {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(content, "content");
        content = content.clone();
    }

    /** A PDF attachment. */
    public static MailAttachment pdf(String fileName, byte[] content) {
        return new MailAttachment(fileName, PDF, content);
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    /** The size in bytes. */
    public int size() {
        return content.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MailAttachment a
                && fileName.equals(a.fileName)
                && contentType.equals(a.contentType)
                && java.util.Arrays.equals(content, a.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileName, contentType, java.util.Arrays.hashCode(content));
    }

    @Override
    public String toString() {
        return "MailAttachment[" + fileName + ", " + contentType + ", " + content.length + " B]";
    }
}
