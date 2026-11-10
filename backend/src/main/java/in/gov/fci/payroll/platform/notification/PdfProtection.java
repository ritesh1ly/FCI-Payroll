package in.gov.fci.payroll.platform.notification;

import in.gov.fci.payroll.platform.security.SecureTokens;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

/**
 * Password protection of an e-mailed PDF (OD-148, PENDING MAIL-1): off until FCI decides; when on,
 * the file opens only with the member's password (AES-256), and its owner password is random and
 * never kept, so nobody can lift the protection with a known password. The password itself is
 * neither logged nor stored.
 */
public final class PdfProtection {

    private static final int AES_256 = 256;

    private PdfProtection() {}

    /** The PDF encrypted with {@code userPassword}; printing stays allowed, editing does not. */
    public static byte[] protect(byte[] pdf, String userPassword) {
        try (PDDocument document = Loader.loadPDF(pdf);
                ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + 1024)) {
            AccessPermission permission = new AccessPermission();
            permission.setCanModify(false);
            permission.setCanModifyAnnotations(false);
            permission.setCanFillInForm(false);
            permission.setCanAssembleDocument(false);
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy(
                            SecureTokens.randomToken(32), userPassword, permission);
            policy.setEncryptionKeyLength(AES_256);
            document.protect(policy);
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot protect the PDF", e);
        }
    }
}
