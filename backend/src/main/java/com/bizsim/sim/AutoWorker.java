package com.bizsim.sim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An auto worker's judgement (D47–D50): a policy, not a person.
 *
 * <p>It is a pure function of what a trainee could see at the same moment — the company's own state,
 * last month's own lines, and the published environment indices — plus the template's public
 * catalogue. It never reads another company's decision, the event deck, or a project's hidden cost
 * (it uses the same <em>hint band</em> a trainee is shown). That is what makes "same rules" true by
 * construction rather than by promise.
 *
 * <p><b>Level</b> is how well it plays: forecast blend, noise, how early it invests. <b>Personality</b>
 * is what it wants: price posture, share target, marketing appetite. Level × personality is the whole
 * matrix — nine shipped combinations, and a profile row can override any of the numbers.
 *
 * <p>Determinism: every draw comes from {@link Seed} keyed on run, round, company and product, so a
 * replayed month produces the identical decision.
 */
public final class AutoWorker {

    /**
     * The knobs, all of them numbers a trainer can tune without a code change.
     *
     * <p>D128: {@code strategy} is the competitive strategy (Porter) and {@code level} is how well the company
     * executes it — two independent axes, so "a cost leader that plays badly" is a profile rather than a
     * contradiction. The levers that make the strategies different are all here as data:
     * {@code capacityBias} (how much of the free cash goes into machines), {@code channelBias} (spend the
     * channel that reaches your class harder than the rest), {@code rndAppetite} and {@code qualityAppetite}
     * (independent of level), and {@code lineLimit} (produce only the products aimed at your class).
     */
    public record Profile(String key, String level, String personality, String strategy,
                          BigDecimal targetShare, BigDecimal pricePosture, BigDecimal forecastBlend,
                          BigDecimal demandNoise, BigDecimal marketingRate,
                          boolean buysMarketReview, boolean investsInRnd, boolean buysQuality,
                          BigDecimal cashBufferMonths,
                          BigDecimal capacityBias, BigDecimal channelBias,
                          BigDecimal rndAppetite, BigDecimal qualityAppetite, int lineLimit) {

        /** The pre-D128 shape, kept so callers that only know level × personality still compile. */
        public Profile(String key, String level, String personality, BigDecimal targetShare,
                       BigDecimal pricePosture, BigDecimal forecastBlend, BigDecimal demandNoise,
                       BigDecimal marketingRate, boolean buysMarketReview, boolean investsInRnd,
                       boolean buysQuality, BigDecimal cashBufferMonths) {
            this(key, level, personality, DEFAULT_STRATEGY, targetShare, pricePosture,
                    forecastBlend, demandNoise, marketingRate, buysMarketReview, investsInRnd,
                    buysQuality, cashBufferMonths, BigDecimal.ZERO, BigDecimal.ONE,
                    BigDecimal.ONE, BigDecimal.ONE, 0);
        }

        /** True when this profile narrows its line to the products aimed at one customer class. */
        public boolean focused() {
            return lineLimit > 0;
        }
    }

    /**
     * D130: personality is retired. It used to imply a strategy when a client sent none; there is no such
     * default now — a profile without a strategy is a contradiction, and the caller must name one.
     */
    public static final String DEFAULT_STRATEGY = "COST";

    /** Everything the policy is allowed to know. Built by the loader, or by a test harness. */
    public record Context(int round, int totalRounds, BigDecimal cash, BigDecimal brand,
                          BigDecimal machineWorkers, Map<String, Integer> staff,
                          Map<String, ScoringEngine.ProductState> products,
                          Map<String, ScoringEngine.RndState> rnd,
                          List<ScoringEngine.ProductLine> lastLines,
                          EnvironmentService.RoundEnvironment environment) {
    }

    public record Decision(DecisionPayload payload, String rationale, String seedKey) {
    }

    private AutoWorker() {
    }

    // ------------------------------------------------------------------ profiles

    /**
     * The shipped matrix: three strategies × three levels (D128).
     *
     * <p>Level is competence and stays where it was: the forecast's honesty, whether the review is bought, how
     * much of a plan it dares to make. Strategy is what the company is trying to be, and it moves the levers
     * in different directions rather than by different amounts — a cost leader buys machines and keeps quality
     * flat, a differentiator buys quality and R&amp;D and charges for it, a focus player narrows to the product
     * and channel that serve its class.
     */
    /**
     * D129: the neutral profile — a baseline plan with no strategic commitment.
     *
     * <p>The guided suggestion for a human seat used to be `profile("MID", "BALANCED")`, which before D128 was
     * the neutral cell and after D128 silently meant **FOCUS**: a suggestion that concentrated marketing on one
     * class's channel and planned only that class's product, for a company the guide knows nothing about. A
     * suggestion is not a strategy. This profile sets every strategy lever to its neutral value — price at the
     * template's, share at the market's, marketing pro rata to channel reach, the whole line planned, no capacity
     * race, quality and R&D untouched — and keeps MID's competence so the numbers are honest but unremarkable.
     */
    public static Profile neutral() {
        return new Profile(
                "neutral", "MID", "BALANCED", "NEUTRAL",
                new BigDecimal("1.00"), BigDecimal.ONE,
                new BigDecimal("0.70"), new BigDecimal("0.14"), new BigDecimal("0.020"),
                false,                 // a suggestion does not spend the company's money on a review
                false,                 // nor commit it to an R&D programme the trainee never chose
                false,                 // nor buy quality
                new BigDecimal("2"),
                BigDecimal.ZERO,       // no capacity race
                BigDecimal.ONE,        // marketing pro rata to reach
                BigDecimal.ONE, BigDecimal.ONE,
                0);                    // the whole line, not one class's product
    }

    /** D130: the profile for a strategy at a level of competence — the whole matrix, now nine cells. */
    public static Profile profile(String level, String strategy) {
        BigDecimal[] byLevel = switch (level) {
            case "HIGH" -> new BigDecimal[]{new BigDecimal("0.34"), new BigDecimal("1.00"),
                    new BigDecimal("0.04"), new BigDecimal("0.035")};
            case "MID" -> new BigDecimal[]{new BigDecimal("0.30"), new BigDecimal("0.70"),
                    new BigDecimal("0.14"), new BigDecimal("0.020")};
            default -> new BigDecimal[]{new BigDecimal("0.25"), new BigDecimal("0.35"),
                    new BigDecimal("0.28"), new BigDecimal("0.008")};
        };
        // D130: the strategy alone now sets price and share. Personality used to move them on top, which made
        // some cells contradictory — COST + AGGRESSIVE double-undercut, DIFFERENTIATION + AGGRESSIVE resisted its
        // own premium — so the axis is retired and its character is absorbed by the strategy's bias below.
        BigDecimal posture = BigDecimal.ONE;
        BigDecimal share = BigDecimal.ONE;
        boolean high = "HIGH".equals(level);
        boolean mid = "MID".equals(level);

        // D128: the strategy re-states the two knobs the personality used to own, then adds the levers the old
        // matrix had no place for. Level still gates *whether* the company can afford a long game (R&D needs
        // free cash), but it no longer decides *whether it wants one* — that is the strategy's job.
        BigDecimal shareBias;
        BigDecimal postureBias;
        BigDecimal capacityBias;
        BigDecimal channelBias;
        BigDecimal rndAppetite;
        BigDecimal qualityAppetite;
        int lineLimit;
        switch (strategy) {
            case "DIFFERENTIATION" -> {
                // Premium price, defensible by product rather than by volume: quality and R&D, no machine race.
                shareBias = new BigDecimal("0.98");
                postureBias = new BigDecimal("1.06");   // charges ~6% over the template
                capacityBias = new BigDecimal("0.25");
                channelBias = BigDecimal.ONE;
                rndAppetite = new BigDecimal("1.35");
                qualityAppetite = new BigDecimal("1.60");
                lineLimit = 0;
            }
            case "FOCUS" -> {
                // One class, served properly: the product aimed at it and the channel that reaches it.
                shareBias = new BigDecimal("1.04");
                postureBias = new BigDecimal("1.02");
                capacityBias = new BigDecimal("0.35");
                channelBias = new BigDecimal("1.80");
                rndAppetite = new BigDecimal("0.90");
                qualityAppetite = new BigDecimal("1.20");
                lineLimit = 1;
            }
            default -> {
                // COST: price and volume, with the cost structure to justify it.
                shareBias = new BigDecimal("1.12");
                postureBias = new BigDecimal("0.94");
                capacityBias = new BigDecimal("1.00");
                channelBias = BigDecimal.ONE;
                rndAppetite = new BigDecimal("0.90");
                qualityAppetite = new BigDecimal("0.35");
                lineLimit = 0;
            }
        }
        return new Profile(
                // D130: the key names the combination that still exists — strategy and level.
                strategy.toLowerCase(java.util.Locale.ROOT) + "_" + level.toLowerCase(java.util.Locale.ROOT),
                level, null, strategy,
                share.multiply(shareBias), posture.multiply(postureBias),
                byLevel[1], byLevel[2], byLevel[3],
                mid || high,          // the review pays for itself once the forecast is used properly
                // D130: R&D is the differentiator's game. It was `high`, which made a HIGH cost leader spend on
                // a programme while buying no quality at all — it paid for the long game and shipped a product
                // nobody preferred, and the self-play calibration showed it losing money while MID prospered.
                strategy.equals("DIFFERENTIATION"),
                strategy.equals("DIFFERENTIATION"),
                new BigDecimal("2"),
                capacityBias, channelBias, rndAppetite, qualityAppetite, lineLimit);
    }

    /** A profile row's params override the shipped numbers, so tuning needs no release. */
    public static Profile withParams(Profile base, Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return base;
        }
        return new Profile(
                base.key(), base.level(), base.personality(), base.strategy(),
                num(params, "targetShare", base.targetShare()),
                num(params, "pricePosture", base.pricePosture()),
                num(params, "forecastBlend", base.forecastBlend()),
                num(params, "demandNoise", base.demandNoise()),
                num(params, "marketingRate", base.marketingRate()),
                bool(params, "buysMarketReview", base.buysMarketReview()),
                bool(params, "investsInRnd", base.investsInRnd()),
                bool(params, "buysQuality", base.buysQuality()),
                num(params, "cashBufferMonths", base.cashBufferMonths()),
                num(params, "capacityBias", base.capacityBias()),
                num(params, "channelBias", base.channelBias()),
                num(params, "rndAppetite", base.rndAppetite()),
                num(params, "qualityAppetite", base.qualityAppetite()),
                (int) num(params, "lineLimit", BigDecimal.valueOf(base.lineLimit())).intValue());
    }

    private static BigDecimal num(Map<String, Object> params, String key, BigDecimal fallback) {
        Object value = params.get(key);
        return value == null ? fallback : new BigDecimal(String.valueOf(value));
    }

    private static boolean bool(Map<String, Object> params, String key, boolean fallback) {
        Object value = params.get(key);
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    // ------------------------------------------------------------------ the decision

    public static Decision decide(Scenario scenario, long runId, long teamId, Context context,
                                  Profile profile) {
        String seedKey = runId + ":bot:" + context.round() + ":" + teamId;
        Map<String, ScoringEngine.ProductLine> last = new LinkedHashMap<>();
        for (ScoringEngine.ProductLine line : context.lastLines()) {
            last.put(line.product(), line);
        }

        List<DecisionPayload.ProductRow> production = new ArrayList<>();
        List<DecisionPayload.PriceRow> prices = new ArrayList<>();
        StringBuilder why = new StringBuilder();

        Map<String, BigDecimal> unitsByProduct = new LinkedHashMap<>();
        Map<String, BigDecimal> priceByProduct = new LinkedHashMap<>();
        List<Scenario.ProductSpec> carries = carried(scenario, context);
        // D128: the classes this company's own products serve — the target of a focus strategy.
        List<String> ownClasses = ownClasses(scenario, carries);
        for (Scenario.ProductSpec spec : carries) {
            ScoringEngine.ProductLine previous = last.get(spec.code());
            BigDecimal forecast = forecast(spec, previous, context, profile, seedKey);
            BigDecimal stock = stock(context, spec.code());
            BigDecimal price = price(scenario, spec, context, forecast, stock, profile);

            // Own demand is the anchor a trainee also has; `targetShare` is only the entry position
            // in round 1, when there is no history yet. (Scaling *own* demand by a share every month
            // would spiral a company down while its fixed costs kept running.)
            BigDecimal planned = previous == null
                    ? forecast.multiply(profile.targetShare())
                    : forecast;
            if (stock.compareTo(forecast) < 0) {
                // Rebuild the shelf, but only to a fifth of expected demand: chasing the whole gap
                // doubles the plan and doubles the crew, which turns one thin month into a payroll
                // the company cannot carry.
                planned = planned.add(forecast.subtract(stock)
                        .min(forecast.multiply(new BigDecimal("0.20"))));
            }
            planned = planned.min(spec.baseDemand().multiply(new BigDecimal("1.5")));
            // D128: a focused company does not push the products outside its class. It still sends a row for
            // them — the validator wants the whole line — but the row is a plan for the stock it has, not for
            // a market it is not competing in.
            if (!inLine(spec, ownClasses, profile)) {
                planned = BigDecimal.ZERO;   // outside the chosen class: sell down the shelf, build nothing
            }
            planned = planned.setScale(0, RoundingMode.HALF_UP);
            unitsByProduct.put(spec.code(), planned);
            priceByProduct.put(spec.code(), price);
            production.add(new DecisionPayload.ProductRow(spec.code(), planned));
            prices.add(new DecisionPayload.PriceRow(spec.code(), price));
        }

        // Workforce follows the plan, but hiring is expensive: a company may grow its crew by half a
        // month's worth at a time, and the plan is trimmed to what that crew can actually make.
        int currentStaff = context.staff().getOrDefault("PRODUCTION", 0);
        int maxStaff = Math.max(currentStaff + 2, (int) Math.ceil(currentStaff * 1.5));
        int needed = staffFor(scenario, unitsByProduct);
        if (needed > maxStaff) {
            BigDecimal factor = BigDecimal.valueOf(maxStaff)
                    .divide(BigDecimal.valueOf(needed), 4, RoundingMode.HALF_UP);
            for (Map.Entry<String, BigDecimal> entry : unitsByProduct.entrySet()) {
                BigDecimal scaled = entry.getValue().multiply(factor).setScale(0, RoundingMode.DOWN);
                entry.setValue(scaled);
            }
            production.clear();
            for (Map.Entry<String, BigDecimal> entry : unitsByProduct.entrySet()) {
                production.add(new DecisionPayload.ProductRow(entry.getKey(), entry.getValue()));
            }
            needed = staffFor(scenario, unitsByProduct);
        }
        int productionStaff = Math.max(1, needed);
        BigDecimal machines = BigDecimal.ZERO;

        BigDecimal expectedRevenue = BigDecimal.ZERO;
        for (Scenario.ProductSpec spec : carries) {
            expectedRevenue = expectedRevenue.add(
                    unitsByProduct.get(spec.code()).multiply(priceByProduct.get(spec.code())));
        }
        BigDecimal marketing = expectedRevenue.multiply(profile.marketingRate())
                .setScale(-5, RoundingMode.HALF_UP);
        List<DecisionPayload.ChannelRow> channels = new ArrayList<>();
        if (!scenario.channels().isEmpty() && marketing.signum() > 0) {
            // D128: weight by the reach this company actually gets, and by the strategy's channel bias for the
            // classes its products serve. A focus player puts its money where its class is reachable; a cost
            // leader and a differentiator keep the neutral, reach-proportional split they had.
            List<BigDecimal> weights = new ArrayList<>();
            BigDecimal totalReach = BigDecimal.ZERO;
            for (Scenario.ChannelSpec channel : scenario.channels()) {
                BigDecimal weight = channel.reachWeight();
                for (String classCode : ownClasses) {
                    if (profile.channelBias().compareTo(BigDecimal.ONE) > 0) {
                        weight = weight.multiply(channel.reachFor(classCode)
                                        .multiply(profile.channelBias())
                                        .add(BigDecimal.ONE.subtract(profile.channelBias())
                                                .multiply(channel.reachWeight())));
                    }
                }
                weights.add(weight);
                totalReach = totalReach.add(weight);
            }
            BigDecimal allocated = BigDecimal.ZERO;
            for (int i = 0; i < scenario.channels().size(); i++) {
                Scenario.ChannelSpec channel = scenario.channels().get(i);
                boolean lastChannel = i == scenario.channels().size() - 1;
                BigDecimal share = totalReach.signum() == 0 ? BigDecimal.ONE
                        .divide(BigDecimal.valueOf(scenario.channels().size()), 4, RoundingMode.HALF_UP)
                        : weights.get(i).divide(totalReach, 4, RoundingMode.HALF_UP);
                BigDecimal spend = lastChannel ? marketing.subtract(allocated)
                        : marketing.multiply(share).setScale(-4, RoundingMode.HALF_UP);
                allocated = allocated.add(spend);
                channels.add(new DecisionPayload.ChannelRow(channel.code(), spend));
            }
        }

        List<DecisionPayload.RndRow> rnd = new ArrayList<>();
        BigDecimal rndSpend = BigDecimal.ZERO;
        if (profile.investsInRnd() && comfortable(context, scenario, profile)) {
            for (Scenario.ProjectSpec project : scenario.projects()) {
                ScoringEngine.RndState state = context.rnd().get(project.code());
                boolean open = state == null || "ACTIVE".equals(state.status());
                BigDecimal pace = project.hintBand() == null ? BigDecimal.ZERO
                        : project.hintBand().divide(BigDecimal.valueOf(Math.max(1, project.durationMonths())), 0,
                        RoundingMode.HALF_UP);
                // D128: the strategy decides how hard to push the slate, not just whether to play it.
                pace = pace.multiply(profile.rndAppetite()).setScale(0, RoundingMode.HALF_UP);
                if (open && pace.signum() > 0) {
                    rnd.add(new DecisionPayload.RndRow(project.code(), pace));
                    rndSpend = rndSpend.add(pace);
                }
            }
        }

        List<DecisionPayload.QualityRow> quality = new ArrayList<>();
        BigDecimal qualitySpend = BigDecimal.ZERO;
        if (profile.buysQuality() && scenario.params().qualityUpgrade()
                && comfortable(context, scenario, profile) && !carries.isEmpty()) {
            // D132: quality is bought for the product the strategy actually sells, not always the first one in
            // the catalogue. A differentiator whose premium sits on P2 was upgrading P1, so the money bought a
            // better product than the one carrying the price rise — and the calibration showed it losing money
            // at every level while the cheaper strategies prospered.
            // D132: the spend must be rationed by what the company actually earns. Two quality points on one
            // product cost 120m a month against ~2.5m of marketing and a 6% premium — 48x the marketing budget,
            // which no premium can recover. A differentiator buys **one** point a month on the product carrying
            // its price, which builds quality steadily and stays inside the margin the strategy can defend.
            int points = 1;
            for (String code : qualityTargets(scenario, carries, profile)) {
                BigDecimal spend = scenario.params().qualityCostPerPoint()
                        .multiply(BigDecimal.valueOf(points));
                qualitySpend = qualitySpend.add(spend);
                quality.add(new DecisionPayload.QualityRow(code, spend));
            }
        }

        boolean review = profile.buysMarketReview() && comfortable(context, scenario, profile);

        // D128: the cost leader buys capacity out of its slack — that is what makes a low price survivable.
        // The other two grow through the crew instead, so they keep their cash for quality, R&D or channel.
        if (profile.capacityBias().signum() > 0 && comfortable(context, scenario, profile)) {
            BigDecimal costPerMachine = scenario.params().machineCostPerWorker();
            if (costPerMachine != null && costPerMachine.signum() > 0) {
                BigDecimal slack = context.cash()
                        .subtract(scenario.params().fixedCostPerMonth().multiply(profile.cashBufferMonths()))
                        .subtract(marketing).subtract(rndSpend).subtract(qualitySpend)
                        .subtract(review ? scenario.params().marketReportCost() : BigDecimal.ZERO);
                BigDecimal room = slack.max(BigDecimal.ZERO)
                        .multiply(new BigDecimal("0.10")).multiply(profile.capacityBias())
                        .setScale(-6, RoundingMode.DOWN);
                if (room.compareTo(costPerMachine) >= 0) {
                    machines = room.divide(costPerMachine, 0, RoundingMode.DOWN).multiply(costPerMachine);
                }
            }
        }

        // Stay inside the cash: trim the discretionary spend, never the production plan (the engine
        // refuses an unaffordable plan, and a bot that keeps being refused never plays).
        BigDecimal discretionary = marketing.add(rndSpend).add(qualitySpend).add(machines)
                .add(review ? scenario.params().marketReportCost() : BigDecimal.ZERO);
        BigDecimal headroom = context.cash()
                .subtract(scenario.params().fixedCostPerMonth().multiply(profile.cashBufferMonths()));
        if (discretionary.compareTo(headroom) > 0 && discretionary.signum() > 0) {
            BigDecimal factor = headroom.signum() <= 0 ? BigDecimal.ZERO
                    : headroom.divide(discretionary, 4, RoundingMode.HALF_UP);
            marketing = marketing.multiply(factor).setScale(-5, RoundingMode.HALF_UP);
            channels.clear();
            if (marketing.signum() > 0 && !scenario.channels().isEmpty()) {
                BigDecimal each = marketing.divide(BigDecimal.valueOf(scenario.channels().size()), 0,
                        RoundingMode.HALF_UP);
                for (Scenario.ChannelSpec channel : scenario.channels()) {
                    channels.add(new DecisionPayload.ChannelRow(channel.code(), each));
                }
            }
            rnd.clear();
            rndSpend = BigDecimal.ZERO;
            quality.clear();
            qualitySpend = BigDecimal.ZERO;
            review = false;
            machines = BigDecimal.ZERO;
        }

        // toPlainString(): stripTrailingZeros() turns 600.00 into 6E+2, and a rationale read aloud in
        // class must not contain scientific notation.
        why.append("P1 ").append(unitsByProduct.getOrDefault("P1", BigDecimal.ZERO)
                .stripTrailingZeros().toPlainString())
                .append(" @").append(shortMoney(priceByProduct.getOrDefault("P1", BigDecimal.ZERO)));
        why.append(" · staff ").append(productionStaff);
        why.append(" · marketing ").append(shortMoney(marketing));
        why.append(rnd.isEmpty() ? " · no R&D" : " · R&D " + shortMoney(rndSpend));
        why.append(review ? " · review bought" : " · no review");

        DecisionPayload payload = new DecisionPayload(production, prices, channels,
                new DecisionPayload.ManpowerRow(productionStaff), rnd, quality, machines, review);
        return new Decision(payload, why.toString(), seedKey);
    }

    // ------------------------------------------------------------------ parts

    /**
     * What this company thinks it can sell next month. Round 1 has no history, so the template's base
     * demand is the anchor; after that last month's demand is, corrected by the published index and a
     * seeded error whose size is the level's honesty about the future.
     */
    private static BigDecimal forecast(Scenario.ProductSpec spec, ScoringEngine.ProductLine previous,
                                       Context context, Profile profile, String seedKey) {
        BigDecimal anchor = previous != null && previous.demand().signum() > 0
                ? previous.demand()
                : spec.baseDemand().multiply(profile.targetShare());
        BigDecimal published = context.environment().demandByProduct()
                .getOrDefault(spec.code(), context.environment().demandIndex());
        BigDecimal correction = BigDecimal.ONE.add(published.subtract(BigDecimal.ONE)
                .multiply(profile.forecastBlend()));
        BigDecimal noise = BigDecimal.ONE.add(Seed.uniform(seedKey + ":f:" + spec.code())
                .subtract(new BigDecimal("0.5"))
                .multiply(BigDecimal.valueOf(2))
                .multiply(profile.demandNoise()));
        return anchor.multiply(correction).multiply(noise).max(BigDecimal.ZERO);
    }

    /**
     * Price: the template's base price, moved by the personality's posture, pulled down when stock is
     * piling up and up when the market is hungry. The top level also follows the published demand.
     */
    private static BigDecimal price(Scenario scenario, Scenario.ProductSpec spec, Context context,
                                    BigDecimal forecast, BigDecimal stock, Profile profile) {
        BigDecimal price = spec.basePrice().multiply(profile.pricePosture());
        if ("HIGH".equals(profile.level())) {
            BigDecimal demand = context.environment().demandIndex();
            BigDecimal drift = demand.subtract(BigDecimal.ONE).multiply(new BigDecimal("0.15"));
            price = price.multiply(BigDecimal.ONE.add(drift));
        }
        if (stock.compareTo(forecast.multiply(new BigDecimal("1.5"))) > 0) {
            price = price.multiply(new BigDecimal("0.96"));
        } else if (stock.compareTo(forecast.multiply(new BigDecimal("0.15"))) < 0) {
            price = price.multiply(new BigDecimal("1.05"));
        }
        return price.setScale(2, RoundingMode.HALF_UP);
    }

    private static int staffFor(Scenario scenario, Map<String, BigDecimal> unitsByProduct) {
        BigDecimal workers = BigDecimal.ZERO;
        for (Scenario.ProductSpec spec : scenario.products()) {
            BigDecimal units = unitsByProduct.getOrDefault(spec.code(), BigDecimal.ZERO);
            if (spec.unitsPerWorker() != null && spec.unitsPerWorker().signum() > 0) {
                workers = workers.add(units.divide(spec.unitsPerWorker(), 0, RoundingMode.CEILING));
            }
        }
        return Math.max(1, workers.setScale(0, RoundingMode.CEILING).intValue());
    }

    private static BigDecimal stock(Context context, String product) {
        ScoringEngine.ProductState state = context.products().get(product);
        return state == null || state.stock() == null ? BigDecimal.ZERO : state.stock();
    }

    /**
     * The products this company carries (D89). A bot decides for the company's own line, never for the
     * whole catalogue: a company seated with a subset profile has no row for the rest, so naming one
     * would be refused at submit and the company would never play.
     */
    private static List<Scenario.ProductSpec> carried(Scenario scenario, Context context) {
        if (context.products() == null || context.products().isEmpty()) {
            return scenario.products();
        }
        List<Scenario.ProductSpec> out = new ArrayList<>();
        for (Scenario.ProductSpec spec : scenario.products()) {
            if (context.products().containsKey(spec.code())) {
                out.add(spec);
            }
        }
        return out;
    }

    /**
     * D128: the customer classes this company's own products are aimed at. A focused company serves these and
     * nothing else, so they decide both where the marketing goes and how wide the line stays.
     */
    private static List<String> ownClasses(Scenario scenario, List<Scenario.ProductSpec> carries) {
        List<String> classes = new ArrayList<>();
        for (Scenario.ProductSpec spec : carries) {
            String target = spec.targetSegment();
            if (target != null && !target.isBlank() && !classes.contains(target)) {
                classes.add(target);
            }
        }
        return classes;
    }

    /**
     * D132: which products the quality money goes to.
     *
     * <p>A focused company upgrades the product aimed at its class. A differentiator upgrades the products it
     * charges a premium on — the whole line is not affordable, so it buys depth where the price is defensible
     * rather than always upgrading the first product in the catalogue. A cost leader, if it buys at all, upgrades
     * the cheapest product, because its strategy is winning on price rather than on product.
     */
    private static List<String> qualityTargets(Scenario scenario, List<Scenario.ProductSpec> carries,
                                               Profile profile) {
        if (carries.isEmpty()) {
            return List.of();
        }
        if (profile.focused()) {
            List<String> own = ownClasses(scenario, carries);
            for (Scenario.ProductSpec spec : carries) {
                if (spec.targetSegment() != null && own.contains(spec.targetSegment())) {
                    return List.of(spec.code());
                }
            }
        }
        if ("DIFFERENTIATION".equals(profile.strategy())) {
            // The product with the highest base price: that is where a premium is defensible.
            Scenario.ProductSpec premium = carries.get(0);
            for (Scenario.ProductSpec spec : carries) {
                if (spec.basePrice() != null && premium.basePrice() != null
                        && spec.basePrice().compareTo(premium.basePrice()) > 0) {
                    premium = spec;
                }
            }
            return List.of(premium.code());
        }
        return List.of(carries.get(0).code());
    }

    /**
     * D128: line discipline. A focused company produces only the products aimed at its class; the rest of the
     * catalogue is carried but not pushed, so the line narrows without the company refusing to submit (which
     * the validator would reject).
     */
    private static boolean inLine(Scenario.ProductSpec spec, List<String> ownClasses, Profile profile) {
        if (!profile.focused() || ownClasses.isEmpty()) {
            return true;
        }
        return spec.targetSegment() != null && ownClasses.contains(spec.targetSegment());
    }

    private static boolean comfortable(Context context, Scenario scenario, Profile profile) {
        return context.cash().compareTo(
                scenario.params().fixedCostPerMonth().multiply(profile.cashBufferMonths())) > 0;
    }

    private static String shortMoney(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        if (value.abs().compareTo(new BigDecimal("1000000")) >= 0) {
            return value.divide(new BigDecimal("1000000"), 1, RoundingMode.HALF_UP).stripTrailingZeros()
                    + "m";
        }
        return value.stripTrailingZeros().toPlainString();
    }
}
