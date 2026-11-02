package com.bizsim.sim;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The scenario as data (decision 9, ruling D16).
 *
 * <p>Every number a trainer may want to tune lives here or in the catalogue tables — elasticities,
 * salaries, decay, holding cost. None of it is a Java constant, and nothing in the engine assumes a
 * fixed number of products: a new product is a row, not a code change (D15, consequence 9).
 */
public record Scenario(String key, Params params, List<ProductSpec> products,
                       List<ChannelSpec> channels, List<ProjectSpec> projects) {

    public Optional<ProductSpec> product(String code) {
        return products.stream().filter(p -> p.code().equals(code)).findFirst();
    }

    public Optional<ChannelSpec> channel(String code) {
        return channels.stream().filter(c -> c.code().equals(code)).findFirst();
    }

    public Optional<ProjectSpec> project(String code) {
        return projects.stream().filter(p -> p.code().equals(code)).findFirst();
    }

    public Map<String, ProductSpec> productsByCode() {
        return products.stream().collect(Collectors.toMap(ProductSpec::code, p -> p));
    }

    /** Seasonality is indexed by round 1..n and wraps if a run is longer than the list. */
    public BigDecimal seasonality(int round) {
        List<BigDecimal> s = params.seasonality();
        if (s.isEmpty()) {
            return BigDecimal.ONE;
        }
        return s.get(Math.floorMod(round - 1, s.size()));
    }

    public record Params(int rounds, int roundMinutes, BigDecimal startingCash, BigDecimal startingBrand,
                         BigDecimal fixedCostPerMonth, BigDecimal brandDecay, BigDecimal holdingCostPerUnit,
                         BigDecimal referenceQuality, BigDecimal referenceSpend,
                         BigDecimal machineCostPerWorker, BigDecimal hiringCostMonths,
                         BigDecimal severanceMonths, BigDecimal minRndBenefitRatio,
                         BigDecimal baselineShare, List<BigDecimal> seasonality,
                         BigDecimal priceElasticity, BigDecimal qualityElasticity,
                         BigDecimal brandElasticity, BigDecimal marketingElasticity,
                         Map<String, BigDecimal> salaries, Map<String, Integer> startingStaff) {
    }

    public record ProductSpec(String code, String name, BigDecimal basePrice, BigDecimal baseUnitCost,
                              BigDecimal baseQuality, BigDecimal baseDemand, BigDecimal unitsPerWorker) {
    }

    public record ChannelSpec(String code, String name, BigDecimal reachWeight, BigDecimal brandAlpha) {
    }

    public record ProjectSpec(String code, String name, String targetProduct, String advantageType,
                              BigDecimal advantageValue, BigDecimal hiddenCost, BigDecimal hintBand,
                              int durationMonths) {

        public boolean processWide() {
            return targetProduct == null || targetProduct.isBlank();
        }
    }
}
