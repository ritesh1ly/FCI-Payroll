package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The issued copy of a Form 16 certificate (OD-153, owner 2026-11-09 "Freeze Part B at release";
 * SRS-7-9, BR-TAX-010; DDL v4.0 {@code form16.report_output_id}, {@code report_output}): at release
 * the certificate as one PDF (Part A, then Part B) is rendered once, stored in the document store
 * and logged as a {@code report_output} row of the certificate's own definition version ({@code
 * form16.definition_version_id}), with its SHA-256; {@code form16.report_output_id} names it. Every
 * later print of the released certificate serves those bytes, so a statutory document already
 * issued reprints exactly as issued, whatever changes in payroll later. A reissue released again
 * freezes a new copy and marks the previous output SUPERSEDED; its file stays (R-DEL).
 */
@Component
public class Form16IssuedCopies {

    /** The owner of an issued copy in the document store (the certificate, by its id). */
    public static final String ENTITY = "form16_issued";

    /** {@code report_output.report_key} of an issued certificate. */
    static final String KEY = "FORM16";

    private final JdbcTemplate jdbc;
    private final DocumentStore store;
    private final Clock clock;

    public Form16IssuedCopies(JdbcTemplate jdbc, DocumentStore store, Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.clock = clock;
    }

    /**
     * Freezes the released certificate's PDF: stores the bytes, logs the output RELEASED by the
     * releasing Checker and links it to the certificate; a previous issued copy is SUPERSEDED.
     *
     * @return the new {@code report_output_id}
     */
    public long freeze(
            long form16Id,
            long definitionVersionId,
            String finYear,
            Long orgUnitId,
            String releasedBy,
            Form16Pdf.Merged pdf) {
        DocumentStore.AttachmentView file =
                store.storeGenerated(
                        releasedBy,
                        ENTITY,
                        String.valueOf(form16Id),
                        "form16-" + finYear + ".pdf",
                        pdf.pdf());
        Timestamp now = Timestamp.from(clock.instant());
        String parameters =
                "{\"form16Id\":"
                        + form16Id
                        + ",\"finYear\":\""
                        + finYear
                        + "\",\"pages\":"
                        + pdf.pages()
                        + ",\"partAIncluded\":"
                        + pdf.partAIncluded()
                        + "}";
        Long previous =
                jdbc.queryForObject(
                        "SELECT report_output_id FROM form16 WHERE form16_id = ?",
                        Long.class,
                        form16Id);
        long output =
                GeneratedKeys.insert(
                        jdbc,
                        "INSERT INTO report_output (report_version_id, report_key, fin_year,"
                                + " org_unit_id, parameters_supplied, parameter_hash, requested_by,"
                                + " generated_at, run_type, period_was_locked, is_provisional,"
                                + " output_status, attachment_id, checksum_sha256, released_by,"
                                + " released_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTUAL', 'Y',"
                                + " 'N', 'RELEASED', ?, ?, ?, ?)",
                        "report_output_id",
                        definitionVersionId,
                        KEY,
                        finYear,
                        orgUnitId,
                        parameters,
                        sha256(parameters.getBytes(StandardCharsets.UTF_8)),
                        releasedBy,
                        now,
                        file.id(),
                        sha256(pdf.pdf()),
                        releasedBy,
                        now);
        if (previous != null) {
            jdbc.update(
                    "UPDATE report_output SET output_status = 'SUPERSEDED' WHERE"
                            + " report_output_id = ?",
                    previous);
        }
        jdbc.update("UPDATE form16 SET report_output_id = ? WHERE form16_id = ?", output, form16Id);
        return output;
    }

    /**
     * The issued copy of a certificate, when one was frozen: its bytes, checked against the stored
     * SHA-256 (a copy that no longer matches is refused, never served). The access is logged under
     * {@code actor}.
     */
    public Optional<Form16Pdf.Merged> issued(long form16Id, String actor, String sourceIp) {
        // parameters_supplied is a CLOB: read through getString (Oracle hands a Clob otherwise).
        List<Stored> rows =
                jdbc.query(
                        "SELECT o.attachment_id, o.checksum_sha256, o.parameters_supplied FROM"
                                + " form16 f JOIN report_output o ON o.report_output_id ="
                                + " f.report_output_id WHERE f.form16_id = ? AND o.attachment_id"
                                + " IS NOT NULL",
                        (rs, i) -> new Stored(rs.getLong(1), rs.getString(2), rs.getString(3)),
                        form16Id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Stored copy = rows.get(0);
        byte[] bytes =
                store.read(actor, sourceIp, ENTITY, String.valueOf(form16Id), copy.attachmentId())
                        .bytes();
        if (!sha256(bytes).equals(copy.checksum())) {
            throw new IllegalStateException(
                    "FORM16_ISSUED_COPY_CHANGED: the issued copy of certificate "
                            + form16Id
                            + " no longer matches its checksum");
        }
        String parameters = copy.parameters() == null ? "" : copy.parameters();
        Matcher pages = PAGES.matcher(parameters);
        return Optional.of(
                new Form16Pdf.Merged(
                        bytes,
                        pages.find() ? Integer.parseInt(pages.group(1)) : 0,
                        parameters.contains("\"partAIncluded\":true")));
    }

    private static final Pattern PAGES = Pattern.compile("\"pages\":(\\d+)");

    private record Stored(long attachmentId, String checksum, String parameters) {}

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
