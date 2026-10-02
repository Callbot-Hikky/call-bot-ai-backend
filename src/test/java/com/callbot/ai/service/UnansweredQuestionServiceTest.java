package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UnansweredQuestionServiceTest {

    @Test
    void normalize_ignoresCaseAccentsAndPunctuation() {
        assertThat(UnansweredQuestionService.normalize("Vous avez un menu enfant ?"))
                .isEqualTo(UnansweredQuestionService.normalize("  vous avez un MENU enfant"))
                .isEqualTo("vous avez un menu enfant");
        assertThat(UnansweredQuestionService.normalize("Êtes-vous près du métro ?"))
                .isEqualTo("etes vous pres du metro");
    }

    @Test
    void normalize_ofPunctuationOnly_isEmpty() {
        assertThat(UnansweredQuestionService.normalize(" ?! ")).isEmpty();
        assertThat(UnansweredQuestionService.normalize(null)).isEmpty();
    }

    @Test
    void normalize_isCappedToTheColumnLength() {
        assertThat(UnansweredQuestionService.normalize("a".repeat(500))).hasSize(200);
    }
}
