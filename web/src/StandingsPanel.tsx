import { useEffect, useState } from "react";
import { api, type Run, type StandingsRow } from "./api";
import { useI18n } from "./i18n";
import { describeError } from "./errors";

/**
 * The class table as the class sees it (D51/D52) — facilitator only.
 *
 * <p>Two things at once: the exact three columns a trainee reads, and the switch that decides whether
 * they may read them. The switch is a session control, so the audit trail records when the ranking
 * was shown or hidden.
 */
export default function StandingsPanel({ run, onChanged }: { run: Run; onChanged: () => void }) {
  const { t, money } = useI18n();
  const [rows, setRows] = useState<StandingsRow[]>([]);
  const [visible, setVisible] = useState<boolean>(Boolean(run.standingsVisible));
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setVisible(Boolean(run.standingsVisible));
  }, [run.standingsVisible, run.id]);

  useEffect(() => {
    let live = true;
    void (async () => {
      try {
        const next = await api.standings(run.id);
        if (live) setRows(next);
      } catch {
        if (live) setRows([]);
      }
    })();
    return () => {
      live = false;
    };
  }, [run.id, run.currentRound]);

  const toggle = async () => {
    setBusy(true);
    setProblem(null);
    try {
      const result = await api.setStandingsVisible(run.id, !visible);
      setVisible(result.visible);
      onChanged();
    } catch (error) {
      setProblem(describeError(error, t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card">
      <h2>{t("standings.title")}</h2>
      <p className="muted">{t("standings.hint")}</p>
      {problem && <p className="error">{problem}</p>}

      <div className="buttons">
        <button onClick={() => void toggle()} disabled={busy}>
          {visible ? t("standings.hide") : t("standings.show")}
        </button>
        <span className="muted">
          {visible ? t("standings.shownToClass") : t("standings.hiddenFromClass")}
        </span>
      </div>

      {rows.length === 0 ? (
        <p className="muted">{t("standings.empty")}</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th className="num">{t("player.rank")}</th>
              <th>{t("debrief.company")}</th>
              <th className="num">{t("debrief.profit")}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.teamId}>
                <td className="num">{row.rank}</td>
                <td>{row.company}</td>
                <td className="num">{money(Number(row.profit ?? 0))}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
