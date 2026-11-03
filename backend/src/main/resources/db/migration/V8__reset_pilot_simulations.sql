-- One-off reset of the pilot simulations.
--
-- Every simulation created before this release ran under two defects: the scoreboard merged every
-- round into a single table, and a run never left OPEN once its final round was computed. Repairing
-- them (V7) leaves rows that still read oddly -- a class at "8 of 8" whose profit and cash columns
-- mix rounds -- so the pilot runs are cleared instead of patched.
--
-- Deleted: every simulation and everything hanging off it by cascade -- run, round, decision,
-- round_result, round_statement, team, player, the company_* tables, the run_environment_* tables
-- and run_round_economy.
--
-- Kept on purpose: tenants, user accounts, scenarios and products. Deleting accounts would lock the
-- facilitator out of the console, and the scenario is the product itself rather than play data. The
-- console therefore reopens with an empty run list, and the next simulation starts clean.
--
-- Flyway applies this exactly once. Simulations created after this release are never touched.
--
-- migration:allow-destructive

delete from simulation;
