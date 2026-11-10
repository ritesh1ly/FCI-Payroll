package in.gov.fci.payroll.platform.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/**
 * OD-149 ("bulk download will be zipped, single month PDF"): entries in the caller's order, every
 * entry stamped with the time from the locked data, safe names, a manifest only when a document was
 * left out, and the same content giving the same bytes.
 */
class ZipBundleTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 1, 0, 0);

    private static byte[] zip(String... names) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipBundle z = new ZipBundle(out, AT)) {
            for (String n : names) {
                z.add(n, n.getBytes(StandardCharsets.US_ASCII));
            }
        }
        return out.toByteArray();
    }

    private static List<ZipEntry> entries(byte[] zip) throws IOException {
        List<ZipEntry> out = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                out.add(e);
            }
        }
        return out;
    }

    @Test
    void OD_149_entriesKeepTheirOrderAndTheTimeOfTheLockedData() throws IOException {
        List<ZipEntry> e = entries(zip("payslip-2-202609.pdf", "payslip-1-202609.pdf"));
        assertThat(e)
                .extracting(ZipEntry::getName)
                .containsExactly("payslip-2-202609.pdf", "payslip-1-202609.pdf");
        assertThat(e).allSatisfy(x -> assertThat(x.getTimeLocal()).isEqualTo(AT));
    }

    @Test
    void SRS_O01_l3392_theSameContentGivesTheSameBytes() throws IOException {
        assertThat(zip("a.pdf", "b.pdf")).isEqualTo(zip("a.pdf", "b.pdf"));
        assertThat(zip("a.pdf", "b.pdf")).isNotEqualTo(zip("b.pdf", "a.pdf"));
    }

    @Test
    void OD_149_entryNamesAreSafeAsciiWithoutFoldersOrTraversal() {
        assertThat(ZipBundle.entryName("../../etc/passwd")).isEqualTo("----etc-passwd");
        assertThat(ZipBundle.entryName("payslip-DL/04 12.pdf")).isEqualTo("payslip-DL-04-12.pdf");
        assertThat(ZipBundle.entryName(".hidden.pdf")).isEqualTo("hidden.pdf");
        assertThat(ZipBundle.entryName("रवि.pdf")).isEqualTo("---.pdf");
        assertThat(ZipBundle.entryName(null)).isEqualTo("document");
        assertThat(ZipBundle.entryName("")).isEqualTo("document");
        assertThat(ZipBundle.entryName("x".repeat(200) + ".pdf")).hasSize(120).endsWith(".pdf");
    }

    @Test
    void OD_149_aDuplicateEntryIsAProgrammingError() throws IOException {
        try (ZipBundle z = new ZipBundle(new ByteArrayOutputStream(), AT)) {
            z.add("a.pdf", new byte[] {1});
            assertThatThrownBy(() -> z.add("a.pdf", new byte[] {2}))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void OD_149_aManifestListsWhatWasLeftOutAndOnlyThen() throws IOException {
        assertThat(entries(zip("a.pdf"))).extracting(ZipEntry::getName).containsExactly("a.pdf");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipBundle z = new ZipBundle(out, AT)) {
            z.add("a.pdf", new byte[] {1});
            z.leftOut("b.pdf", "IllegalStateException");
            assertThat(z.added()).isEqualTo(1);
            assertThat(z.leftOut()).isEqualTo(1);
        }
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            assertThat(in.getNextEntry().getName()).isEqualTo("a.pdf");
            ZipEntry m = in.getNextEntry();
            assertThat(m.getName()).isEqualTo(ZipBundle.MANIFEST);
            assertThat(m.getTimeLocal()).isEqualTo(AT);
            assertThat(new String(in.readAllBytes(), StandardCharsets.US_ASCII))
                    .isEqualTo(
                            "Documents not produced (1); see the job log.\n"
                                    + "b.pdf\tIllegalStateException\n");
        }
    }

    @Test
    void OD_149_anEmptyBundleIsAValidZip() throws IOException {
        assertThat(entries(zip())).isEmpty();
    }
}
