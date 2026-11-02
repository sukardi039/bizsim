-- V4: R&D benefit curves and over-funding policy are per-project definition (ruling D17).
--
-- Four knobs, all scenario data, all optional per project:
--   partial_benefit   false -> the project needs 100% of its hidden cost, no partial credit
--   benefit_curve     LINEAR  factor = progress
--                     POWER   factor = progress ^ benefit_exponent  (2.0 = concave-to-the-top,
--                             the last dollars buy the most; 0.5 gives the opposite shape)
--                     CHANCE  success if progress >= a hidden threshold drawn once from a seeded
--                             PRNG, revealed after the deadline. Same inputs replay identically.
--   overfunding       REFUND (default) -> money above the hidden cost comes back at completion
--                     LOST             -> the programme consumes everything invested
--
-- progress = (ratio - min_ratio) / (1 - min_ratio), where ratio = invested / hidden_cost and
-- min_ratio is the scenario's minRndBenefitRatio (0.80).

alter table rnd_project add column partial_benefit boolean not null default true;
alter table rnd_project add column benefit_curve varchar(20) not null default 'LINEAR'
    check (benefit_curve in ('LINEAR', 'POWER', 'CHANCE'));
alter table rnd_project add column benefit_exponent numeric(4, 2) not null default 1.00
    check (benefit_exponent > 0);
alter table rnd_project add column overfunding varchar(10) not null default 'REFUND'
    check (overfunding in ('REFUND', 'LOST'));

-- The shipped slate stays LINEAR + REFUND so the standard scenario matches D10 exactly and the
-- calibration table in docs/SCENARIO_CALIBRATION.md stays valid. Switching a project is one update:
--   update rnd_project set benefit_curve = 'POWER', benefit_exponent = 2.0 where code = 'RND-CAP';
