-- V48: a channel says what happens inside it (D127).
--
-- The owner's review: a trainee deciding where to put marketing money could see a channel's name and its
-- reach weights, but not what the money actually buys inside it — a trade show, paid search, a distributor's
-- shelf, sales visits. Betting on a return needs to know the activities.
--
-- This is words per language, so it lives in `channel_text`, which already carries a channel's name per
-- language (D114). Nothing here is a new mechanic: no weight, multiplier or cost moves.
--
-- Ordering matters, and this cost one failed migration: V42 seeded the **zh** rows only, so writing both
-- languages as inserts collides with `channel_text_pkey` on the zh ones. The shipped rows are updated in
-- place instead, and a description is only written where the row is still carrying the shipped name — the
-- same rule V43 used, so a channel a trainer has renamed keeps the trainer's words.

alter table channel_text add column description text;

-- The shipped three. English words for the English rows, Chinese for the Chinese ones, each describing the
-- channel V41 defined: DIGITAL the broadest and fastest reach, TRADE the widest physical reach and the
-- slowest to move, FIELD the narrowest reach and the strongest effect on named customers.
update channel_text set description = case channel_code
    when 'DIGITAL' then
        'Paid online media and own channels: search advertising (SEO and paid search), social and display '
        || 'campaigns, marketplace listings, and email to the existing customer list. Broad and fast, but '
        || 'attention is rented month to month — the effect fades as soon as the spend stops.'
    when 'TRADE' then
        'Working through distributors and retailers: listing fees and shelf space, trade promotions and '
        || 'volume discounts, distributor incentive schemes, point-of-sale display, and the annual trade '
        || 'show where next year''s listings are agreed. The widest physical reach, and the slowest to move '
        || '— a listing won this month sells next quarter.'
    when 'FIELD' then
        'A direct sales force in front of named customers: account visits and demonstrations, tender and '
        || 'procurement work, technical support during trials, after-sales service, and relationship '
        || 'building with the largest buyers. The narrowest reach and the highest cost per contact, but the '
        || 'strongest effect on the customers who matter most.'
end
where lang = 'en'
  and scenario_key in (select key from scenario where key = 'standard-v1' or key like 'preset-%')
  -- Only where the row still carries the shipped name: a tenant's own wording is not ours to describe.
  and name = case channel_code when 'DIGITAL' then 'Digital'
                               when 'TRADE' then 'Trade'
                               when 'FIELD' then 'Field sales' end;

update channel_text set description = case channel_code
    when 'DIGITAL' then
        '按点击和曝光付费的网络渠道：搜索广告（SEO 与竞价排名）、社交媒体与展示广告、电商平台的店铺与'
        || '商品页，以及面向现有客户名单的邮件营销。覆盖面最广、见效最快，但注意力是按月租来的——预算一停，'
        || '效果随即消退。'
    when 'TRADE' then
        '通过经销商与零售终端销售：进场费与货架位置、渠道促销与批量折扣、经销商激励政策、门店陈列，以及'
        || '每年敲定下一年度上架计划的行业展会。物理覆盖最广，但反应最慢——本月谈下的上架，往往下个季度才'
        || '真正开卖。'
    when 'FIELD' then
        '由直销团队面对具体客户：客户拜访与产品演示、招投标与采购流程、试用期的技术支持、售后服务和与'
        || '关键大客户的长期关系维护。覆盖最窄、单次接触成本最高，但对最重要的客户影响最强。'
end
where lang = 'zh'
  and scenario_key in (select key from scenario where key = 'standard-v1' or key like 'preset-%');

comment on column channel_text.description is
    'D127: what happens inside this channel — the activities the money buys, for the class reading it.';
