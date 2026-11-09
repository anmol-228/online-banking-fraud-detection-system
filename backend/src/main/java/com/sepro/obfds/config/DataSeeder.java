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

    /** The five passwords a seed run needs, one per kind of user. */
    private record SeedPasswords(String admin, String analyst, String officer, String sysadmin, String customer) {
        static SeedPasswords published() {
            return new SeedPasswords(DEMO_ADMIN_PASSWORD, DEMO_ANALYST_PASSWORD, DEMO_OFFICER_PASSWORD,
                    DEMO_SYSADMIN_PASSWORD, DEMO_CUSTOMER_PASSWORD);
        }
    }

    /**
     * Seeds according to {@code obfds.seed.mode}:
     * <ul>
     *   <li>{@code demo-fixed} - the synthetic dataset with the passwords published in the
     *       repository. For an isolated local run only; {@link StartupSafetyGuard} refuses it
     *       anywhere reachable from outside.</li>
     *   <li>{@code bootstrap-env} - the same synthetic dataset, with passwords taken from the
     *       environment, so a public deployment has no password anyone can read in the source.</li>
     *   <li>{@code none} - reference roles only.</li>
     * </ul>
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedRoles();

        String mode = appProperties.isSeedDemoData()
                ? appProperties.getSeed().getMode().toLowerCase(java.util.Locale.ROOT)
                : "none";
        if (mode.equals("none")) {
            log.info("User seeding is disabled (seed mode none).");
            return;
        }
        if (userRepository.count() > 0) {
            log.info("Users already exist, skipping demo data seeding.");
            return;
        }
        SeedPasswords passwords = switch (mode) {
            case "demo-fixed" -> SeedPasswords.published();
            case "bootstrap-env" -> bootstrapPasswords();
            default -> throw new IllegalStateException("Unknown seed mode: " + mode);
        };
        seedDemoData(passwords);
    }

    private SeedPasswords bootstrapPasswords() {
        AppProperties.Seed.Bootstrap env = appProperties.getSeed().getBootstrap();
        SeedPasswords passwords = new SeedPasswords(
                env.getAdminPassword(), env.getAnalystPassword(), env.getOfficerPassword(),
                env.getSysadminPassword(), env.getCustomerPassword());
        // The startup guard has already checked these; the seeder repeats the check because it is
        // the last thing standing between an empty password and a real account.
        for (String password : new String[] {
            passwords.admin(), passwords.analyst(), passwords.officer(), passwords.sysadmin(), passwords.customer()}) {
            if (password == null || password.length() < 12 || StartupSafetyGuard.PUBLISHED_DEMO_PASSWORDS.contains(password)) {
                throw new IllegalStateException(
                        "Seed mode bootstrap-env requires five strong passwords of at least 12 characters in "
                                + "obfds.seed.bootstrap.*; none may be a published demo password.");
            }
        }
        return passwords;
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

    private void seedDemoData(SeedPasswords passwords) {
        log.info("Seeding fictional demo data for the Online Banking and Fraud Detection System.");

        // ---- Bank staff -------------------------------------------------
        createStaffUser("admin.bank", passwords.admin(), "Priya Deshmukh",
                "priya.deshmukh@demobank.example", RoleName.BANK_ADMIN);
        createStaffUser("analyst.fraud", passwords.analyst(), "Arjun Mehta",
                "arjun.mehta@demobank.example", RoleName.FRAUD_ANALYST);
        createStaffUser("ops.officer", passwords.officer(), "Kavya Iyer",
                "kavya.iyer@demobank.example", RoleName.OPS_OFFICER);
        createStaffUser("sys.admin", passwords.sysadmin(), "Rohit Verma",
                "rohit.verma@demobank.example", RoleName.SYSTEM_ADMIN);

        // ---- Customers --------------------------------------------------
        Customer ravi = createCustomer(
                "ravi.kumar", passwords.customer(), "Ravi Kumar", "ravi.kumar@demomail.example",
                "CUST10000001", "9876500011", "12 MG Road, Pune, Maharashtra");
        Account raviSavings = createAccount(ravi, "900000000001", AccountType.SAVINGS, "150000.00");
        createAccount(ravi, "900000000002", AccountType.CURRENT, "60000.00");

        Customer meera = createCustomer(
                "meera.nair", passwords.customer(), "Meera Nair", "meera.nair@demomail.example",
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
