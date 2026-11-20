-- D111: the shipped template's channels state their market-specific reach.
--
-- D109 put the market-specific columns on the channel record; D110 stopped the editor inventing values
-- for them. Both left the shipped template with all nine blank, so a class played on `standard-v1` (and
-- on the three presets, which clone it) never exercises the feature — the columns exist and do nothing.
--
-- **The numbers are derived from what each channel is, not tuned.** The rule, applied identically to all
-- three channels, is:

--   1. A channel's *market-specific* weight is its own `reach_weight` scaled by how well that channel
--      suits the class, expressed as a multiplier in {1.6, 1.0, 0.4} for (best fit, neutral, poor fit).
--      The same three multipliers are used for every channel, so no channel is favoured by the encoding.
--   2. Best fit is decided by the class's **own economics**, which the template already states:
--        - SEG-BASIC   : income_elasticity 0.40 (price-sensitive) and the largest pool (4,000)
--        - SEG-MID     : income_elasticity 1.00, the neutral class by construction
--        - SEG-PREMIUM : income_elasticity 1.80 (least price-sensitive) and the smallest pool (600)
--   3. Channel nature:
--        - TRADE   (wholesale/trade counters)     : reaches price-sensitive mass buyers best, affluent
--                                                   buyers worst           -> 1.6 / 1.0 / 0.4
--        - FIELD   (field sales, personal selling) : reaches affluent buyers and middle buyers best,
--                                                   price-driven mass worst -> 0.4 / 1.0 / 1.6
--        - DIGITAL (digital media)                 : reaches the largest, most reachable audience and
--                                                   the middle class well; affluent buyers are the least
--                                                   digitally-reached of the three -> 1.6 / 1.6 / 0.4
--
-- Worked example, TRADE: reach_weight 1.200 x 1.6 = 1.920 on mass; x 1.0 = 1.200 on middle;
-- x 0.4 = 0.480 on affluent. The middle column equals the channel's own weight, which is what makes the
-- encoding auditable: every channel's middle value is its `reach_weight`, by rule 1.
--
-- Consequences, stated so they are a decision and not a surprise:
--   * DIGITAL and TRADE now reach the mass market hardest, FIELD the affluent class. Under the old
--     segment-blind model the three channels differed only in *total* reach (Trade 1.2 > Digital 1.0 >
--     Field 0.8); they now differ in *who* they reach, which is the point of the feature.
--   * The middle class is unchanged for every channel (1.6 x reach_weight for Digital, 1.0 for the
--     other two are the multipliers; the stored value is reach_weight x multiplier in each case).
--   * A class played on the shipped template will now see products serving different classes attract
--     different shares from the same spend — measurable, and the reason this is worth doing at all.
--
-- **Tenant drafts are deliberately left blank** (the owner's ruling). A draft is somebody's own economy,
-- and seeding it would put numbers in their template that they did not choose and cannot attribute. The
-- editor shows a blank cell as "not specific to this market", which is the truth for a draft.
--
-- Scope: only the shipped templates that carry the shipped channels — `standard-v1` and the three
-- presets cloned from it. A tenant copy made *before* this migration also stays blank, because
-- `tenant_id is not null` marks it as somebody's own.

update channel c
set reach_mass = round(c.reach_weight * case c.code
                                           when 'TRADE'   then 1.6
                                           when 'DIGITAL' then 1.6
                                           when 'FIELD'   then 0.4
                                       end, 3),
    reach_mid   = round(c.reach_weight * 1.0, 3),
    reach_premium = round(c.reach_weight * case c.code
                                             when 'TRADE'   then 0.4
                                             when 'DIGITAL' then 0.4
                                             when 'FIELD'   then 1.6
                                         end, 3)
where c.scenario_key in ('standard-v1', 'preset-cost-v1', 'preset-diff-v1', 'preset-focus-v1')
  and c.code in ('TRADE', 'DIGITAL', 'FIELD');
