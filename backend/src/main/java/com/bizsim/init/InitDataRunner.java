package com.bizsim.init;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs {@link InitDataService} at boot when a tenant is named, and does nothing otherwise.
 *
 * <pre>
 *   BIZSIM_INIT_TENANT     tenant code to create, e.g. demo — unset means "off"
 *   BIZSIM_INIT_NAME       tenant display name (default: the code)
 *   BIZSIM_INIT_COMPANIES  companies in the class (default 4, 1-6)
 *   BIZSIM_INIT_TRAINEES   trainees seated (default 4, 1-companies)
 *   BIZSIM_INIT_ROUNDS     rounds played to a result (default 8; 0 = create but do not play)
 *   BIZSIM_INIT_CYCLES     cycles for the session (default: the template's)
 *   BIZSIM_INIT_LOCALE     class language, en or zh (default en)
 *   BIZSIM_INIT_PASSWORD   credential for every seeded account (default is the documented demo one)
 *   BIZSIM_INIT_PACKAGE    package name (default "Demo programme &lt;code&gt;")
 *   BIZSIM_INIT_RUN        session name (default "Demo class &lt;code&gt;")
 * </pre>
 *
 * <p>Fail-soft on purpose: a seeder that throws at startup turns a working deployment into a crash
 * loop. A failure here is logged with its reason and the application continues — the tenant simply
 * has not been created, and the admin endpoint can retry once the cause is fixed.
 *
 * <p>Idempotent by contract, not by hope: the service refuses a tenant that already has a package, so
 * leaving these variables set on a live deployment cannot create a second class or reset a password.
 */
@Component
public class InitDataRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitDataRunner.class);

    private final InitDataService initData;

    private final String tenant;
    private final String tenantName;
    private final int companies;
    private final int trainees;
    private final int rounds;
    private final int cycles;
    private final String locale;
    private final String password;
    private final String packageName;
    private final String runName;

    public InitDataRunner(InitDataService initData,
                          @Value("${BIZSIM_INIT_TENANT:}") String tenant,
                          @Value("${BIZSIM_INIT_NAME:}") String tenantName,
                          @Value("${BIZSIM_INIT_COMPANIES:4}") int companies,
                          @Value("${BIZSIM_INIT_TRAINEES:4}") int trainees,
                          @Value("${BIZSIM_INIT_ROUNDS:8}") int rounds,
                          @Value("${BIZSIM_INIT_CYCLES:0}") int cycles,
                          @Value("${BIZSIM_INIT_LOCALE:en}") String locale,
                          @Value("${BIZSIM_INIT_PASSWORD:}") String password,
                          @Value("${BIZSIM_INIT_PACKAGE:}") String packageName,
                          @Value("${BIZSIM_INIT_RUN:}") String runName) {
        this.initData = initData;
        this.tenant = tenant;
        this.tenantName = tenantName;
        this.companies = companies;
        this.trainees = trainees;
        this.rounds = rounds;
        this.cycles = cycles;
        this.locale = locale;
        this.password = password;
        this.packageName = packageName;
        this.runName = runName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (tenant == null || tenant.isBlank()) {
            return;
        }
        try {
            InitDataService.Summary summary = initData.init(tenant,
                    new InitDataService.Params(companies, trainees, rounds,
                            cycles > 0 ? cycles : null, locale, password, tenantName,
                            packageName, runName));
            log.info("init data: sign in as {}@{} (password from BIZSIM_INIT_PASSWORD, or the "
                            + "documented demo one) — package '{}', session {} {}",
                    "admin", summary.tenantCode(), summary.packageName(), summary.runId(),
                    summary.runStatus());
        } catch (RuntimeException e) {
            log.error("init data: tenant '{}' was not created: {}", tenant, e.getMessage());
        }
    }
}
