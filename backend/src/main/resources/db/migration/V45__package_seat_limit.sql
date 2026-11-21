-- V45: how many companies a class may have is a property of the package (D118).
--
-- Ten was never a property of the model: it came from decision 5 — "10 players per simulation, 5
-- concurrent simulations per tenant" — a sizing target for v1, written into a validation, a builder
-- constant and a ten-hue palette. The engine itself has always iterated over `teams.size()`.
--
-- A class size is a teaching decision: the same package prepared for twelve companies or for twenty-four
-- is the same economy, and the builder is the person who knows the room. So the number moves onto the
-- package, defaults to the old ten (no published package changes behaviour), and is bounded by what the
-- console can draw.
--
-- 2 is the floor because a class of one has no market to compete in; 30 is the ceiling because the
-- position map and the share pies have to stay readable, and the palette has thirty distinct colours.

alter table simulation_package add column seat_limit integer not null default 10
    check (seat_limit between 2 and 30);

comment on column simulation_package.seat_limit is
    'D118: how many companies (and therefore seats) this package may run; 10 for every package published before the column existed';
