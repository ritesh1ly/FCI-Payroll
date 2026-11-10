package in.gov.fci.payroll.platform.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RFC 4180 reading of an uploaded or exported CSV (SRS-G-13): quoted fields with commas, line
 * breaks and doubled quotes, a quote inside an unquoted field kept as written, CRLF or LF ends.
 */
class CsvParseTest {

    @Test
    void SRS_G_13_aQuotedFieldMayEndTheFileAndHoldCommasAndDoubledQuotes() {
        assertThat(Csv.parse("a,\"b, \"\"c\"\"\"")).containsExactly(List.of("a", "b, \"c\""));
        assertThat(Csv.parse("\"x\ny\",z\r\n")).containsExactly(List.of("x\ny", "z"));
    }

    @Test
    void SRS_G_13_aQuoteInsideAnUnquotedFieldIsKeptAsWritten() {
        assertThat(Csv.parse("5\" pipe,ok\n")).containsExactly(List.of("5\" pipe", "ok"));
    }

    @Test
    void SRS_G_13_lfAndCrlfEndsAndAnEmptyLastFieldAreRead() {
        assertThat(Csv.parse("a,b\nc,\r\nd"))
                .containsExactly(List.of("a", "b"), List.of("c", ""), List.of("d"));
        assertThat(Csv.parse("")).isEmpty();
        // A lone CR ends a line too.
        assertThat(Csv.parse("a\rb")).containsExactly(List.of("a"), List.of("b"));
    }

    @Test
    void SRS_G_13_whatTheWriterWritesIsReadBack() {
        String text = Csv.line("Name, first", "=SUM(A1)", null, "say \"hi\"");
        assertThat(Csv.parse(text))
                .containsExactly(List.of("Name, first", "'=SUM(A1)", "", "say \"hi\""));
    }
}
