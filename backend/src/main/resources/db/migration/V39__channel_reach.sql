-- D107: channel reach can be weighted per customer class.
--
-- Today `channel.reach_weight` is one number and marketing is segment-blind: spend in any channel lifts
-- share in every product and every class alike. This table makes the weight a function of
-- (channel, customer class) as well, so a template can say "Field sales reaches the premium class well
-- and the mass market barely at all".
--
-- **A missing row means "the channel's own weight"**, not zero. That is deliberate: every existing
-- template, and every new one, keeps behaving exactly as it does today unless a trainer adds weights.
-- With no rows at all the allocation is identical to the pre-D107 engine, which is what makes this
-- migration safe to apply to a running class.
create table channel_reach (
    scenario_key varchar(60) not null,
    channel_code varchar(20) not null,
    segment_code varchar(20) not null,
    -- Relative reach to this class, on the same scale as channel.reach_weight. 0 means the channel
    -- cannot reach this class at all, which is a legitimate strategy lever.
    weight       numeric(6, 3) not null check (weight >= 0),
    primary key (scenario_key, channel_code, segment_code),
    foreign key (scenario_key, channel_code) references channel (scenario_key, code) on delete cascade,
    foreign key (scenario_key, segment_code) references customer_segment (scenario_key, code) on delete cascade
);

comment on table channel_reach is
    'D107: per-class reach weight for a channel. No row = use channel.reach_weight (segment-blind).';
