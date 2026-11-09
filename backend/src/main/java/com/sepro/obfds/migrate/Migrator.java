package com.sepro.obfds.migrate;

import java.io.PrintStream;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * The only thing that changes the database schema.
 *
 * <p>The application itself never runs migrations. Keeping schema changes in a separate, explicit
 * step lets the running service use a database account that can read and write rows but cannot
 * create, alter or drop tables, so a bug or an injection in the application cannot damage the
 * schema. This class is that step. It is plain Java with no Spring and no web stack, so it needs no
 * signing secret and cannot start a server.</p>
 *
 * <pre>
 * java -cp obfds-backend.jar -Dloader.main=com.sepro.obfds.migrate.Migrator \
 *      org.springframework.boot.loader.launch.PropertiesLauncher  &lt;info|validate|migrate|baseline&gt;
 * </pre>
 *
 * <p>Settings come from the environment, never from arguments (which show up in process lists):
 * {@code MIGRATE_URL}, {@code MIGRATE_USERNAME}, {@code MIGRATE_PASSWORD}. Secrets are never printed.</p>
 *
 * <h2>Safety rules that are enforced here, not merely documented</h2>
 * <ul>
 *   <li>{@code clean} does not exist, and Flyway's own clean is disabled.</li>
 *   <li>{@code migrate} against a database that has tables but no schema history is refused by
 *       Flyway ("non-empty schema without history"). A fresh {@code V1} is therefore never run
 *       over existing tables.</li>
 *   <li>Adopting an existing, populated database needs the explicit {@code baseline} command and
 *       {@code OBFDS_MIGRATE_CONFIRM_BASELINE=yes}. It writes only a history row and does not
 *       touch data; take a backup first (the runbook's script does).</li>
 *   <li>Checksums are validated on every {@code migrate}, so an edited migration is detected.</li>
 * </ul>
 */
public final class Migrator {

    /** The version an existing, pre-Flyway database is declared to be at when it is adopted. */
    public static final String BASELINE_VERSION = "1";

    private Migrator() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out));
    }

    /** Runs one command and returns the process exit code (0 = success). */
    public static int run(String[] args, Map<String, String> env, PrintStream out) {
        if (args.length != 1) {
            out.println("usage: Migrator <info|validate|migrate|baseline>");
            return 2;
        }
        String url = env.get("MIGRATE_URL");
        String user = env.get("MIGRATE_USERNAME");
        String password = env.get("MIGRATE_PASSWORD");
        if (isBlank(url) || isBlank(user) || password == null) {
            out.println("MIGRATE_URL, MIGRATE_USERNAME and MIGRATE_PASSWORD must be set.");
            return 2;
        }
        try {
            Flyway flyway = configure(url, user, password);
            switch (args[0]) {
                case "info" -> printInfo(flyway, out);
                case "validate" -> {
                    flyway.validate();
                    out.println("validate: OK - the history matches the migrations on the class path.");
                }
                case "migrate" -> {
                    MigrateResult result = flyway.migrate();
                    out.println("migrate: " + result.migrationsExecuted + " migration(s) applied; schema now at "
                            + result.targetSchemaVersion);
                }
                case "baseline" -> {
                    if (!"yes".equals(env.get("OBFDS_MIGRATE_CONFIRM_BASELINE"))) {
                        out.println("baseline refused: set OBFDS_MIGRATE_CONFIRM_BASELINE=yes after taking a backup.");
                        return 3;
                    }
                    flyway.baseline();
                    out.println("baseline: schema declared to be at version " + BASELINE_VERSION
                            + ". No data was changed.");
                }
                default -> {
                    out.println("unknown command '" + args[0] + "'; use info, validate, migrate or baseline.");
                    return 2;
                }
            }
            return 0;
        } catch (RuntimeException ex) {
            // Flyway's messages name tables and versions, never the password.
            out.println(args[0] + " FAILED: " + ex.getMessage());
            return 1;
        }
    }

    /** The single Flyway configuration used by the command line and by the tests. */
    public static Flyway configure(String url, String user, String password) {
        return Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .baselineVersion(BASELINE_VERSION)
                .validateOnMigrate(true)
                .load();
    }

    private static void printInfo(Flyway flyway, PrintStream out) {
        out.println("version | state | description");
        for (MigrationInfo info : flyway.info().all()) {
            out.println((info.getVersion() == null ? "(repeatable)" : info.getVersion().getVersion())
                    + " | " + info.getState() + " | " + info.getDescription());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
