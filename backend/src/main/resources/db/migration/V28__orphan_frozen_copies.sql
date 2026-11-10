-- Tidy up of a programme left one frozen copy behind per part, and those copies were readable.
--
-- Publishing freezes the template into a private scenario row per part (D62) and points
-- `scenario.frozen_for_package` back at the package. Tidy up cleared that back-reference and then
-- deleted only the package's own copy (`package.template_key`, i.e. part 1) — so for a package with
-- more than one part, parts 2..N stayed behind with no back-reference. That is exactly the condition
-- the template library filters on, so they appeared as ordinary templates, named
-- "<package> · <part> (v<version>)", and were offered in the package template picker.
--
-- The code deletes every copy now (PackageRepository.collect), and the library refuses the reserved
-- `pkg-<tenant>-<8 hex>` key shape even when a back-reference is missing. This removes the rows the
-- old path left behind. The guards are the same as V22's: nothing that a package, a part, a derived
-- template, a simulation or a run still names is touched, and only keys in the generated frozen
-- shape are considered — a tenant's own copies are named after their template, never `pkg-`.

delete from scenario s
where s.key ~ '^pkg-.+-[0-9a-f]{8}$'
  and s.frozen_for_package is null
  and not exists (select 1 from simulation_package p where p.template_key = s.key)
  and not exists (select 1 from simulation_package p where p.source_template_key = s.key)
  and not exists (select 1 from simulation_package_part pt
                  where pt.template_key = s.key or pt.source_template_key = s.key)
  and not exists (select 1 from scenario derived where derived.based_on = s.key)
  and not exists (select 1 from simulation sim where sim.scenario_key = s.key)
  and not exists (select 1 from run r where r.scenario_key = s.key);
