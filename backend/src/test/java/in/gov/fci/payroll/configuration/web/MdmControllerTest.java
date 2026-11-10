package in.gov.fci.payroll.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.configuration.orchestration.LabelService;
import in.gov.fci.payroll.platform.error.FciException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SRS-G-39 bulk upload: the label file is read in the download's columns, UTF-8 only. */
class MdmControllerTest {

    private static List<LabelService.ProposedLabel> rows(String csv) {
        return MdmController.labelRows(csv.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void SRS_G_39_anUploadedLabelFileIsReadByItsHeaderInAnyColumnOrder() {
        List<LabelService.ProposedLabel> labels =
                rows(
                        "﻿language,label_key,label_type,label_text,effective_from,scope,"
                                + "master_entity,master_code,localisable\r\n"
                                + "HI,DESIG.SARDAR,MASTER_VALUE,सरदार,2026-04-01,GLOBAL,"
                                + "ORG_DESIGNATION,SARDAR,Y\r\n"
                                + ",,,,,,,,\r\n"
                                + "EN,LBL.A,UI_LABEL,\"'=SUM(1), safe\",2026-05-01,,,,N\r\n");
        assertThat(labels).hasSize(2);
        LabelService.ProposedLabel first = labels.get(0);
        assertThat(first.key()).isEqualTo("DESIG.SARDAR");
        assertThat(first.text()).isEqualTo("सरदार");
        assertThat(first.localisable()).isTrue();
        assertThat(first.masterCode()).isEqualTo("SARDAR");
        assertThat(first.effectiveFrom()).isEqualTo(LocalDate.of(2026, 4, 1));
        LabelService.ProposedLabel second = labels.get(1);
        // The spreadsheet guard apostrophe of the download is dropped; the text is kept as text.
        assertThat(second.text()).isEqualTo("=SUM(1), safe");
        assertThat(second.scope()).isNull();
        assertThat(second.localisable()).isFalse();
        assertThat(second.reason()).isNull();
    }

    @Test
    void SRS_G_39_aFileWithoutTheRequiredColumnsOrWithABadDateOrEncodingIsRefused() {
        for (String bad :
                List.of(
                        "",
                        // Each required column missing in turn (the download's names).
                        "label_type,language,label_text,effective_from\r\n"
                                + "UI_LABEL,HI,t,2026-04-01\r\n",
                        "label_key,language,label_text,effective_from\r\nK,HI,t,2026-04-01\r\n",
                        "label_key,label_type,label_text,effective_from\r\n"
                                + "K,UI_LABEL,t,2026-04-01\r\n",
                        "label_key,label_type,language,effective_from\r\n"
                                + "K,UI_LABEL,HI,2026-04-01\r\n",
                        "label_key,label_type,language,label_text\r\nK,UI_LABEL,HI,t\r\n",
                        "label_key,label_type,language,label_text,effective_from\r\n"
                                + "K,UI_LABEL,HI,t,01-04-2026\r\n",
                        "label_key,label_type,language,label_text,effective_from\r\n"
                                + "K,UI_LABEL,HI,t\r\n")) {
            assertThatThrownBy(() -> rows(bad)).isInstanceOf(FciException.class);
        }
        byte[] latin1 =
                "label_key,label_type,language,label_text,effective_from\r\nK,UI_LABEL,HI,café,2026-04-01\r\n"
                        .getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> MdmController.labelRows(latin1)).isInstanceOf(FciException.class);
    }
}
