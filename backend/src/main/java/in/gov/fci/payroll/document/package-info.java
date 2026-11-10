/**
 * Document module: order copies, sanction letters, certificates and uploaded source files attached
 * to a transaction of any module (target DDL v4.0 Section 25; SRS-G-16, SRS-G-30, SRS-G-31,
 * SRS-DM-13; KB pattern {@code 02-cross-cutting/order-control-attachments.md}).
 *
 * <p>The module owns metadata ({@code document_attachment}), the bytes (behind the {@code
 * DocumentStorage} port; today the in-database ORACLE_SECUREFILE store, decision D-1) and the
 * access log ({@code document_access_log}). It never decides who may see an attachment: the module
 * that owns the transaction authorises the caller on its own record first and then asks for the
 * attachment of that record ({@code DocumentStore}), so the attachment can never be read through
 * another record's id.
 */
@ApplicationModule(
        displayName = "Document",
        allowedDependencies = {"platform", "audit::api"})
package in.gov.fci.payroll.document;

import org.springframework.modulith.ApplicationModule;
