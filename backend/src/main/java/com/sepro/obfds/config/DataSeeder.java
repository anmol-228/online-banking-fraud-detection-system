package com.sepro.obfds.config;

import com.sepro.obfds.entity.Account;
import com.sepro.obfds.entity.AccountType;
import com.sepro.obfds.entity.ApplicationUser;
import com.sepro.obfds.entity.Beneficiary;
import com.sepro.obfds.entity.Customer;
import com.sepro.obfds.entity.RoleName;
import com.sepro.obfds.entity.UserRole;
import com.sepro.obfds.repository.AccountRepository;
import com.sepro.obfds.repository.ApplicationUserRepository;
import com.sepro.obfds.repository.BeneficiaryRepository;
import com.sepro.obfds.repository.CustomerRepository;
import com.sepro.obfds.repository.UserRoleRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the roles the application needs, and an optional fictional demo dataset.
 *
 * <p>Every name, account number and password below is invented for this simulation. No
 * real customer information or real banking credential appears anywhere in this project. The demo
 * passwords are listed in {@code docs/16_DEMO_GUIDE.md} because a demonstration would be
 * impossible without them.</p>
 *
 * <p>Seeding is skipped when users already exist, so restarting the application against a MySQL
 * database does not duplicate the dataset.</p>
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private static final String DEMO_CUSTOMER_PASSWORD = "Customer@123";
    private static final String DEMO_ADMIN_PASSWORD = "Admin@123";
    private static final String DEMO_ANALYST_PASSWORD = "Analyst@123";
    private static final String DEMO_OFFICER_PASSWORD = "Officer@123";
    private static final String DEMO_SYSADMIN_PASSWORD = "SysAdmin@123";

    private final ApplicationUserRepository userRepository;
    private final UserRoleRepository roleRepository;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    public DataSeeder(
            ApplicationUserRepository userRepository,
            UserRoleRepository roleRepository,
            CustomerRepository customerRepository,
            AccountRepository accountRepository,
            BeneficiaryRepository beneficiaryRepository,
            PasswordEncoder passwordEncoder,
            AppProperties appProperties) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.passwordEncoder = passwordEncoder;
        this.appProperties = appProperties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedRoles();

        if (!appProperties.isSeedDemoData()) {
            log.info("Demo data seeding is disabled (obfds.seed-demo-data=false).");
            return;
        }
        if (userRepository.count() > 0) {
            log.info("Users already exist, skipping demo data seeding.");
            return;
        }
        seedDemoData();
    }

    /** Roles are reference data and must exist in every environment. */
    private void seedRoles() {
        createRoleIfMissing(RoleName.CUSTOMER, "Bank customer using online banking services");
        createRoleIfMissing(RoleName.BANK_ADMIN, "Bank administrator managing users and roles");
        createRoleIfMissing(RoleName.FRAUD_ANALYST, "Fraud analyst reviewing alerts and cases");
        createRoleIfMissing(RoleName.OPS_OFFICER, "Bank operations officer handling disputes");
        createRoleIfMissing(RoleName.SYSTEM_ADMIN, "System administrator responsible for operations");
    }

    private void createRoleIfMissing(RoleName name, String description) {
        roleRepository.findByName(name).orElseGet(() -> roleRepository.save(new UserRole(name, description)));
    }

    private void seedDemoData() {
        log.info("Seeding fictional demo data for the Online Banking and Fraud Detection System.");

        // ---- Bank staff -------------------------------------------------
        createStaffUser("admin.bank", DEMO_ADMIN_PASSWORD, "Priya Deshmukh",
                "priya.deshmukh@demobank.example", RoleName.BANK_ADMIN);
        createStaffUser("analyst.fraud", DEMO_ANALYST_PASSWORD, "Arjun Mehta",
                "arjun.mehta@demobank.example", RoleName.FRAUD_ANALYST);
        createStaffUser("ops.officer", DEMO_OFFICER_PASSWORD, "Kavya Iyer",
                "kavya.iyer@demobank.example", RoleName.OPS_OFFICER);
        createStaffUser("sys.admin", DEMO_SYSADMIN_PASSWORD, "Rohit Verma",
                "rohit.verma@demobank.example", RoleName.SYSTEM_ADMIN);

        // ---- Customers --------------------------------------------------
        Customer ravi = createCustomer(
                "ravi.kumar", DEMO_CUSTOMER_PASSWORD, "Ravi Kumar", "ravi.kumar@demomail.example",
                "CUST10000001", "9876500011", "12 MG Road, Pune, Maharashtra");
        Account raviSavings = createAccount(ravi, "900000000001", AccountType.SAVINGS, "150000.00");
        createAccount(ravi, "900000000002", AccountType.CURRENT, "60000.00");

        Customer meera = createCustomer(
                "meera.nair", DEMO_CUSTOMER_PASSWORD, "Meera Nair", "meera.nair@demomail.example",
                "CUST10000002", "9876500022", "44 Residency Road, Bengaluru, Karnataka");
        Account meeraSavings = createAccount(meera, "900000000003", AccountType.SAVINGS, "120000.00");

        // ---- Beneficiaries ----------------------------------------------
        // These are backdated so that they do NOT trigger the new-beneficiary rule. A payee added
        // during the demonstration will trigger it, which is what makes the rule easy to show.
        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);

        createBeneficiary(ravi, "Meera Nair", meeraSavings.getAccountNumber(),
                "Demo Bank", "DEMO0000123", "Sister", thirtyDaysAgo);
        createBeneficiary(ravi, "Anita Sharma", "500000000009",
                "Example Bank", "EXMP0000456", "Landlord", thirtyDaysAgo);
        createBeneficiary(meera, "Ravi Kumar", raviSavings.getAccountNumber(),
                "Demo Bank", "DEMO0000123", "Brother", thirtyDaysAgo);

        log.info("Demo data seeded: {} users, {} customers, {} accounts.",
                userRepository.count(), customerRepository.count(), accountRepository.count());
    }

    private void createStaffUser(
            String username, String rawPassword, String fullName, String email, RoleName roleName) {

        UserRole role = roleRepository
                .findByName(roleName)
                .orElseThrow(() -> new IllegalStateException("Role missing: " + roleName));

        ApplicationUser user = new ApplicationUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setFullName(fullName);
        user.setEmail(email);
        user.setEnabled(true);
        user.setRoles(new LinkedHashSet<>(Set.of(role)));
        userRepository.save(user);
    }

    private Customer createCustomer(
            String username,
            String rawPassword,
            String fullName,
            String email,
            String customerNumber,
            String phone,
            String address) {

        UserRole role = roleRepository
                .findByName(RoleName.CUSTOMER)
                .orElseThrow(() -> new IllegalStateException("CUSTOMER role missing"));

        ApplicationUser user = new ApplicationUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setFullName(fullName);
        user.setEmail(email);
        user.setEnabled(true);
        user.setRoles(new LinkedHashSet<>(Set.of(role)));
        userRepository.save(user);

        Customer customer = new Customer();
        customer.setCustomerNumber(customerNumber);
        customer.setFullName(fullName);
        customer.setEmail(email);
        customer.setPhone(phone);
        customer.setAddress(address);
        customer.setUser(user);
        return customerRepository.save(customer);
    }

    private Account createAccount(
            Customer customer, String accountNumber, AccountType type, String balance) {

        Account account = new Account();
        account.setAccountNumber(accountNumber);
        account.setCustomer(customer);
        account.setAccountType(type);
        account.setBalance(new BigDecimal(balance));
        return accountRepository.save(account);
    }

    private void createBeneficiary(
            Customer customer,
            String name,
            String accountNumber,
            String bankName,
            String ifsc,
            String nickname,
            Instant createdAt) {

        Beneficiary beneficiary = new Beneficiary();
        beneficiary.setCustomer(customer);
        beneficiary.setName(name);
        beneficiary.setAccountNumber(accountNumber);
        beneficiary.setBankName(bankName);
        beneficiary.setIfscCode(ifsc);
        beneficiary.setNickname(nickname);
        beneficiary.setActive(true);
        beneficiary.setCreatedAt(createdAt);
        beneficiaryRepository.save(beneficiary);
    }
}
