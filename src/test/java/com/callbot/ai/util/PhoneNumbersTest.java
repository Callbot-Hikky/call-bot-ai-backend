package com.callbot.ai.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhoneNumbersTest {

    @Test
    void normalize_frenchNumbersShareOneInternationalForm() {
        assertThat(PhoneNumbers.normalize("06 12.34-56 78")).isEqualTo("+33612345678");
        assertThat(PhoneNumbers.normalize("0612345678")).isEqualTo("+33612345678");
        assertThat(PhoneNumbers.normalize("+33 (0)6 12 34 56 78")).isEqualTo("+33612345678");
        assertThat(PhoneNumbers.normalize("+33 6 12 34 56 78")).isEqualTo("+33612345678");
        assertThat(PhoneNumbers.normalize("0033612345678")).isEqualTo("+33612345678");
        assertThat(PhoneNumbers.normalize(null)).isNull();
    }

    @Test
    void normalize_keepsForeignAndShortNumbersAsTheyAre() {
        assertThat(PhoneNumbers.normalize("+41 79 123 45 67")).isEqualTo("+41791234567");
        assertThat(PhoneNumbers.normalize("3631")).isEqualTo("3631");
    }
}
