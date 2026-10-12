package com.example.ordermgt;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the same Flyway script against a real MySQL -- the target database -- and pins down
 * the two behaviours the whole design leans on:
 *
 *   1. the migration is portable (it bootstraps MySQL, not just H2),
 *   2. the unique constraint arbitrates an idempotency-key race, and SELECT ... FOR UPDATE
 *      really does serialise two confirmations.
 *
 * Skipped automatically when Docker is unavailable, so it can live in the normal suite.
 */
@Testcontainers(disabledWithoutDocker = true)
class MySqlMigrationAndLockingTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ordermgt")
            .withUsername("ordermgt")
            .withPassword("ordermgt");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void thePortableScriptBootstrapsMySqlAndSeedsTheCatalog() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM catalog_items WHERE active = TRUE")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(5);
            }
            // The tables the confirmation flow needs must exist too.
            for (String table : new String[]{"conversations", "messages", "pending_actions",
                    "idempotency_records"}) {
                try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertThat(rows.next()).isTrue();
                }
            }
        }
    }

    @Test
    void theUniqueKeyIsWhatArbitratesARaceOnTheSameIdempotencyKey() throws Exception {
        insertIdempotencyClaim("race-key");

        // The second claim cannot win: it is the constraint, not a read-then-write check.
        assertThatThrownBy(() -> insertIdempotencyClaim("race-key"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void selectForUpdateSerialisesTwoConfirmationsOfOneAction() throws Exception {
        long id = insertPendingAction();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> secondReaderSaw = new AtomicReference<>();

        Thread first = new Thread(() -> {
            try (Connection connection = open()) {
                connection.setAutoCommit(false);
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT status FROM pending_actions WHERE id = ? FOR UPDATE")) {
                    lock.setLong(1, id);
                    try (ResultSet rows = lock.executeQuery()) {
                        rows.next();
                    }
                }
                locked.countDown();
                release.await(10, TimeUnit.SECONDS);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE pending_actions SET status = 'CONFIRMED' WHERE id = ?")) {
                    update.setLong(1, id);
                    update.executeUpdate();
                }
                connection.commit();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        first.start();

        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Thread second = new Thread(() -> {
            try (Connection connection = open();
                 PreparedStatement lock = connection.prepareStatement(
                         "SELECT status FROM pending_actions WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, id);
                try (ResultSet rows = lock.executeQuery()) {
                    rows.next();
                    // This only runs after the first transaction committed, so it must
                    // already see CONFIRMED -- the second confirmation can never execute.
                    secondReaderSaw.set(rows.getString(1));
                }
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        second.start();

        // Give the second thread time to block on the row lock, then let the first finish.
        Thread.sleep(1500);
        release.countDown();
        first.join(10_000);
        second.join(10_000);

        assertThat(secondReaderSaw.get()).isEqualTo("CONFIRMED");
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private void insertIdempotencyClaim(String key) throws SQLException {
        try (Connection connection = open();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO idempotency_records (customer_id, operation, idempotency_key, "
                             + "request_hash, status, created_at, expires_at) "
                             + "VALUES (?, 'CREATE_ORDER', ?, 'hash', 'IN_PROGRESS', "
                             + "CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))")) {
            insert.setLong(1, 1001L);
            insert.setString(2, key);
            insert.executeUpdate();
        }
    }

    private long insertPendingAction() throws SQLException {
        try (Connection connection = open();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO pending_actions (token, customer_id, conversation_id, tool_name, "
                             + "arguments_json, summary, status, expires_at, created_at) "
                             + "VALUES (?, 1001, 1, 'createOrder', '{}', 's', 'PENDING', "
                             + "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 15 MINUTE), CURRENT_TIMESTAMP(3))",
                     Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, "token-" + System.nanoTime());
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }
}
