package in.gov.fci.payroll.integration.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * SRS-INT-6, R-PII, R-INT: what the HRMS exchange log carries - bodies on unless switched off, the
 * body length kept within 100 .. 8000 with 1500 as the default.
 */
class HrmsLogPropertiesTest {

    @Test
    void SRS_INT_6_bodiesAreLoggedUnlessSwitchedOff() {
        assertThat(new HrmsLogProperties(null, null).logBodies()).isTrue();
        assertThat(new HrmsLogProperties(true, null).logBodies()).isTrue();
        assertThat(new HrmsLogProperties(false, null).logBodies()).isFalse();
    }

    @Test
    void SRS_INT_6_theBodyLengthDefaultsTo1500() {
        assertThat(new HrmsLogProperties(null, null).maxBody()).isEqualTo(1500);
    }

    @Test
    void SRS_INT_6_theBodyLengthIsKeptWithin100And8000() {
        assertThat(new HrmsLogProperties(null, 99).maxBody()).isEqualTo(100);
        assertThat(new HrmsLogProperties(null, 100).maxBody()).isEqualTo(100);
        assertThat(new HrmsLogProperties(null, 101).maxBody()).isEqualTo(101);
        assertThat(new HrmsLogProperties(null, 7999).maxBody()).isEqualTo(7999);
        assertThat(new HrmsLogProperties(null, 8000).maxBody()).isEqualTo(8000);
        assertThat(new HrmsLogProperties(null, 8001).maxBody()).isEqualTo(8000);
        assertThat(new HrmsLogProperties(null, 0).maxBody()).isEqualTo(100);
        assertThat(new HrmsLogProperties(null, -50).maxBody()).isEqualTo(100);
        assertThat(new HrmsLogProperties(null, Integer.MAX_VALUE).maxBody()).isEqualTo(8000);
    }
}
