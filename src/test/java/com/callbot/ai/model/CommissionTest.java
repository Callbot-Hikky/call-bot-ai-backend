package com.callbot.ai.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CommissionTest {

    @Test
    void takesFivePercentPlusFiftyCents() {
        // 15,00 € → 0,75 € + 0,50 €
        assertThat(Commission.on(1500).amountCents()).isEqualTo(125);
    }

    @Test
    void roundsTheHalfCentUpwards() {
        // 5 % de 4,50 € = 22,5 centimes
        assertThat(Commission.on(450).amountCents()).isEqualTo(23 + 50);
    }

    @Test
    void neverExceedsWhatTheDinerPaid() {
        // Otherwise a ten-cent fee would owe more commission than it collected.
        assertThat(Commission.on(10).amountCents()).isEqualTo(10);
    }

    @Test
    void isNothingOnNothing() {
        assertThat(Commission.on(0)).isEqualTo(Commission.none());
    }

    @Test
    void onALargeFeeTheArithmeticStaysExact() {
        // 50 000 € : the percentage overflows an int if computed without widening.
        assertThat(Commission.on(5_000_000).amountCents()).isEqualTo(250_000 + 50);
    }
}
