-- Resetting a session is a facilitator act worth recording (D58).
--
-- The reset restores every company to the state recorded when the books opened and deletes every
-- round, so the class starts again from month 0 with the same people seated. The action joins the
-- audit trail with the other session controls, because "we started again" is something a class asks
-- about afterwards.

alter table facilitator_action drop constraint if exists facilitator_action_action_check;

alter table facilitator_action add constraint facilitator_action_action_check
    check (action in ('CLOCK_PAUSE', 'CLOCK_RESUME', 'CLOCK_EXTEND', 'CASH_ADJUST', 'ROUND_VOID',
                      'STANDINGS_SHOW', 'STANDINGS_HIDE', 'RUN_RESET'));
