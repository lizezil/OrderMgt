package com.example.ordermgt.support;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Rebuilds the schema for every test class that imports it. The L4 suites run against a resident
 * MySQL, so rows would otherwise accumulate: their assertions count exact rows (a customer has
 * exactly one order, one card) and reuse fixed idempotency keys, which a leftover row from an
 * earlier class would break. clean() drops everything, migrate() replays V1/V2 -- each class starts
 * from the seed. Imported by every integration test class instead of being copy-pasted.
 */
@Configuration
public class CleanSchemaConfiguration {

    @Bean
    FlywayMigrationStrategy cleanMigrate() {
        return flyway -> {
            flyway.clean();
            flyway.migrate();
        };
    }
}
