package com.callbot.ai.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves that no line of money is lost when the singular columns become a register.
 *
 * <p>The other integration tests migrate an empty schema, so the backfill in V18 never
 * sees a row there and could be silently wrong. This one stops at V17, writes the
 * reservations a live database would hold — paid, refunded, already paid out, penalised,
 * and one whose checkout was only ever opened — then runs V18 over them.
 *
 * <p>It uses its own container: the shared one is migrated to head before any test runs,
 * and there is no going back from that.
 */
class ChargeRegisterMigrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        dataSource = source;
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    private static Flyway flywayUpTo(String version) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(version)
                .load();
    }

    private static void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("Could not run: " + sql, e);
        }
    }

    /** One row of the register, keyed by column name, for the given reservation. */
    private static Map<String, Object> chargeOf(String reservationRef) {
        String sql = """
                SELECT c.* FROM reservation_charges c
                JOIN reservations r ON r.id = c.reservation_id
                WHERE r.notes = '%s'
                """.formatted(reservationRef);
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as("a charge for %s", reservationRef).isTrue();
            Map<String, Object> charge = new LinkedHashMap<>();
            for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                charge.put(rows.getMetaData().getColumnName(column), rows.getObject(column));
            }
            assertThat(rows.next()).as("only one charge for %s", reservationRef).isFalse();
            return charge;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long countCharges() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM reservation_charges")) {
            rows.next();
            return rows.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void everyLineOfMoneySurvivesTheMoveIntoTheRegister() {
        flywayUpTo("17").migrate();

        execute("""
                INSERT INTO organizations (id, name) VALUES
                    ('11111111-1111-1111-1111-111111111111', 'Alloquence Test');
                """);
        execute("""
                INSERT INTO restaurants (id, organization_id, name, phone_number) VALUES
                    ('22222222-2222-2222-2222-222222222222',
                     '11111111-1111-1111-1111-111111111111', 'Chez Migration', '+33987654321');
                """);
        execute("""
                INSERT INTO payouts (id, restaurant_id, amount_cents, currency, reservation_count, status)
                VALUES ('33333333-3333-3333-3333-333333333333',
                        '22222222-2222-2222-2222-222222222222', 8500, 'eur', 1, 'paid');
                """);

        // Paid, waiting for its payout.
        insertReservation("paid", """
                guarantee_mode = 'booking_fee', guarantee_status = 'secured',
                guarantee_amount_cents = 9000, application_fee_cents = 500,
                stripe_session_id = 'cs_paid', stripe_payment_intent_id = 'pi_paid',
                paid_at = now() - interval '3 days',
                payout_eligible_at = now() - interval '1 day'
                """);
        // Paid, refunded: nothing is owed to anyone any more, but the trace remains.
        insertReservation("refunded", """
                guarantee_mode = 'booking_fee', guarantee_status = 'refunded',
                guarantee_amount_cents = 4000, application_fee_cents = 250,
                stripe_session_id = 'cs_refunded', stripe_payment_intent_id = 'pi_refunded',
                paid_at = now() - interval '5 days',
                refunded_at = now() - interval '4 days', refunded_amount_cents = 4000
                """);
        // Already transferred to the restaurateur's bank.
        insertReservation("paid_out", """
                guarantee_mode = 'booking_fee', guarantee_status = 'secured',
                guarantee_amount_cents = 9000, application_fee_cents = 500,
                stripe_payment_intent_id = 'pi_paid_out',
                paid_at = now() - interval '9 days',
                payout_eligible_at = now() - interval '7 days',
                paid_out_at = now() - interval '6 days',
                payout_id = '33333333-3333-3333-3333-333333333333'
                """);
        // A no-show penalty, which owes Alloquence no commission.
        insertReservation("penalty", """
                guarantee_mode = 'no_show', guarantee_status = 'charged',
                guarantee_amount_cents = 10000,
                stripe_penalty_intent_id = 'pi_penalty',
                penalty_charged_at = now() - interval '2 days',
                paid_at = now() - interval '2 days',
                payout_eligible_at = now() - interval '1 day'
                """);
        // A checkout that opened and was never paid: no money, but a live Stripe session.
        insertReservation("opened", """
                guarantee_mode = 'booking_fee', guarantee_status = 'awaiting',
                guarantee_amount_cents = 3000, application_fee_cents = 200,
                stripe_session_id = 'cs_open'
                """);
        // A free reservation: nothing to carry over at all.
        insertReservation("free", "guarantee_mode = 'none', guarantee_status = 'not_required'");

        flywayUpTo("18").migrate();

        // Five money lines in, five out — the free reservation brings none.
        assertThat(countCharges()).isEqualTo(5);

        Map<String, Object> paid = chargeOf("paid");
        assertThat(paid.get("kind")).isEqualTo("booking_fee");
        assertThat(paid.get("status")).isEqualTo("paid");
        assertThat(paid.get("amount_cents")).isEqualTo(9000);
        assertThat(paid.get("application_fee_cents")).isEqualTo(500);
        assertThat(paid.get("stripe_payment_intent_id")).isEqualTo("pi_paid");
        assertThat(paid.get("paid_at")).isNotNull();
        assertThat(paid.get("payout_eligible_at")).isNotNull();
        assertThat(paid.get("paid_out_at")).isNull();

        Map<String, Object> refunded = chargeOf("refunded");
        assertThat(refunded.get("status")).isEqualTo("refunded");
        assertThat(refunded.get("refunded_amount_cents")).isEqualTo(4000);
        assertThat(refunded.get("refunded_at")).isNotNull();

        // A reversed payout must still find the charges it claimed.
        Map<String, Object> paidOut = chargeOf("paid_out");
        assertThat(paidOut.get("payout_id").toString())
                .isEqualTo("33333333-3333-3333-3333-333333333333");
        assertThat(paidOut.get("paid_out_at")).isNotNull();

        Map<String, Object> penalty = chargeOf("penalty");
        assertThat(penalty.get("kind")).isEqualTo("no_show_penalty");
        assertThat(penalty.get("status")).isEqualTo("paid");
        assertThat(penalty.get("amount_cents")).isEqualTo(10000);
        assertThat(penalty.get("application_fee_cents")).isEqualTo(0);
        assertThat(penalty.get("stripe_payment_intent_id")).isEqualTo("pi_penalty");

        // An open checkout is a charge that owes nothing yet, and knows its session.
        Map<String, Object> opened = chargeOf("opened");
        assertThat(opened.get("status")).isEqualTo("pending");
        assertThat(opened.get("stripe_session_id")).isEqualTo("cs_open");
        assertThat(opened.get("paid_at")).isNull();
    }

    private void insertReservation(String reference, String moneyColumns) {
        String assignments = moneyColumns.strip();
        String columns = "";
        String values = "";
        for (String assignment : assignments.split(",")) {
            String[] parts = assignment.split("=", 2);
            columns += ", " + parts[0].strip();
            values += ", " + parts[1].strip();
        }
        execute("""
                INSERT INTO reservations
                    (restaurant_id, starts_at, ends_at, party_size, status, notes%s)
                VALUES
                    ('22222222-2222-2222-2222-222222222222',
                     now() - interval '10 days', now() - interval '10 days' + interval '2 hours',
                     4, 'completed', '%s'%s);
                """.formatted(columns, reference, values));
    }
}
