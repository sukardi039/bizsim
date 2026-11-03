-- The class list belongs to the package (ruling 3 Nov 2026).
--
-- Until now a package carried the template and the companies, and the facilitator decided who sat
-- where when the session started. That put preparation back in the facilitator's hands on the day.
-- A package now carries the seating too: one person per company, chosen while the package is built.
--
-- `position` is the index of the company in `simulation_package.company_profile_ids` — the two are
-- parallel by construction. A company may be left unseated (no row) so a walk-in can be added later;
-- a person may not be seated twice in the same package (unique on user_id), because the same trainee
-- cannot run two competing companies in one class.

create table simulation_package_seat (
    package_id bigint not null references simulation_package (id) on delete cascade,
    position   integer not null check (position >= 0),
    user_id    bigint not null references app_user (id) on delete restrict,
    primary key (package_id, position),
    unique (package_id, user_id)
);

comment on table simulation_package_seat is
    'Who sits in each company of a prepared package; position matches company_profile_ids index';
