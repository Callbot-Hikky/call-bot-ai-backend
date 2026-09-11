package com.callbot.ai.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhoneNumbersTest {

    @Test
    void normalize_dropsSpacesDotsParenthesesAndDashes_keepsPlus() {
        assertThat(PhoneNumbers.normalize("06 12.34-56 78")).isEqualTo("0612345678");
        assertThat(PhoneNumbers.normalize("+33 (0)6 12 34 56 78")).isEqualTo("+330612345678");
        assertThat(PhoneNumbers.normalize("0612345678")).isEqualTo("0612345678");
        assertThat(PhoneNumbers.normalize(null)).isNull();
    }
}
