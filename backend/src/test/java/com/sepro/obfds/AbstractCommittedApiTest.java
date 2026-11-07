package com.sepro.obfds;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sepro.obfds.entity.Account;
import com.sepro.obfds.entity.AccountType;
import com.sepro.obfds.entity.ApplicationUser;
import com.sepro.obfds.entity.Beneficiary;
import com.sepro.obfds.entity.Customer;
import com.sepro.obfds.entity.RoleName;
import com.sepro.obfds.repository.AccountRepository;
import com.sepro.obfds.repository.ApplicationUserRepository;
import com.sepro.obfds.repository.BeneficiaryRepository;
import com.sepro.obfds.repository.CustomerRepository;
import com.sepro.obfds.repository.UserRoleRepository;
import com.sepro.obfds.service.ReferenceGenerator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Base for tests that need <em>committed</em> data and genuinely overlapping transactions.
 *
 * <p>Unlike {@link AbstractApiTest}, nothing here runs inside a test-managed transaction that is
 * rolled back afterwards: a race only exists if two requests reach the database before either has
 * committed. Because the data is real, each test creates its own throw-away customers instead of
 * touching the seeded ones, and these classes are tagged {@code concurrency} so the build runs them
 * in a separate JVM (see {@code pom.xml}) where they cannot disturb tests that count rows.</p>
 *
 * <p>Risk scores used by the fixtures (all weights are the engine's constants): an amount of
 * 60,000 scores 35 (high amount); a payee that is not a saved beneficiary adds 20, so 60,000 to
 * an unregistered payee is 55 = MEDIUM; a beneficiary saved just now adds 25 instead, so 60,000
 * to it is 60 = HIGH; anything under 50,000 to an unregistered payee is 20 = LOW.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("concurrency")
abstract class AbstractCommittedApiTest {

    protected static final String PASSWORD = "Committed@12345";
    private static final AtomicInteger SEQUENCE = new AtomicInteger(1000);

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ApplicationUserRepository users;
    @Autowired protected UserRoleRepository roles;
    @Autowired protected CustomerRepository customers;
    @Autowired protected AccountRepository accounts;
    @Autowired protected BeneficiaryRepository beneficiaries;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected ReferenceGenerator references;
    @Autowired protected PlatformTransactionManager transactionManager;

    /** A throw-away customer with one account and a ready-to-use bearer token. */
    protected record Fixture(String username, Long customerId, String accountNumber, String token) {}

    protected Fixture newCustomer(String balance) throws Exception {
        int n = SEQUENCE.incrementAndGet();
        String username = "cc.user." + n + "." + Long.toString(System.nanoTime(), 36);
        Long[] ids = new Long[1];
        String[] accountNumber = new String[1];
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ApplicationUser user = new ApplicationUser();
            user.setUsername(username);
            user.setPasswordHash(passwordEncoder.encode(PASSWORD));
            user.setFullName("Committed Test " + n);
            user.setEmail(username + "@demomail.example");
            user.setEnabled(true);
            user.setRoles(new LinkedHashSet<>(Set.of(roles.findByName(RoleName.CUSTOMER).orElseThrow())));
            users.save(user);

            Customer customer = new Customer();
            customer.setCustomerNumber(references.customerNumber());
            customer.setFullName("Committed Test " + n);
            customer.setEmail(username + "@demomail.example");
            customer.setUser(user);
            customers.save(customer);

            Account account = new Account();
            account.setAccountNumber(references.accountNumber());
            account.setCustomer(customer);
            account.setAccountType(AccountType.SAVINGS);
            account.setBalance(new BigDecimal(balance));
            accounts.save(account);

            ids[0] = customer.getId();
            accountNumber[0] = account.getAccountNumber();
        });
        return new Fixture(username, ids[0], accountNumber[0], login(username, PASSWORD));
    }

    /** Saves {@code destination} as a payee of the customer, created {@code ageHours} ago. */
    protected void addBeneficiary(Fixture owner, String destination, long ageHours) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Beneficiary beneficiary = new Beneficiary();
            beneficiary.setCustomer(customers.findById(owner.customerId()).orElseThrow());
            beneficiary.setName("Payee " + destination);
            beneficiary.setAccountNumber(destination);
            beneficiary.setBankName("Example Bank");
            beneficiary.setIfscCode("EXMP0000456");
            beneficiary.setNickname("t");
            beneficiary.setActive(true);
            beneficiary.setCreatedAt(Instant.now().minusSeconds(ageHours * 3600));
            beneficiaries.save(beneficiary);
        });
    }

    protected String login(String username, String password) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", username, "password", password));
        String response = mockMvc
                .perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(response).get("token").asText();
    }

    protected Map<String, Object> transferBody(String source, String destination, String amount, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceAccountNumber", source);
        body.put("destinationAccountNumber", destination);
        body.put("amount", amount);
        body.put("description", "committed test");
        if (key != null) {
            body.put("idempotencyKey", key);
        }
        return body;
    }

    protected MvcResult transfer(String token, String source, String destination, String amount, String key)
            throws Exception {
        return mockMvc
                .perform(post("/api/transactions/transfer")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferBody(source, destination, amount, key))))
                .andReturn();
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * Runs the callables on separate threads released together by a barrier (never by sleeping)
     * and returns their results in the order given.
     */
    protected <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CyclicBarrier start = new CyclicBarrier(tasks.size());
        try {
            List<Future<T>> futures = tasks.stream()
                    .map(task -> pool.submit(() -> {
                        start.await(15, TimeUnit.SECONDS);
                        return task.call();
                    }))
                    .toList();
            List<T> results = new java.util.ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- direct database reads, independent of the application's own queries ----

    protected BigDecimal balanceOf(String accountNumber) {
        return jdbc.queryForObject(
                "select balance from account where account_number = ?", BigDecimal.class, accountNumber);
    }

    /** Sum of amounts the account has promised to hold (the same states the application reserves). */
    protected BigDecimal heldOn(String accountNumber) {
        return jdbc.queryForObject(
                "select coalesce(sum(t.amount), 0) from bank_transaction t "
                        + "join account a on a.id = t.source_account_id "
                        + "where a.account_number = ? and t.status in ('PENDING', 'PENDING_VERIFICATION')",
                BigDecimal.class, accountNumber);
    }

    protected int rowsWithKey(String key) {
        return jdbc.queryForObject(
                "select count(*) from bank_transaction where idempotency_key = ?", Integer.class, key);
    }

    // ---- scenario helpers shared by the workflow tests ----

    /** A HIGH-risk hold (60,000 to a payee saved just now = 35 + 25 = 60). Returns the transaction reference. */
    protected String holdHigh(Fixture customer, String payee) throws Exception {
        addBeneficiary(customer, payee, 0);
        MvcResult result = transfer(customer.token(), customer.accountNumber(), payee, "60000.00", null);
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(201);
        org.assertj.core.api.Assertions.assertThat(body(result).get("status").asText()).isEqualTo("PENDING");
        return body(result).get("reference").asText();
    }

    /** A MEDIUM-risk hold (60,000 to an unregistered payee = 35 + 20 = 55) awaiting a verification code. */
    protected String holdMedium(Fixture customer, String payee) throws Exception {
        MvcResult result = transfer(customer.token(), customer.accountNumber(), payee, "60000.00", null);
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(201);
        org.assertj.core.api.Assertions.assertThat(body(result).get("status").asText()).isEqualTo("PENDING_VERIFICATION");
        return body(result).get("reference").asText();
    }

    protected String txStatus(String transactionReference) {
        return jdbc.queryForObject("select status from bank_transaction where reference = ?", String.class, transactionReference);
    }

    protected String txStatusReason(String transactionReference) {
        return jdbc.queryForObject("select status_reason from bank_transaction where reference = ?", String.class, transactionReference);
    }

    protected String alertStatusOf(String transactionReference) {
        return jdbc.queryForObject(
                "select a.status from fraud_alert a join bank_transaction t on a.transaction_id = t.id where t.reference = ?",
                String.class, transactionReference);
    }

    protected String caseReferenceFor(String transactionReference) {
        return jdbc.queryForObject(
                "select c.reference from fraud_case c join fraud_alert a on c.alert_id = a.id "
                        + "join bank_transaction t on a.transaction_id = t.id where t.reference = ?",
                String.class, transactionReference);
    }

    protected String caseStatus(String caseReference) {
        return jdbc.queryForObject("select status from fraud_case where reference = ?", String.class, caseReference);
    }

    protected int auditRows(String action, String entityReference) {
        return jdbc.queryForObject(
                "select count(*) from audit_log where action = ? and entity_reference = ?", Integer.class, action, entityReference);
    }

    protected MvcResult call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String token, Object json)
            throws Exception {
        var builder = request.header(HttpHeaders.AUTHORIZATION, token);
        if (json != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(json));
        }
        return mockMvc.perform(builder).andReturn();
    }

    protected String analystToken() throws Exception {
        return login("analyst.fraud", "Analyst@123");
    }

    /** A throw-away fraud analyst, for tests whose assertions depend on per-user counters. */
    protected String newAnalystToken() throws Exception {
        String username = "cc.analyst." + Long.toString(System.nanoTime(), 36);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ApplicationUser user = new ApplicationUser();
            user.setUsername(username);
            user.setPasswordHash(passwordEncoder.encode(PASSWORD));
            user.setFullName("Committed Analyst");
            user.setEmail(username + "@demobank.example");
            user.setEnabled(true);
            user.setRoles(new LinkedHashSet<>(Set.of(roles.findByName(RoleName.FRAUD_ANALYST).orElseThrow())));
            users.save(user);
        });
        return login(username, PASSWORD);
    }
}