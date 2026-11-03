-- A third staff role: BUILDER (ruling 3 Nov 2026).
--
-- Building a simulation package and executing it are two functions performed by two parties, and
-- preparation happens well before the class. A BUILDER owns templates, companies and packages; a
-- FACILITATOR owns sessions and people; an ADMIN owns both. The check constraint is the only place
-- the vocabulary is enforced, so it moves with the product rather than being implied by the code.

alter table app_user drop constraint if exists app_user_role_check;

alter table app_user add constraint app_user_role_check
    check (role in ('ADMIN', 'FACILITATOR', 'BUILDER', 'PLAYER'));

comment on column app_user.role is
    'ADMIN = both jobs; BUILDER = prepares packages; FACILITATOR = runs sessions; PLAYER = trainee';
