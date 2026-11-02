package com.bizsim.sim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * One company's monthly submission (ruling D1: one decision per company per month, not per role).
 *
 * <p>Field names are the API contract. Unknown properties are rejected at deserialisation so a typo
 * in a form field is a 400 instead of a silently ignored decision.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DecisionPayload(List<ProductRow> production,
                              List<PriceRow> price,
                              List<ChannelRow> marketing,
                              ManpowerRow manpower,
                              List<RndRow> rnd,
                              BigDecimal capacityPurchase,
                              Boolean marketReport) {

    public record ProductRow(String product, BigDecimal units) {
    }

    public record PriceRow(String product, BigDecimal unitPrice) {
    }

    public record ChannelRow(String channel, BigDecimal spend) {
    }

    /** D5: manpower deployed to production. Other functions are fixed in v1. */
    public record ManpowerRow(Integer production) {
    }

    public record RndRow(String project, BigDecimal invest) {
    }

    public static DecisionPayload none() {
        return new DecisionPayload(List.of(), List.of(), List.of(), null, List.of(), null, null);
    }

    public boolean marketReportRequested() {
        return Boolean.TRUE.equals(marketReport);
    }

    public BigDecimal units(String product) {
        return production == null ? BigDecimal.ZERO : production.stream()
                .filter(r -> product.equals(r.product()))
                .map(r -> zeroIfNull(r.units())).findFirst().orElse(BigDecimal.ZERO);
    }

    /** Null means "keep the current price" — only reachable for a team that submitted nothing. */
    public BigDecimal priceOf(String product) {
        return price == null ? null : price.stream()
                .filter(r -> product.equals(r.product()))
                .map(PriceRow::unitPrice).findFirst().orElse(null);
    }

    public BigDecimal spend(String channel) {
        return marketing == null ? BigDecimal.ZERO : marketing.stream()
                .filter(r -> channel.equals(r.channel()))
                .map(r -> zeroIfNull(r.spend())).findFirst().orElse(BigDecimal.ZERO);
    }

    public BigDecimal invest(String project) {
        return rnd == null ? BigDecimal.ZERO : rnd.stream()
                .filter(r -> project.equals(r.project()))
                .map(r -> zeroIfNull(r.invest())).findFirst().orElse(BigDecimal.ZERO);
    }

    public BigDecimal marketingTotal() {
        return marketing == null ? BigDecimal.ZERO
                : marketing.stream().map(r -> zeroIfNull(r.spend())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal rndTotal() {
        return rnd == null ? BigDecimal.ZERO
                : rnd.stream().map(r -> zeroIfNull(r.invest())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal capacityPurchaseOrZero() {
        return zeroIfNull(capacityPurchase);
    }

    public Optional<Integer> productionTarget() {
        return Optional.ofNullable(manpower).map(ManpowerRow::production);
    }

    private static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
