import { useEffect, useState } from "react";
import { api, ApiError, type BotReplay, type BotTraceRow, type Run } from "./api";
import { useI18n } from "./i18n";
import { describeError } from "./errors";

/**
 * What the opponents did, round by round (D49/D50) — facilitator only.
 *
 * <p>Three things a class day needs: which brain version played, the one-line reason it gives, and a
 * replay that proves the same input still produces the same decision. If a replay stops matching, the
 * brain was edited after the fact, and the facilitator should know before a trainee asks.
 */
export default function BotPanel({ run }: { run: Run }) {
  const { t } = useI18n();
  const [rows, setRows] = useState<BotTraceRow[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [replay, setReplay] = useState<BotReplay | null>(null);

  const load = async () => {
    try {
      setRows(await api.botTrace(run.id));
      setProblem(null);
    } catch (error) {
      // A class with no opponents is the normal case, not an error worth shouting about.
      setRows([]);
      if (error instanceof ApiError && error.status !== 404) {
        setProblem(describeError(error, t));
      }
    }
  };

  useEffect(() => {
    void load();
  }, [run.id, run.currentRound, run.roundOpen]);

  const ask = async () => {
    setBusy(true);
    setProblem(null);
    try {
      const result = await api.askBots(run.id);
      setProblem(null);
      await load();
      if (result.decided === 0) {
        setProblem(t("bots.nothingToDecide"));
      }
    } catch (error) {
      setProblem(describeError(error, t));
    } finally {
      setBusy(false);
    }
  };

  const verify = async (row: BotTraceRow) => {
    setProblem(null);
    try {
      setReplay(await api.replayBot(run.id, row.teamId, row.round));
    } catch (error) {
      setProblem(describeError(error, t));
    }
  };

  return (
    <section className="card">
      <h2>{t("bots.traceTitle")}</h2>
      <p className="muted">{t("bots.traceHint")}</p>
      {problem && <p className="error">{problem}</p>}

      <div className="buttons">
        <button onClick={() => void ask()} disabled={busy || !run.roundOpen}>
          {t("bots.ask")}
        </button>
      </div>

      {rows.length === 0 ? (
        <p className="muted">{t("bots.noTrace")}</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th className="num">{t("debrief.round")}</th>
              <th>{t("debrief.company")}</th>
              <th>{t("bots.level")}</th>
              <th className="num">{t("bots.version")}</th>
              <th>{t("bots.mode")}</th>
              <th>{t("bots.rationale")}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={`${row.round}-${row.teamId}`}>
                <td className="num">{row.round}</td>
                <td>{row.company}</td>
                <td>
                  {row.level} · {row.personality}
                </td>
                <td className="num">{row.profileVersion}</td>
                <td>{t(`bots.mode${row.mode}`)}</td>
                <td className="muted">{row.rationale ?? "—"}</td>
                <td>
                  <button className="ghost" onClick={() => void verify(row)}>
                    {t("bots.replay")}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {replay && (
        <p className={replay.identical ? "muted" : "error"}>
          {replay.identical
            ? t("bots.replaySame", { round: replay.round })
            : t("bots.replayDiffers", { round: replay.round })}
        </p>
      )}
    </section>
  );
}
