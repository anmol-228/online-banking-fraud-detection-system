package com.sepro.obfds.migrate;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A read-only startup check that the database schema is at the version this build expects.
 *
 * <p>This is <b>not</b> Flyway running inside the application (Flyway is deliberately disabled
 * there). It only reads the migrator's history table and compares the newest successfully applied
 * version with the newest {@code V<n>__*.sql} bundled in this build. If the database is behind, the
 * service refuses to start with the exact command to run, instead of failing later on a missing
 * column. Hibernate's {@code ddl-auto=validate} separately checks that tables and columns match the
 * entities.</p>
 *
 * <p>Active only on MySQL (the H2 development and test databases are created from the entities).</p>
 */
@Component
@Profile("mysql")
@Order(0)
public class SchemaVersionCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaVersionCheck.class);
    private static final Pattern MIGRATION = Pattern.compile("V(\\d+)__.*\\.sql");

    private final JdbcTemplate jdbc;

    public SchemaVersionCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        int expected = newestBundledVersion();
        Integer actual;
        try {
            actual = jdbc.queryForObject(
                    "select max(cast(version as unsigned)) from flyway_schema_history where success = 1 and version is not null",
                    Integer.class);
        } catch (org.springframework.dao.DataAccessException ex) {
            throw new IllegalStateException(
                    "The database has no migration history (flyway_schema_history). Run the migrator first: "
                            + "'migrate' on an empty database, or 'baseline' then 'migrate' on an existing one. "
                            + "See docs/operations.md.", ex);
        }
        if (actual == null || actual < expected) {
            throw new IllegalStateException(
                    "The database schema is at version " + actual + " but this build needs version " + expected
                            + ". Run the migrator ('migrate') before starting the service.");
        }
        if (actual > expected) {
            throw new IllegalStateException(
                    "The database schema is at version " + actual + ", newer than this build understands ("
                            + expected + "). Use a newer build or restore a matching backup.");
        }
        log.info("Database schema version {} matches this build.", actual);
    }

    static int newestBundledVersion() throws IOException {
        List<Resource> resources =
                List.of(new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*__*.sql"));
        int newest = 0;
        for (Resource resource : resources) {
            Matcher matcher = MIGRATION.matcher(resource.getFilename() == null ? "" : resource.getFilename());
            if (matcher.matches()) {
                newest = Math.max(newest, Integer.parseInt(matcher.group(1)));
            }
        }
        return newest;
    }
}
