package com.bizsim.sim;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shape and reference validation for a monthly submission. All errors are returned at once, never the
 * first one: a trainee with five form mistakes gets five messages, not five submit attempts.
 *
 * <p>Cash affordability is not checked here — it needs the company's full state, so it lives in
 * {@link ScoringEngine#preview} and is enforced by {@link RoundService} at submit time.
 */
@Component
public class DecisionValidator {

    public List<String> validate(Scenario scenario, DecisionPayload payload,
                                 Map<String, ScoringEngine.RndState> rndState) {
        List<String> errors = new ArrayList<>();
        if (payload == null) {
            return List.of("decision payload is required");
        }

        Set<String> productCodes = new HashSet<>(scenario.productsByCode().keySet());

        if (payload.production() == null || payload.production().isEmpty()) {
            errors.add("production: every product needs a volume, even 0");
        } else {
            Set<String> seen = new HashSet<>();
            for (DecisionPayload.ProductRow row : payload.production()) {
                if (row.product() == null || !productCodes.contains(row.product())) {
                    errors.add("production: unknown product " + row.product());
                } else if (!seen.add(row.product())) {
                    errors.add("production: product " + row.product() + " appears more than once");
                }
                if (row.units() == null || row.units().signum() < 0) {
                    errors.add("production." + row.product() + ": units must be >= 0");
                }
            }
            for (String code : productCodes) {
                if (!seen.contains(code)) {
                    errors.add("production: missing product " + code);
                }
            }
        }

        if (payload.price() == null || payload.price().isEmpty()) {
            errors.add("price: every product needs a selling price");
        } else {
            Set<String> seen = new HashSet<>();
            for (DecisionPayload.PriceRow row : payload.price()) {
                if (row.product() == null || !productCodes.contains(row.product())) {
                    errors.add("price: unknown product " + row.product());
                } else if (!seen.add(row.product())) {
                    errors.add("price: product " + row.product() + " appears more than once");
                }
                if (row.unitPrice() == null || row.unitPrice().signum() <= 0) {
                    errors.add("price." + row.product() + ": price must be > 0");
                }
            }
            for (String code : productCodes) {
                if (!seen.contains(code)) {
                    errors.add("price: missing product " + code);
                }
            }
        }

        if (payload.marketing() != null) {
            Set<String> seen = new HashSet<>();
            for (DecisionPayload.ChannelRow row : payload.marketing()) {
                if (row.channel() == null || scenario.channel(row.channel()).isEmpty()) {
                    errors.add("marketing: unknown channel " + row.channel());
                } else if (!seen.add(row.channel())) {
                    errors.add("marketing: channel " + row.channel() + " appears more than once");
                }
                if (row.spend() == null || row.spend().signum() < 0) {
                    errors.add("marketing." + row.channel() + ": spend must be >= 0");
                }
            }
        }

        if (payload.manpower() != null && payload.manpower().production() != null) {
            int target = payload.manpower().production();
            if (target < 0 || target > 10_000) {
                errors.add("manpower.production: must be between 0 and 10000");
            }
        }

        if (payload.capacityPurchase() != null && payload.capacityPurchase().signum() < 0) {
            errors.add("capacityPurchase: must be >= 0");
        }

        if (payload.rnd() != null) {
            Set<String> seen = new HashSet<>();
            for (DecisionPayload.RndRow row : payload.rnd()) {
                Scenario.ProjectSpec project = scenario.project(row.project()).orElse(null);
                if (project == null) {
                    errors.add("rnd: unknown project " + row.project());
                    continue;
                }
                if (!seen.add(row.project())) {
                    errors.add("rnd: project " + row.project() + " appears more than once");
                }
                if (row.invest() == null || row.invest().signum() < 0) {
                    errors.add("rnd." + row.project() + ": invest must be >= 0");
                }
                ScoringEngine.RndState state = rndState.get(row.project());
                if (state != null && !"ACTIVE".equals(state.status())) {
                    errors.add("rnd." + row.project() + ": project is already " + state.status());
                }
            }
        }

        return errors;
    }
}
