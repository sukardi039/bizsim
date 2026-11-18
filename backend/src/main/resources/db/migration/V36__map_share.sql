-- D102: a shared position map is a link anyone in the class can open, so it has to be a row.
--
-- The page itself is frozen HTML (the figure and the round's numbers), because a link that works without
-- a login must not read the database when it is opened: a recomputed round would then quietly change what
-- the class was already shown. The token is stored hashed, so a database read cannot mint links, and
-- `revoked_at` withdraws one without deleting the record that it existed.
create table map_share (
    id          bigserial primary key,
    token_hash  text not null unique,
    tenant_id   bigint not null references tenant (id),
    run_id      bigint not null references run (id),
    kind        text not null,
    round       integer,
    title       text,
    html        text not null,
    created_at  timestamptz not null default now(),
    expires_at  timestamptz,
    revoked_at  timestamptz
);

create index map_share_run_idx on map_share (run_id, created_at desc);
