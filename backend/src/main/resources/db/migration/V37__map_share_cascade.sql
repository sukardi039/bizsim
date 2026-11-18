-- D103: a shared map belongs to a session. Deleting the session must take its links with it, or a
-- facilitator cannot delete a run once anyone has shared its picture (and the page would outlive the
-- class it describes). `tenant` keeps the plain reference: tenants are not deleted in normal operation.
alter table map_share drop constraint map_share_run_id_fkey;
alter table map_share add constraint map_share_run_id_fkey
    foreign key (run_id) references run (id) on delete cascade;
