package com.bizsim.sim;

import com.bizsim.web.ApiProblem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shape and reference validation for a monthly submission. All errors are returned at once, never the
 * first one: a trainee with five form mistakes gets five messages, not five submit attempts.
 *
 * <p>Each failure is a **code plus params** (D23), not a sentence: the interface decides the wording
 * and the language. The English text is kept only as a diagnostic fallback.
 *
 * <p>Cash affordability is not checked here — it needs the company's full state, so it lives in
 * {@link ScoringEngine#preview} and is enforced by {@link RoundService} at submit time.
 */
@Component
public class DecisionValidator {

    public List<ApiProblem> validate(Scenario scenario, DecisionPayload payload,
                                     Map<String, ScoringEngine.RndState> rndState) {
        List<ApiProblem> problems = new ArrayList<>();
        if (payload == null) {
            return List.of(ApiProblem.of("decision.required", "decision payload is required"));
        }

        Set<String> productCodes = new HashSet<>(scenario.productsByCode().keySet());

        if (payload.production() == null || payload.production().isEmpty()) {
            problems.add(ApiProblem.of("production.required", "production: every product needs a volume, even 0"));
        } else {
            Set<String> seen = new HashSet<>();
            for (DecisionPayload.ProductRow row : payload.production()) {
                if (row.product() == null || !productCodes.contains(row.product())) {
                    problems.add(ApiProblem.of("production.unknown_product",
                            "production: unknown product " + row.product(), "product", nullToDash(row.product())));
                } else if (!seen.add(row.product())) {
                    problems.add(ApiProblem.of("production.duplicate_product",
                            "production: product " + row.product() + " appears more than once",
                            "product", row.product()));
                }
                if (row.units() == null || row.units().signum() < 0) {
                    problems.add(ApiProblem.of("production.units_negative",
                            "production." + row.product() + ": units must be >= 0",
                            "product", nullToDash(row.product())));
                }
            }
            for (String code : productCodes) {
                if (!seen.contains(code)) {
                    problems.add(ApiProblem.of("production.missing_product",
                            "production: missing product " + code, "product", code));
                }
            }
        }

        if (payload.price() == null || payload.price().isEmpty()) {
            problems.add(ApiProblem.of("price.required", "price: every product needs a selling price"));
        } else {
            Set<String> seen = new HashSet<>();
            for (DecisionPayload.PriceRow row : payload.price()) {
                if (row.product() == null || !productCodes.contains(row.product())) {
                    problems.add(ApiProblem.of("price.unknown_product",
                            "price: unknown product " + row.product(), "product", nullToDash(row.product())));
                } else if (!seen.add(row.product())) {
                    problems.add(ApiProblem.of("price.duplicate_product",
                            "price: product " + row.product() + " appears more than once",
                            "product", row.product()));
                }
                if (row.unitPrice() == null || row.unitPrice().signum() <= 0) {
                    problems.add(ApiProblem.of("price.not_positive",
                            "price." + row.product() + ": price must be > 0",
                            "product", nullToDash(row.product())));
                } else if (row.unitPrice().compareTo(ceiling(scenario, row.product())) > 0) {
                    // A price is the one submitted number cash does not bound. Without a ceiling it
                    // was stored (jsonb takes anything) and only failed later, when the round was
                    // computed and numeric(14,2) overflowed — leaving the round OPEN and uncomputable.
                    problems.add(ApiProblem.of("price.too_high",
                            "price." + row.product() + ": price must be at most "
                                    + ceiling(scenario, row.product()).toPlainString(),
                            "product", nullToDash(row.product())));
                }
            }
            for (String code : productCodes) {
                if (!seen.contains(code)) {
                    problems.add(ApiProblem.of("price.missing_product",
                            "price: missing product " + code, "product", code));
                }
            }
        }

        if (payload.marketing() != null) {
            Set<String> seen = new HashSet<>();
            for (DecisionPayload.ChannelRow row : payload.marketing()) {
                if (row.channel() == null || scenario.channel(row.channel()).isEmpty()) {
                    problems.add(ApiProblem.of("marketing.unknown_channel",
                            "marketing: unknown channel " + row.channel(),
                            "channel", nullToDash(row.channel())));
                } else if (!seen.add(row.channel())) {
                    problems.add(ApiProblem.of("marketing.duplicate_channel",
                            "marketing: channel " + row.channel() + " appears more than once",
                            "channel", row.channel()));
                }
                if (row.spend() == null || row.spend().signum() < 0) {
                    problems.add(ApiProblem.of("marketing.spend_negative",
                            "marketing." + row.channel() + ": spend must be >= 0",
                            "channel", nullToDash(row.channel())));
                }
            }
        }

        if (payload.manpower() != null && payload.manpower().production() != null) {
            int target = payload.manpower().production();
            if (target < 0 || target > 10_000) {
                problems.add(ApiProblem.of("manpower.out_of_range",
                        "manpower.production: must be between 0 and 10000", "max", 10_000));
            }
        }

        if (payload.capacityPurchase() != null && payload.capacityPurchase().signum() < 0) {
            problems.add(ApiProblem.of("capacity.negative", "capacityPurchase: must be >= 0"));
        }

        if (payload.rnd() != null) {
            Set<String> seen = new HashSet<>();
            for (DecisionPayload.RndRow row : payload.rnd()) {
                Scenario.ProjectSpec project = scenario.project(row.project()).orElse(null);
                if (project == null) {
                    problems.add(ApiProblem.of("rnd.unknown_project",
                            "rnd: unknown project " + row.project(), "project", nullToDash(row.project())));
                    continue;
                }
                if (!seen.add(row.project())) {
                    problems.add(ApiProblem.of("rnd.duplicate_project",
                            "rnd: project " + row.project() + " appears more than once",
                            "project", row.project()));
                }
                if (row.invest() == null || row.invest().signum() < 0) {
                    problems.add(ApiProblem.of("rnd.invest_negative",
                            "rnd." + row.project() + ": invest must be >= 0", "project", row.project()));
                }
                ScoringEngine.RndState state = rndState.get(row.project());
                // A finished project takes no more money -- but a zero row is not money. Rejecting the
                // row itself locked a class out entirely: the form sends every project every round, so
                // once one project completed, every submission failed with rnd.not_active.
                if (state != null && !"ACTIVE".equals(state.status()) && row.invest().signum() > 0) {
                    problems.add(ApiProblem.of("rnd.not_active",
                            "rnd." + row.project() + ": project is already " + state.status(),
                            "project", row.project(), "status", state.status()));
                }
            }
        }

        if (payload.quality() != null) {
            boolean enabled = scenario.params().qualityUpgrade();
            Set<String> seen = new HashSet<>();
            for (DecisionPayload.QualityRow row : payload.quality()) {
                if (row.product() == null || !productCodes.contains(row.product())) {
                    problems.add(ApiProblem.of("quality.unknown_product",
                            "quality: unknown product " + row.product(),
                            "product", nullToDash(row.product())));
                    continue;
                }
                if (!seen.add(row.product())) {
                    problems.add(ApiProblem.of("quality.duplicate_product",
                            "quality: product " + row.product() + " appears more than once",
                            "product", row.product()));
                }
                if (row.invest() == null || row.invest().signum() < 0) {
                    problems.add(ApiProblem.of("quality.invest_negative",
                            "quality." + row.product() + ": invest must be >= 0",
                            "product", row.product()));
                } else if (row.invest().signum() > 0 && !enabled) {
                    // D15: the mechanic is a per-template switch. Money sent to a template that does
                    // not sell it is a client bug, not a silent donation.
                    problems.add(ApiProblem.of("quality.disabled",
                            "quality: this template does not offer quality upgrades",
                            "product", row.product()));
                }
            }
        }

        return problems;
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    /**
     * The highest price a product may carry: a hundred times its catalogue price. Generous for any
     * elasticity experiment (demand collapses long before that) and well inside numeric(14,2), so a
     * submitted decision can always be computed.
     */
    private static java.math.BigDecimal ceiling(Scenario scenario, String code) {
        Scenario.ProductSpec spec = scenario.productsByCode().get(code);
        return spec == null ? java.math.BigDecimal.ZERO
                : spec.basePrice().multiply(new java.math.BigDecimal("100"));
    }
}
