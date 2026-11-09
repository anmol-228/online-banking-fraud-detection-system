package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sepro.obfds.entity.AccountStatus;
import com.sepro.obfds.entity.AccountType;
import com.sepro.obfds.entity.AlertStatus;
import com.sepro.obfds.entity.AuditOutcome;
import com.sepro.obfds.entity.CaseStatus;
import com.sepro.obfds.entity.DisputeStatus;
import com.sepro.obfds.entity.NotificationType;
import com.sepro.obfds.entity.RiskLevel;
import com.sepro.obfds.entity.RoleName;
import com.sepro.obfds.entity.TransactionStatus;
import com.sepro.obfds.entity.VerificationStatus;
import com.sepro.obfds.migrate.Migrator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Schema migrations against a real MySQL server (skipped on H2 runs).
 *
 * <p>Runs only when {@code SPRING_DATASOURCE_URL} points at MySQL, and then against a SEPARATE
 * scratch database ({@code OBFDS_MIGRATION_SCRATCH_DB}, default {@code obfds_probe}) which these
 * tests empty at the start of every test. The golden demonstration database and the database used
 * by the other tests are never touched; the class refuses to run if the scratch name is either.</p>
 */
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = "jdbc:mysql:.*")
class SchemaMigrationMysqlTest {

    /** Every native ENUM column in the schema and the Java enum whose values it must list. */
    private static final Map<String, Class<? extends Enum<?>>> ENUM_COLUMNS = new LinkedHashMap<>();

    static {
        ENUM_COLUMNS.put("account.account_type", AccountType.class);
        ENUM_COLUMNS.put("account.status", AccountStatus.class);
        ENUM_COLUMNS.put("audit_log.outcome", AuditOutcome.class);
        ENUM_COLUMNS.put("bank_transaction.status", TransactionStatus.class);
        ENUM_COLUMNS.put("bank_transaction.risk_level", RiskLevel.class);
        ENUM_COLUMNS.put("fraud_alert.risk_level", RiskLevel.class);
        ENUM_COLUMNS.put("fraud_alert.status", AlertStatus.class);
        ENUM_COLUMNS.put("fraud_case.status", CaseStatus.class);
        ENUM_COLUMNS.put("dispute.status", DisputeStatus.class);
        ENUM_COLUMNS.put("notification.type", NotificationType.class);
        ENUM_COLUMNS.put("user_role.name", RoleName.class);
        ENUM_COLUMNS.put("verification_request.status", VerificationStatus.class);
    }

    private String url;
    private String user;
    private String password;
    private String database;

    @BeforeEach
    void emptyTheScratchDatabase() throws SQLException {
        String configured = System.getenv("SPRING_DATASOURCE_URL");
        database = System.getenv().getOrDefault("OBFDS_MIGRATION_SCRATCH_DB", "obfds_probe");
        Matcher matcher = Pattern.compile("^(jdbc:mysql://[^/]+/)([^?]+)(.*)$").matcher(configured);
        assertThat(matcher.matches()).as("a jdbc:mysql URL with a database name").isTrue();
        assertThat(database).as("scratch database").isNotIn("obfds", matcher.group(2));
        url = matcher.group(1) + database + matcher.group(3);
        user = System.getenv("SPRING_DATASOURCE_USERNAME");
        password = System.getenv("SPRING_DATASOURCE_PASSWORD");

        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery(
                    "select table_name from information_schema.tables where table_schema = database()")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String table : tables) {
                statement.execute("DROP TABLE `" + table + "`");
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    }

    private Flyway flyway() {
        return Migrator.configure(url, user, password);
    }

    /** Creates the V1 schema exactly as a pre-Flyway installation has it: tables, no history. */
    private void createPreFlywayDatabaseWithData() throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V1__baseline.sql"));
        }
        JdbcTemplate jdbc = jdbc();
        jdbc.update("insert into user_role (name, description) values ('CUSTOMER', 'c'), ('BANK_ADMIN', 'a')");
        jdbc.update("insert into application_user (created_at, email, enabled, full_name, password_hash, username) "
                + "values (utc_timestamp(6), 'pre@example.invalid', b'1', 'Pre Flyway', 'x', 'pre.flyway')");
        jdbc.update("insert into customer (created_at, updated_at, customer_number, email, full_name, user_id) "
                + "select utc_timestamp(6), utc_timestamp(6), 'CUSTPRE1', 'pre@example.invalid', 'Pre Flyway', id "
                + "from application_user where username = 'pre.flyway'");
        jdbc.update("insert into account (account_number, account_type, balance, currency, opened_at, status, version, customer_id) "
                + "select '777000000001', 'SAVINGS', 123.45, 'INR', utc_timestamp(6), 'ACTIVE', 0, id from customer");
    }

    private String checksum(String table) {
        return jdbc().queryForMap("checksum table " + table).values().stream()
                .reduce((first, second) -> second).orElseThrow().toString();
    }

    @Test
    @DisplayName("MIG-1 An empty database migrates to the latest version with the history recorded")
    void emptyDatabaseMigratesToLatest() {
        flyway().migrate();

        JdbcTemplate jdbc = jdbc();
        assertThat(jdbc.queryForList(
                        "select version from flyway_schema_history where success = 1 order by installed_rank", String.class))
                .containsExactly("1", "2");
        assertThat(jdbc.queryForObject(
                        "select count(*) from information_schema.tables where table_schema = database() "
                                + "and table_name <> 'flyway_schema_history'", Integer.class))
                .isEqualTo(13);
        assertThat(jdbc.queryForObject(
                        "select count(*) from information_schema.columns where table_schema = database() "
                                + "and table_name = 'fraud_case' and column_name in "
                                + "('customer_response', 'customer_responded_at', 'review_due_at')", Integer.class))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("MIG-2 The column-level AUTO_INCREMENT survives: inserts get generated ids, as JPA IDENTITY needs")
    void identityColumnsGenerateIds() {
        flyway().migrate();
        JdbcTemplate jdbc = jdbc();

        List<String> identityTables = jdbc.queryForList(
                "select table_name from information_schema.columns where table_schema = database() "
                        + "and column_name = 'id' and extra like '%auto_increment%' order by table_name", String.class);
        assertThat(identityTables).as("tables with an AUTO_INCREMENT id").hasSize(12);

        jdbc.update("insert into user_role (name, description) values ('CUSTOMER', 'one')");
        jdbc.update("insert into user_role (name, description) values ('BANK_ADMIN', 'two')");
        assertThat(jdbc.queryForList("select id from user_role order by id", Long.class)).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("MIG-3 Every native ENUM column lists exactly the values of its Java enum")
    void enumColumnsMatchTheJavaEnums() {
        flyway().migrate();
        JdbcTemplate jdbc = jdbc();

        assertThat(jdbc.queryForObject(
                        "select count(*) from information_schema.columns where table_schema = database() "
                                + "and data_type = 'enum'", Integer.class))
                .as("a new ENUM column needs an entry in ENUM_COLUMNS above")
                .isEqualTo(ENUM_COLUMNS.size());

        ENUM_COLUMNS.forEach((column, enumType) -> {
            String[] parts = column.split("\\.");
            String columnType = jdbc.queryForObject(
                    "select column_type from information_schema.columns where table_schema = database() "
                            + "and table_name = ? and column_name = ?", String.class, parts[0], parts[1]);
            List<String> inDatabase = Arrays.stream(columnType.substring(5, columnType.length() - 1).split(","))
                    .map(value -> value.replace("'", "")).sorted().toList();
            List<String> inJava = Arrays.stream(enumType.getEnumConstants()).map(Enum::name).sorted().toList();
            assertThat(inDatabase).as(column).isEqualTo(inJava);
        });
    }

    @Test
    @DisplayName("MIG-4 Hibernate validate accepts the migrated schema and entities can be inserted into it")
    void hibernateValidatesTheMigratedSchema() {
        flyway().migrate();

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ObfdsApplication.class)
                .run("--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                        "--spring.jmx.enabled=false", "--spring.profiles.active=test",
                        "--spring.datasource.url=" + url,
                        "--spring.datasource.username=" + user,
                        "--spring.datasource.password=" + password,
                        "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "--spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
                        "--spring.jpa.hibernate.ddl-auto=validate")) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            // The seeder inserted users, customers, accounts and beneficiaries through JPA.
            assertThat(jdbc.queryForObject("select count(*) from application_user", Integer.class)).isPositive();
            assertThat(jdbc.queryForObject("select count(*) from account", Integer.class)).isPositive();
        }
    }

    @Test
    @DisplayName("MIG-5 NEGATIVE CONTROL: migrate refuses a populated database that has no history")
    void migrateRefusesAPopulatedDatabaseWithoutHistory() throws Exception {
        createPreFlywayDatabaseWithData();
        String before = checksum("account");

        assertThatThrownBy(() -> flyway().migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("non-empty");

        assertThat(checksum("account")).as("data untouched by the refused migration").isEqualTo(before);
        assertThat(jdbc().queryForObject(
                        "select count(*) from information_schema.tables where table_schema = database() "
                                + "and table_name = 'flyway_schema_history'", Integer.class))
                .as("no history table was created by the refused migration").isZero();
    }

    @Test
    @DisplayName("MIG-6 A populated pre-Flyway database is adopted by baseline, upgraded to V2, and its data is unchanged")
    void populatedDatabaseIsBaselinedAndUpgradedWithoutDataChange() throws Exception {
        createPreFlywayDatabaseWithData();
        Map<String, String> before = new LinkedHashMap<>();
        for (String table : List.of("user_role", "application_user", "customer", "account")) {
            before.put(table, checksum(table));
        }

        flyway().baseline();
        flyway().migrate();

        JdbcTemplate jdbc = jdbc();
        assertThat(jdbc.queryForList(
                        "select concat(version, ':', type) from flyway_schema_history order by installed_rank", String.class))
                .containsExactly("1:BASELINE", "2:SQL");
        before.forEach((table, sum) -> assertThat(checksum(table)).as(table).isEqualTo(sum));
        assertThat(jdbc.queryForObject("select balance from account where account_number = '777000000001'",
                        java.math.BigDecimal.class))
                .isEqualByComparingTo("123.45");
        assertThat(jdbc.queryForObject(
                        "select count(*) from information_schema.columns where table_schema = database() "
                                + "and table_name = 'fraud_case' and column_name = 'review_due_at'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("MIG-7 NEGATIVE CONTROL: clean is disabled and an edited migration is detected")
    void cleanIsDisabledAndChecksumsAreEnforced() {
        flyway().migrate();

        assertThatThrownBy(() -> flyway().clean()).isInstanceOf(FlywayException.class);
        assertThat(jdbc().queryForObject("select count(*) from user_role", Integer.class)).isNotNull();

        jdbc().update("update flyway_schema_history set checksum = checksum + 1 where version = '1'");
        assertThatThrownBy(() -> flyway().validate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("checksum");
    }

    private ConfigurableApplicationContext startWithMysqlProfile() {
        return new SpringApplicationBuilder(ObfdsApplication.class)
                .run("--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                        "--spring.jmx.enabled=false", "--spring.profiles.active=test,mysql",
                        "--spring.datasource.url=" + url,
                        "--spring.datasource.username=" + user,
                        "--spring.datasource.password=" + password,
                        "--spring.jpa.hibernate.ddl-auto=validate");
    }

    @Test
    @DisplayName("MIG-9 A schema that is behind the entities is refused by Hibernate validate (nothing is altered)")
    void serviceRefusesAnOutOfDateSchemaShape() {
        Flyway toV1Only = Flyway.configure().configuration(flyway().getConfiguration()).target("1").load();
        toV1Only.migrate();

        assertThatThrownBy(this::startWithMysqlProfile)
                .hasStackTraceContaining("Schema-validation: missing column");
        assertThat(jdbc().queryForObject(
                        "select count(*) from information_schema.columns where table_schema = database() "
                                + "and table_name = 'fraud_case' and column_name = 'review_due_at'", Integer.class))
                .as("the service must not have altered the schema to fix it").isZero();
    }

    @Test
    @DisplayName("MIG-10 If the history says the migrator has not reached this build's version, the service refuses to start")
    void serviceRefusesWhenTheHistoryIsBehind() {
        flyway().migrate();
        // Same table shape as V2 (so Hibernate is satisfied) but the history claims V2 never succeeded:
        // the situation after someone altered the schema by hand.
        jdbc().update("update flyway_schema_history set success = 0 where version = '2'");

        assertThatThrownBy(this::startWithMysqlProfile)
                .hasStackTraceContaining("schema is at version 1")
                .hasStackTraceContaining("needs version 2");
    }

    @Test
    @DisplayName("MIG-10b A database with the right shape but no migration history at all is refused")
    void serviceRefusesADatabaseWithoutHistory() {
        flyway().migrate();
        jdbc().execute("drop table flyway_schema_history");

        assertThatThrownBy(this::startWithMysqlProfile)
                .hasStackTraceContaining("no migration history");
    }
    @Test
    @DisplayName("MIG-11 The service starts, and its version check passes, on a fully migrated database")
    void serviceStartsOnAnUpToDateSchema() {
        flyway().migrate();

        try (ConfigurableApplicationContext context = startWithMysqlProfile()) {
            assertThat(context.isRunning()).isTrue();
        }
    }

    @Test
    @DisplayName("MIG-8 The command line refuses baseline without confirmation and never prints the password")
    void commandLineSafety() {
        Map<String, String> env = Map.of(
                "MIGRATE_URL", url, "MIGRATE_USERNAME", user, "MIGRATE_PASSWORD", password);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);
        assertThat(Migrator.run(new String[] {"baseline"}, env, out)).isEqualTo(3);
        assertThat(Migrator.run(new String[] {"migrate"}, env, out)).isZero();
        assertThat(Migrator.run(new String[] {"info"}, env, out)).isZero();
        assertThat(Migrator.run(new String[] {"validate"}, env, out)).isZero();
        assertThat(Migrator.run(new String[] {"migrate"}, Map.of(), out)).isEqualTo(2);
        assertThat(Migrator.run(new String[] {"clean"}, env, out)).isEqualTo(2);

        String output = captured.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("baseline refused").contains("2 migration(s) applied");
        if (!password.isBlank()) {
            assertThat(output).doesNotContain(password);
        }
    }
}
