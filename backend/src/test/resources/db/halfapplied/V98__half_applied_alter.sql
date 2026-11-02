-- Test fixture only. Never on the application classpath.
--
-- A realistic "half-applied" candidate: DDL and DML in one file, with the failure on the last
-- statement. On a database without transactional DDL this leaves a table with a row in it, a new
-- column on `team`, and an index behind. On PostgreSQL the whole file must disappear.
create table half_applied_probe (id integer primary key, note text);

insert into half_applied_probe values (1, 'row written by a migration that failed');

alter table team add column half_applied_probe text;

create index half_applied_probe_idx on team (half_applied_probe);

select this_statement_does_not_exist();
