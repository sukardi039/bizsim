-- A package freezes the template it was published from (ruling A, 3 Nov 2026).
--
-- Before this, a package *recorded* `template_version` while execution loaded the template by key —
-- so editing a draft between publishing and the class changed the class. Now publishing clones the
-- template into a private row that nothing can edit, and the package points at that row. The draft
-- stays a working document; what a class runs is the frozen copy.
--
-- Why a copy and not a versioned template: the engine, the catalogue and the loader already work on
-- whole scenario rows. A frozen row is version 1 of a snapshot, needs no new lookup path, and keeps
-- every existing invariant — including "a scenario is complete or it does not exist".

alter table scenario add column frozen_for_package bigint references simulation_package (id);

alter table simulation_package add column source_template_key varchar(60) references scenario (key);

create index scenario_frozen_idx on scenario (frozen_for_package);

comment on column scenario.frozen_for_package is
    'set on the private copy a package runs on: hidden from the template library and never editable';
comment on column simulation_package.source_template_key is
    'the draft the builder published from, for provenance; template_key is the frozen copy that runs';
