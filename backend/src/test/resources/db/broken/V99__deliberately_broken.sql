-- Test fixture only. Lives under src/test/resources, never on the application classpath.
--
-- It exists so the suite can prove the property a deploy depends on: in PostgreSQL, DDL is
-- transactional, so a migration that fails half-way leaves no table, no column and no history row
-- behind. The first statement would succeed; the second must fail; the whole file must roll back.
create table migration_probe (id integer primary key);

select this_function_does_not_exist();
