-- One-off repair for runs that predate the finish transition (9dbbbdc).
--
-- Before that fix nothing ever moved a run out of OPEN once its final round was computed: Flyway's
-- own history shows why the code path existed but nothing called it. The visible effects were a
-- console that kept offering another round on a session that had played 8 of 8, and a facilitator
-- who could not start the next cohort because the D21 guard treats every run that is not FINISHED
-- as still in flight.
--
-- Only runs whose final round is actually COMPUTED are closed. A run whose last round is still open
-- (the class has not submitted it yet) is genuinely in progress, so it keeps its status -- closing it
-- here would freeze a live round.
--
-- finished_at takes the moment the last results were written, never the moment this migration ran,
-- so the recorded end of a session stays truthful.

update run r
set status = 'FINISHED',
    finished_at = coalesce(r.finished_at,
        (select max(coalesce(rr.computed_at, rd.closed_at))
         from round rd
         left join round_result rr on rr.round_id = rd.id
         where rd.run_id = r.id
           and rd.status = 'COMPUTED'))
where r.status <> 'FINISHED'
  and r.rounds is not null
  and r.current_round >= r.rounds
  and exists (
      select 1
      from round rd
      where rd.run_id = r.id
        and rd.number >= r.rounds
        and rd.status = 'COMPUTED'
  );
