package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sepro.obfds.config.AppProperties;
import com.sepro.obfds.entity.Account;
import com.sepro.obfds.entity.Beneficiary;
import com.sepro.obfds.entity.Customer;
import com.sepro.obfds.entity.RiskLevel;
import com.sepro.obfds.entity.VerificationStatus;
import com.sepro.obfds.fraud.FraudRiskService;
import com.sepro.obfds.fraud.RiskEvaluation;
import com.sepro.obfds.fraud.RiskFactor;
import com.sepro.obfds.repository.TransactionRepository;
import com.sepro.obfds.repository.VerificationRequestRepository;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fraud rule specification, executed.
 *
 * <p>What it proves, and how:</p>
 * <ul>
 *   <li><b>The documentation is the specification.</b> The point values and the risk bands are read
 *       out of {@code docs/fraud-risk-engine.md}. The expected score of every case below is computed
 *       from those documented numbers, so if the table and the engine ever disagree, this fails.</li>
 *   <li><b>All 144 categorical combinations</b> (3 amount bands x 3 payee states x 2 velocity x 2
 *       balance-drain x 2 hour x 2 failed-verification) are run through the real engine.</li>
 *   <li><b>24 boundary cases</b> pin the exact edge of each rule (inclusive or strict, and why).</li>
 *   <li>The 168 results are written to {@code docs/generated/fraud-rule-matrix.json}, which is
 *       committed. The test fails if the committed file differs from what the engine now produces, and
 *       the browser demo is checked against the same file ({@code frontend/scripts/test_rule_parity.mjs}),
 *       so the backend, the documentation and the showcase cannot drift apart unnoticed.</li>
 * </ul>
 *
 * <p>This proves the engine does what the document says. It makes no claim that the rules detect
 * fraud: they have not been validated against real data.</p>
 *
 * <p>To regenerate the committed matrix after a deliberate rule change, run with the environment
 * variable {@code OBFDS_UPDATE_GENERATED=true}.</p>
 */
class FraudRuleSpecificationTest {

    private static final Path DOC = Path.of("..", "docs", "fraud-risk-engine.md");
    private static final Path MATRIX = Path.of("..", "docs", "generated", "fraud-rule-matrix.json");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Everything the engine needs, in the units the specification uses. */
    private record Scenario(
            String id, String amount, String balance, Integer payeeAgeSeconds,
            int earlierTransfers, int failedVerifications, String istTime) {}

    private record Outcome(int score, String level, Set<String> rules) {}

    // ------------------------------------------------------------------ reading the document

    private static Map<String, Integer> documentedPoints() throws IOException {
        Map<String, Integer> points = new TreeMap<>();
        boolean inside = false;
        Pattern row = Pattern.compile("^\\|\\s*`([A-Z_]+)`\\s*\\|[^|]*\\|\\s*(\\d+)\\s*\\|");
        for (String line : Files.readAllLines(DOC)) {
            if (line.contains("<!-- rules:begin -->")) {
                inside = true;
            } else if (line.contains("<!-- rules:end -->")) {
                inside = false;
            } else if (inside) {
                Matcher matcher = row.matcher(line);
                if (matcher.find()) {
                    points.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
                }
            }
        }
        return points;
    }

    /** {mediumFrom, highFrom} read from the documented bands table. */
    private static int[] documentedBands() throws IOException {
        int medium = -1;
        int high = -1;
        boolean inside = false;
        Pattern range = Pattern.compile("^\\|\\s*(\\d+)\\s*[–-]\\s*(\\d+)\\s*\\|\\s*\\*\\*(LOW|MEDIUM|HIGH)\\*\\*");
        Pattern open = Pattern.compile("^\\|\\s*(\\d+)\\s+and above\\s*\\|\\s*\\*\\*HIGH\\*\\*");
        for (String line : Files.readAllLines(DOC)) {
            if (line.contains("<!-- bands:begin -->")) {
                inside = true;
            } else if (line.contains("<!-- bands:end -->")) {
                inside = false;
            } else if (inside) {
                Matcher matcher = range.matcher(line);
                if (matcher.find() && matcher.group(3).equals("MEDIUM")) {
                    medium = Integer.parseInt(matcher.group(1));
                }
                Matcher tail = open.matcher(line);
                if (tail.find()) {
                    high = Integer.parseInt(tail.group(1));
                }
            }
        }
        return new int[] {medium, high};
    }

    // ------------------------------------------------------------------ driving the real engine

    private static void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Outcome runEngine(Scenario scenario) {
        AppProperties properties = new AppProperties();
        properties.getFraud().setOddHourStart(0);
        properties.getFraud().setOddHourEnd(5);

        TransactionRepository transactions = mock(TransactionRepository.class);
        VerificationRequestRepository verifications = mock(VerificationRequestRepository.class);
        when(transactions.countBySourceAccountCustomerIdAndCreatedAtAfter(anyLong(), any()))
                .thenReturn((long) scenario.earlierTransfers());
        when(verifications.countByTransactionSourceAccountCustomerIdAndStatusAndCreatedAtAfter(
                        anyLong(), any(VerificationStatus.class), any()))
                .thenReturn((long) scenario.failedVerifications());

        FraudRiskService engine = new FraudRiskService(properties, transactions, verifications);

        Customer customer = new Customer();
        setId(customer, 1L);
        Account account = new Account();
        account.setAccountNumber("900000000001");
        account.setCustomer(customer);
        account.setBalance(new BigDecimal(scenario.balance()));

        String[] time = scenario.istTime().split(":");
        Instant now = LocalDateTime.of(2026, 11, 10, Integer.parseInt(time[0]), Integer.parseInt(time[1]))
                .atZone(IST).toInstant();

        Beneficiary payee = null;
        if (scenario.payeeAgeSeconds() != null) {
            payee = new Beneficiary();
            payee.setCreatedAt(now.minusSeconds(scenario.payeeAgeSeconds()));
        }

        RiskEvaluation evaluation = engine.evaluate(account, payee, new BigDecimal(scenario.amount()), now);
        return new Outcome(
                evaluation.score(), evaluation.level().name(),
                evaluation.factors().stream().map(RiskFactor::code).collect(Collectors.toCollection(TreeSet::new)));
    }

    // ------------------------------------------------------------------ the specification oracle

    /** Which rules the documented semantics say must fire. Written from the document, not the code. */
    private static Set<String> specifiedRules(Scenario s) {
        Set<String> rules = new TreeSet<>();
        BigDecimal amount = new BigDecimal(s.amount());
        BigDecimal balance = new BigDecimal(s.balance());
        if (amount.compareTo(new BigDecimal("100000")) >= 0) {
            rules.add("VERY_HIGH_AMOUNT");
        } else if (amount.compareTo(new BigDecimal("50000")) >= 0) {
            rules.add("HIGH_AMOUNT");
        }
        if (s.payeeAgeSeconds() == null) {
            rules.add("UNREGISTERED_PAYEE");
        } else if (s.payeeAgeSeconds() < 24 * 3600) {
            rules.add("NEW_BENEFICIARY");
        }
        if (s.earlierTransfers() >= 3) {
            rules.add("RAPID_TRANSFERS");
        }
        if (balance.signum() > 0 && amount.compareTo(balance.multiply(new BigDecimal("0.80"))) > 0) {
            rules.add("BALANCE_DRAIN");
        }
        int hour = Integer.parseInt(s.istTime().split(":")[0]);
        if (hour >= 0 && hour < 5) {
            rules.add("UNUSUAL_HOUR");
        }
        if (s.failedVerifications() >= 2) {
            rules.add("REPEATED_FAILED_VERIFICATION");
        }
        return rules;
    }

    private static String specifiedLevel(int score, int[] bands) {
        return score >= bands[1] ? "HIGH" : score >= bands[0] ? "MEDIUM" : "LOW";
    }

    // ------------------------------------------------------------------ the cases

    private static List<Scenario> combinations() {
        List<Scenario> cases = new ArrayList<>();
        int n = 0;
        for (String amount : List.of("2500.00", "60000.00", "150000.00")) {
            for (Integer age : new Integer[] {30 * 24 * 3600, 3600, null}) {
                for (int velocity : new int[] {0, 3}) {
                    for (boolean drain : new boolean[] {false, true}) {
                        for (String time : List.of("12:00", "02:00")) {
                            for (int failed : new int[] {0, 2}) {
                                BigDecimal value = new BigDecimal(amount);
                                // not draining: the balance is ten times the amount; draining: only 10% more.
                                BigDecimal balance = drain ? value.multiply(new BigDecimal("1.1")) : value.multiply(BigDecimal.TEN);
                                cases.add(new Scenario("C" + String.format("%03d", ++n), amount,
                                        balance.setScale(2).toPlainString(), age, velocity, failed, time));
                            }
                        }
                    }
                }
            }
        }
        return cases;
    }

    /** {scenario, hand-written expected rules}. These do not use the oracle above. */
    private static Map<Scenario, Set<String>> boundaries() {
        Map<Scenario, Set<String>> b = new LinkedHashMap<>();
        String big = "10000000.00"; // keeps BALANCE_DRAIN out of the way
        int month = 30 * 24 * 3600;
        b.put(new Scenario("B01", "49999.99", big, month, 0, 0, "12:00"), Set.of());
        b.put(new Scenario("B02", "50000.00", big, month, 0, 0, "12:00"), Set.of("HIGH_AMOUNT"));
        b.put(new Scenario("B03", "99999.99", big, month, 0, 0, "12:00"), Set.of("HIGH_AMOUNT"));
        b.put(new Scenario("B04", "100000.00", big, month, 0, 0, "12:00"), Set.of("VERY_HIGH_AMOUNT"));
        b.put(new Scenario("B05", "2500.00", big, 86399, 0, 0, "12:00"), Set.of("NEW_BENEFICIARY"));
        b.put(new Scenario("B06", "2500.00", big, 86400, 0, 0, "12:00"), Set.of());
        b.put(new Scenario("B07", "2500.00", big, 86401, 0, 0, "12:00"), Set.of());
        b.put(new Scenario("B08", "2500.00", big, month, 2, 0, "12:00"), Set.of());
        b.put(new Scenario("B09", "2500.00", big, month, 3, 0, "12:00"), Set.of("RAPID_TRANSFERS"));
        b.put(new Scenario("B10", "2500.00", big, month, 0, 1, "12:00"), Set.of());
        b.put(new Scenario("B11", "2500.00", big, month, 0, 2, "12:00"), Set.of("REPEATED_FAILED_VERIFICATION"));
        b.put(new Scenario("B12", "8000.00", "10000.00", month, 0, 0, "12:00"), Set.of());
        b.put(new Scenario("B13", "8000.01", "10000.00", month, 0, 0, "12:00"), Set.of("BALANCE_DRAIN"));
        b.put(new Scenario("B14", "2500.00", "0.00", month, 0, 0, "12:00"), Set.of());
        b.put(new Scenario("B15", "2500.00", big, month, 0, 0, "23:59"), Set.of());
        b.put(new Scenario("B16", "2500.00", big, month, 0, 0, "00:00"), Set.of("UNUSUAL_HOUR"));
        b.put(new Scenario("B17", "2500.00", big, month, 0, 0, "04:59"), Set.of("UNUSUAL_HOUR"));
        b.put(new Scenario("B18", "2500.00", big, month, 0, 0, "05:00"), Set.of());
        // B19-B22 are the score bands, tested through classify() below.
        b.put(new Scenario("B23", "150000.00", "160000.00", 3600, 3, 2, "02:00"),
                Set.of("VERY_HIGH_AMOUNT", "NEW_BENEFICIARY", "RAPID_TRANSFERS", "BALANCE_DRAIN", "UNUSUAL_HOUR",
                        "REPEATED_FAILED_VERIFICATION"));
        b.put(new Scenario("B24", "150000.00", "10000000.00", null, 0, 0, "12:00"),
                Set.of("VERY_HIGH_AMOUNT", "UNREGISTERED_PAYEE"));
        return b;
    }

    private static final List<int[]> BAND_BOUNDARIES = List.of(
            new int[] {29, 0}, new int[] {30, 1}, new int[] {59, 1}, new int[] {60, 2}); // score, level index
    private static final String[] LEVELS = {"LOW", "MEDIUM", "HIGH"};

    // ------------------------------------------------------------------ the tests

    @Test
    @DisplayName("SPEC-0 The document's rule and band tables were found and parsed (the suite is not vacuous)")
    void documentTablesAreParsed() throws IOException {
        assertThat(documentedPoints()).containsOnlyKeys(
                "VERY_HIGH_AMOUNT", "HIGH_AMOUNT", "NEW_BENEFICIARY", "UNREGISTERED_PAYEE", "RAPID_TRANSFERS",
                "REPEATED_FAILED_VERIFICATION", "BALANCE_DRAIN", "UNUSUAL_HOUR");
        assertThat(documentedBands()).containsExactly(30, 60);
        assertThat(combinations()).hasSize(144);
        assertThat(boundaries()).hasSize(20);
        assertThat(BAND_BOUNDARIES).hasSize(4); // 20 + 4 = 24 boundary cases in total
    }

    @Test
    @DisplayName("SPEC-1 All 144 categorical combinations score exactly as the documented table says")
    void allCombinationsMatchTheDocumentedTable() throws IOException {
        Map<String, Integer> points = documentedPoints();
        int[] bands = documentedBands();
        List<String> mismatches = new ArrayList<>();
        for (Scenario scenario : combinations()) {
            Outcome actual = runEngine(scenario);
            Set<String> rules = specifiedRules(scenario);
            int score = rules.stream().mapToInt(points::get).sum();
            if (!actual.rules().equals(rules) || actual.score() != score
                    || !actual.level().equals(specifiedLevel(score, bands))) {
                mismatches.add(scenario + " -> engine " + actual + " but the specification says "
                        + rules + " = " + score + " " + specifiedLevel(score, bands));
            }
            // The two exclusive pairs never both fire.
            assertThat(actual.rules().contains("VERY_HIGH_AMOUNT") && actual.rules().contains("HIGH_AMOUNT")).isFalse();
            assertThat(actual.rules().contains("NEW_BENEFICIARY") && actual.rules().contains("UNREGISTERED_PAYEE")).isFalse();
            assertThat(actual.score()).isLessThanOrEqualTo(140);
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    @DisplayName("SPEC-2 Each of the 24 boundary cases fires exactly the rules the specification names")
    void boundaryCasesMatch() throws IOException {
        Map<String, Integer> points = documentedPoints();
        int[] bands = documentedBands();
        boundaries().forEach((scenario, expected) -> {
            Outcome actual = runEngine(scenario);
            assertThat(actual.rules()).as("%s %s", scenario.id(), scenario).isEqualTo(new TreeSet<>(expected));
            int score = expected.stream().mapToInt(points::get).sum();
            assertThat(actual.score()).as("%s score", scenario.id()).isEqualTo(score);
            assertThat(actual.level()).as("%s level", scenario.id()).isEqualTo(specifiedLevel(score, bands));
        });

        FraudRiskService engine = new FraudRiskService(
                new AppProperties(), mock(TransactionRepository.class), mock(VerificationRequestRepository.class));
        for (int[] boundary : BAND_BOUNDARIES) {
            assertThat(engine.classify(boundary[0])).as("score %d", boundary[0])
                    .isEqualTo(RiskLevel.valueOf(LEVELS[boundary[1]]));
        }
        assertThat(boundaries().size() + BAND_BOUNDARIES.size()).isEqualTo(24);
    }

    @Test
    @DisplayName("SPEC-3 The worked examples in the document score as printed")
    void workedExamplesMatch() {
        record Example(String name, Scenario scenario, int score, String level) {}
        int month = 30 * 24 * 3600;
        List<Example> examples = List.of(
                new Example("2,500 to an established payee", new Scenario("E1", "2500.00", "100000.00", month, 0, 0, "12:00"), 0, "LOW"),
                new Example("55,000 to an established payee", new Scenario("E2", "55000.00", "1000000.00", month, 0, 0, "12:00"), 35, "MEDIUM"),
                new Example("50,000 to a payee added an hour ago", new Scenario("E3", "50000.00", "1000000.00", 3600, 0, 0, "12:00"), 60, "HIGH"),
                new Example("49,999.99 to a payee added an hour ago", new Scenario("E4", "49999.99", "1000000.00", 3600, 0, 0, "12:00"), 25, "LOW"),
                new Example("120,000 to a payee added minutes ago", new Scenario("E5", "120000.00", "10000000.00", 300, 0, 0, "12:00"), 75, "HIGH"),
                new Example("1,000 to an unsaved account", new Scenario("E6", "1000.00", "100000.00", null, 0, 0, "12:00"), 20, "LOW"),
                new Example("4th transfer within 10 minutes", new Scenario("E7", "1000.00", "100000.00", month, 3, 0, "12:00"), 20, "LOW"));
        for (Example example : examples) {
            Outcome outcome = runEngine(example.scenario());
            assertThat(outcome.score()).as(example.name()).isEqualTo(example.score());
            assertThat(outcome.level()).as(example.name()).isEqualTo(example.level());
        }
    }

    @Test
    @DisplayName("SPEC-4 The committed rule matrix equals what the engine produces now (backend/docs/showcase share it)")
    void committedMatrixIsCurrent() throws IOException {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        List<Object> cases = new ArrayList<>();
        List<Scenario> all = new ArrayList<>(combinations());
        all.addAll(boundaries().keySet());
        for (Scenario scenario : all) {
            Outcome outcome = runEngine(scenario);
            Map<String, Object> entry = new TreeMap<>();
            entry.put("id", scenario.id());
            Map<String, Object> input = new TreeMap<>();
            input.put("amount", scenario.amount());
            input.put("balance", scenario.balance());
            input.put("payeeAgeSeconds", scenario.payeeAgeSeconds());
            input.put("earlierTransfers", scenario.earlierTransfers());
            input.put("failedVerifications", scenario.failedVerifications());
            input.put("istTime", scenario.istTime());
            entry.put("input", input);
            entry.put("score", outcome.score());
            entry.put("level", outcome.level());
            entry.put("rules", new ArrayList<>(outcome.rules()));
            cases.add(entry);
        }
        Map<String, Object> document = new TreeMap<>();
        document.put("generatedBy", "FraudRuleSpecificationTest (do not edit by hand; see its Javadoc)");
        document.put("cases", cases);
        document.put("zone", "Asia/Kolkata");
        String generated = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document).replace("\r\n", "\n") + "\n";

        if ("true".equals(System.getenv("OBFDS_UPDATE_GENERATED"))) {
            Files.createDirectories(MATRIX.getParent());
            Files.writeString(MATRIX, generated);
        }
        assertThat(Files.exists(MATRIX)).as("docs/generated/fraud-rule-matrix.json is committed").isTrue();
        assertThat(Files.readString(MATRIX).replace("\r\n", "\n"))
                .as("run once with OBFDS_UPDATE_GENERATED=true after a deliberate rule change")
                .isEqualTo(generated);
        assertThat(cases).hasSize(144 + 20);
    }
}
