-- A failed publish must not leave a template behind (D54).
--
-- Publishing froze the template *before* validating the class list, and the controller was not a
-- single unit of work, so a publish refused after the freeze (a seat list of the wrong length, a
-- duplicate person, a bad class language) committed the frozen copy and then abandoned it. The copy
-- has `frozen_for_package` null, which is exactly the condition the template library filters on — so
-- it appeared there, published, attached to nothing.
--
-- The publish path now validates first and runs in one transaction (see PackageController.publish).
-- This removes the rows the old path left: copies of the form `pkg-…` that no package names, no
-- session ran, and no simulation points at. Nothing else is touched — a tenant's own drafts are named
-- after their template, not `pkg-`.

delete from scenario s
where s.key like 'pkg-%'
  and s.frozen_for_package is null
  and not exists (select 1 from simulation_package p where p.template_key = s.key)
  and not exists (select 1 from simulation_package p where p.source_template_key = s.key)
  and not exists (select 1 from simulation sim where sim.scenario_key = s.key)
  and not exists (select 1 from run r where r.scenario_key = s.key);
