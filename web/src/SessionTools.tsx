import { useCallback, useEffect, useMemo, useState } from "react";
import { useI18n } from "./i18n";
import { describeError } from "./errors";
import { api, type AuditRow, type Run, type SubmissionRow } from "./api";

/**
 * The four facilitator powers of D12/Q-G, on the run page:
 * stop and extend the clock, read what every company sent, move a company's cash with a reason on
 * the record, and undo the last computed round. Each one is a server call; the console never edits
 * a number itself, so two browser tabs cannot disagree about the state of a class.
 */
export default function SessionTools({
  run,
  onChanged,
  refreshKey,
}: {
  run: Run;
  onChanged: () => void;
  refreshKey: number;
}) {
  const { t } = useI18n();
  const [left, setLeft] = useState(run.clockRemainingSeconds ?? -1);
  const [submissions, setSubmissions] = useState<SubmissionRow[]>([]);
  const [audit, setAudit] = useState<AuditRow[]>([]);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [cash, setCash] = useState<Record<number, { delta: string; reason: string }>>({});

  const paused = Boolean(run.clockPaused);
  const untimed = (run.clockRemainingSeconds ?? -1) < 0;

  // Reset the countdown whenever the server sends a new deadline (open, pause, extend, resume).
  useEffect(() => {
    setLeft(run.clockRemainingSeconds ?? -1);
  }, [run.id, run.currentRound, run.closesAt, run.clockPaused, run.clockRemainingSeconds]);

  // Tick locally between refreshes: the server owns the deadline, this is only the display.
  useEffect(() => {
    if (paused || left <= 0) return;
    const timer = window.setInterval(() => setLeft((value) => Math.max(0, value - 1)), 1000);
    return () => window.clearInterval(timer);
  }, [paused, left > 0]);

  const load = useCallback(async () => {
    if (run.currentRound < 1) {
      setSubmissions([]);
      setAudit([]);
      return;
    }
    try {
      const [decisions, history] = await Promise.all([
        api.submissions(run.id),
        api.audit(run.id),
      ]);
      setSubmissions(decisions.companies);
      setAudit(history);
    } catch (error) {
      setProblem(describeError(error, t));
    }
  }, [run.id, run.currentRound, t]);

  useEffect(() => {
    void load();
  }, [load, refreshKey, run.roundOpen]);

  const act = async (action: () => Promise<unknown>, message: string) => {
    setBusy(true);
    setProblem(null);
    setNote(null);
    try {
      await action();
      setNote(message);
      await load();
      onChanged();
    } catch (error) {
      setProblem(describeError(error, t));
    } finally {
      setBusy(false);
    }
  };

  const togglePause = () =>
    paused
      ? act(() => api.resumeClock(run.id), t("tools.resumed"))
      : act(() => api.pauseClock(run.id), t("tools.pausedNote"));

  const extend = (minutes: number) =>
    act(() => api.extendClock(run.id, minutes), t("tools.extended", { minutes }));

  const submitCash = (teamId: number, companyName: string) => {
    const draft = cash[teamId];
    const delta = Number(draft?.delta ?? 0);
    const reason = (draft?.reason ?? "").trim();
    if (!delta) {
      setProblem(t("tools.cashNeedsAmount"));
      return;
    }
    if (reason.length < 3) {
      setProblem(t("tools.cashNeedsReason"));
      return;
    }
    void act(async () => {
      await api.adjustCash(run.id, teamId, delta, reason);
      setCash((current) => ({ ...current, [teamId]: { delta: "", reason: "" } }));
    }, t("tools.cashDone", { company: companyName }));
  };

  const voidLast = () => {
    const reason = window.prompt(t("tools.voidPrompt", { round: run.currentRound }));
    if (reason === null) return;
    if (reason.trim().length < 3) {
      setProblem(t("tools.voidNeedsReason"));
      return;
    }
    void act(
      () => api.voidLastRound(run.id, reason.trim()),
      t("tools.voidDone", { round: run.currentRound }),
    );
  };

  const submitted = useMemo(
    () => submissions.filter((row) => row.submitted).length,
    [submissions],
  );

  return (
    <>
      <section className="card">
        <h2>{t("tools.clock")}</h2>
        {problem && <p className="error">{problem}</p>}
        {note && <p className="notice">{note}</p>}
        {run.roundOpen ? (
          <>
            <p className={left >= 0 && left <= 60 ? "error" : "muted"}>
              {untimed
                ? t("tools.untimed")
                : paused
                  ? t("tools.clockPaused", { time: clockText(left) })
                  : t("tools.clockLeft", { time: clockText(left) })}
            </p>
            <div className="buttons">
              <button onClick={togglePause} disabled={busy}>
                {paused ? t("tools.resume") : t("tools.pause")}
              </button>
              <button onClick={() => extend(5)} disabled={busy}>
                {t("tools.extend", { minutes: 5 })}
              </button>
            </div>
          </>
        ) : (
          <>
            <p className="muted">{t("tools.noRoundOpen")}</p>
            {run.currentRound > 0 && run.status === "OPEN" && (
              <button className="danger" onClick={voidLast} disabled={busy}>
                {t("tools.void", { round: run.currentRound })}
              </button>
            )}
          </>
        )}
      </section>

      <section className="card">
        <h2>
          {t("tools.submissions")}
          {submissions.length > 0 && (
            <small className="muted"> · {t("tools.submittedCount", {
              done: submitted,
              total: submissions.length,
            })}</small>
          )}
        </h2>
        {submissions.length === 0 ? (
          <p className="muted">{t("tools.noRoundYet")}</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>{t("score.company")}</th>
                <th>{t("tools.submitted")}</th>
                <th>{t("tools.whatTheySent")}</th>
                <th>{t("tools.cashAdjust")}</th>
              </tr>
            </thead>
            <tbody>
              {submissions.map((row) => (
                <tr key={row.teamId}>
                  <td>{row.companyName}</td>
                  <td>
                    {row.submitted ? (
                      <span className="positive">
                        {t("tools.yes")}
                        {row.submittedBy ? <small className="muted"> · {row.submittedBy}</small> : null}
                      </span>
                    ) : (
                      <span className="muted">{t("tools.no")}</span>
                    )}
                  </td>
                  <td>
                    {row.decision ? (
                      <details>
                        <summary>{t("tools.showDecision")}</summary>
                        <DecisionSummary decision={row.decision} t={t} />
                      </details>
                    ) : (
                      <span className="muted">—</span>
                    )}
                  </td>
                  <td>
                    <div className="cash-row">
                      <input
                        type="number"
                        step="any"
                        placeholder={t("tools.delta")}
                        value={cash[row.teamId]?.delta ?? ""}
                        onChange={(e) =>
                          setCash((current) => ({
                            ...current,
                            [row.teamId]: {
                              delta: e.target.value,
                              reason: current[row.teamId]?.reason ?? "",
                            },
                          }))
                        }
                      />
                      <input
                        type="text"
                        placeholder={t("tools.reason")}
                        value={cash[row.teamId]?.reason ?? ""}
                        onChange={(e) =>
                          setCash((current) => ({
                            ...current,
                            [row.teamId]: {
                              delta: current[row.teamId]?.delta ?? "",
                              reason: e.target.value,
                            },
                          }))
                        }
                      />
                      <button
                        onClick={() => submitCash(row.teamId, row.companyName)}
                        disabled={busy}
                      >
                        {t("tools.apply")}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="card">
        <h2>{t("tools.audit")}</h2>
        {audit.length === 0 ? (
          <p className="muted">{t("tools.auditEmpty")}</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th className="num">{t("tools.round")}</th>
                <th>{t("tools.action")}</th>
                <th>{t("score.company")}</th>
                <th className="num">{t("tools.amount")}</th>
                <th>{t("tools.reason")}</th>
                <th>{t("tools.actor")}</th>
                <th>{t("tools.at")}</th>
              </tr>
            </thead>
            <tbody>
              {audit.map((row) => (
                <tr key={row.id}>
                  <td className="num">{row.round ?? "—"}</td>
                  <td>{t(`tools.action.${row.action}`)}</td>
                  <td>{row.companyName ?? "—"}</td>
                  <td className="num">{row.amount == null ? "—" : moneyText(row.amount)}</td>
                  <td>{row.reason ?? "—"}</td>
                  <td>{row.actor}</td>
                  <td>{new Date(row.at).toLocaleTimeString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}

/** What one company sent, in the same shape the form uses — readable without opening the form. */
function DecisionSummary({
  decision,
  t,
}: {
  decision: NonNullable<SubmissionRow["decision"]>;
  t: (key: string, params?: Record<string, string | number>) => string;
}) {
  const marketing = (decision.marketing ?? []).filter((row) => Number(row.spend) > 0);
  const rnd = (decision.rnd ?? []).filter((row) => Number(row.invest) > 0);
  const quality = (decision.quality ?? []).filter((row) => Number(row.invest) > 0);
  return (
    <ul className="decision-summary">
      {(decision.production ?? []).map((row) => (
        <li key={`p-${row.product}`}>
          <strong>{row.product}</strong>{" "}
          {t("tools.lineUnits", {
            units: row.units,
            price:
              (decision.price ?? []).find((p) => p.product === row.product)?.unitPrice ?? "—",
          })}
        </li>
      ))}
      {marketing.length > 0 && (
        <li>
          {t("tools.lineMarketing")}:{" "}
          {marketing.map((row) => `${row.channel} ${moneyText(Number(row.spend))}`).join(" · ")}
        </li>
      )}
      {rnd.length > 0 && (
        <li>
          {t("tools.lineRnd")}:{" "}
          {rnd.map((row) => `${row.project} ${moneyText(Number(row.invest))}`).join(" · ")}
        </li>
      )}
      {quality.length > 0 && (
        <li>
          {t("tools.lineQuality")}:{" "}
          {quality.map((row) => `${row.product} ${moneyText(Number(row.invest))}`).join(" · ")}
        </li>
      )}
      <li>
        {t("tools.lineStaff", {
          staff: decision.manpower?.production ?? 0,
          capacity: moneyText(Number(decision.capacityPurchase ?? 0)),
        })}
      </li>
    </ul>
  );
}

function clockText(seconds: number): string {
  if (seconds < 0) return "—";
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return `${minutes}:${String(rest).padStart(2, "0")}`;
}

function moneyText(value: number): string {
  return value.toLocaleString(undefined, { maximumFractionDigits: 2 });
}
