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
 *
 * <p>D81 separates the two things the old model had merged: a <strong>customer class</strong>
 * ({@link SegmentSpec}) is the unit of economic reaction, a <strong>product</strong> is the unit of
 * competition. A product names the class it is made for, so an oil crisis reaches it through its
 * customers rather than through a per-product demand multiplier.
 */
public record Scenario(String key, Params params, List<ProductSpec> products,
                       List<ChannelSpec> channels, List<ProjectSpec> projects,
                       List<RoundSpec> rounds, List<SegmentSpec> segments, List<EventSpec> events) {

    public Optional<ProductSpec> product(String code) {
        return products.stream().filter(p -> p.code().equals(code)).findFirst();
    }

    public Optional<ChannelSpec> channel(String code) {
        return channels.stream().filter(c -> c.code().equals(code)).findFirst();
    }

    public Optional<ProjectSpec> project(String code) {
        return projects.stream().filter(p -> p.code().equals(code)).findFirst();
    }

    public Optional<SegmentSpec> segment(String code) {
        return segments.stream().filter(s -> s.code().equals(code)).findFirst();
    }

    /** The class a product is made for, or empty for a legacy product with its own demand pool. */
    public Optional<SegmentSpec> segmentOf(ProductSpec product) {
        return product.targetSegment() == null ? Optional.empty() : segment(product.targetSegment());
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
                         Map<String, BigDecimal> salaries, Map<String, Integer> startingStaff,
                         BigDecimal marketReportCost, int forecastHorizon, BigDecimal forecastBand,
                         int cycles, int cycleLength, BigDecimal cycleDemandDrift,
                         BigDecimal cycleCostDrift,
                         /** D15: off unless the template turns it on, so a copy keeps its behaviour. */
                         boolean qualityUpgrade, BigDecimal qualityCostPerPoint,
                         /** D48: auto workers are a per-template mechanic, off unless turned on. */
                         boolean autoWorkers,
                         /** D61: how many opening rounds come with a suggested plan, 0 = none. */
                         int guidedRounds,
                         /** D79: stock is charged at what it cost (weighted average) rather than at
                          *  this month's replacement cost. Off = the pre-D79 behaviour. */
                         boolean weightedCosting) {
    }

    /**
     * The economy for one cycle position. Missing positions fall back to neutral (1.0).
     *
     * <p>{@code description}/{@code descriptionZh} are the published narrative a player reads in the
     * workbench (D83); they never carry mechanics the forecast does not already show.
     */
    public record RoundSpec(int round, BigDecimal demandIndex, BigDecimal costIndex,
                            BigDecimal wageIndex, BigDecimal trendStrength, String trendLabel,
                            String note, String description, String descriptionZh, String labelZh) {
    }

    /**
     * A card in the event deck. Whether it fires, and when inside its window, is drawn per run from
     * a seed — unpredictable to trainees, fixed and replayable for the facilitator.
     *
     * <p>The card itself names no product: it moves each {@link EventEffect affected class}, and the
     * products that serve that class inherit the change (D82). {@code demandMultiplier} and
     * {@code targetProduct} are the pre-D82 mechanism and remain only so an old template keeps its
     * numbers; the shipped deck sets the multiplier to 1.000 and leaves the target null.
     */
    public record EventSpec(String code, String name, String description, String category,
                            int earliestRound, int latestRound, BigDecimal probability,
                            int durationMonths, BigDecimal demandMultiplier,
                            BigDecimal costMultiplier, BigDecimal wageMultiplier,
                            String targetProduct, String nameZh, String descriptionZh,
                            List<EventEffect> effects) {
    }

    /** One event's effect on one customer class: how many buyers there are, and what they can spend. */
    public record EventEffect(String segmentCode, BigDecimal sizeMultiplier,
                              BigDecimal purchasingPowerMultiplier) {
    }

    /**
     * A class of potential customer (D81).
     *
     * <p>{@code baseSize} is units per round for a ten-company run, {@code basePurchasingPower} an
     * index (1.000 = the class affords that base size at the reference price). Both move with the
     * economy: the demand index and trend hit the size, events hit either number.
     */
    public record SegmentSpec(String code, String name, String nameZh, String description,
                              String descriptionZh, BigDecimal baseSize,
                              BigDecimal basePurchasingPower, BigDecimal incomeElasticity,
                              BigDecimal trendAffinity, int sortOrder) {
    }

    public record ProductSpec(String code, String name, BigDecimal basePrice, BigDecimal baseUnitCost,
                              BigDecimal baseQuality, BigDecimal baseDemand, BigDecimal unitsPerWorker,
                              BigDecimal incomeElasticity, BigDecimal trendAffinity,
                              String targetSegment, BigDecimal crossSegmentPct) {
    }

    public record ChannelSpec(String code, String name, BigDecimal reachWeight, BigDecimal brandAlpha) {
    }

    public record ProjectSpec(String code, String name, String lang, String targetProduct,
                              String advantageType,
                              BigDecimal advantageValue, BigDecimal hiddenCost, BigDecimal hintBand,
                              int durationMonths, boolean partialBenefit, String benefitCurve,
                              BigDecimal benefitExponent, String overfunding) {

        public boolean processWide() {
            return targetProduct == null || targetProduct.isBlank();
        }

        public boolean refundsOverfunding() {
            return !"LOST".equals(overfunding);
        }
    }
}
