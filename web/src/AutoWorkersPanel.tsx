import { useEffect, useState } from "react";
import { api, ApiError, type BotRow } from "./api";
import { useI18n } from "./i18n";
import { describeError } from "./errors";

/**
 * The auto-worker library (D47): reusable opponents a builder creates once and seats in packages.
 *
 * <p>Strategy is what it is trying to be, level is how well it plays it (D130 retired personality). The name
 * carries the level as a
 * suffix because that is what a scoreboard shows — but the suffix is generated from the stored level,
 * so editing the level renames the brain rather than the other way round.
 */
export default function AutoWorkersPanel() {
  const { t } = useI18n();
  const [bots, setBots] = useState<BotRow[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [name, setName] = useState("");
  const [level, setLevel] = useState<"LOW" | "MID" | "HIGH">("MID");
  /** D135: the positions come from limits.json via /api/limits — this screen does not restate them. */
  const [positions, setPositions] = useState<string[]>([]);
  const [strategy, setStrategy] = useState("");

  const load = async () => {
    try {
      setBots(await api.bots());
    } catch (error) {
      setProblem(describeError(error, t));
    }
  };

  useEffect(() => {
    void load();
    // The picker renders whatever the one authority lists; a failure leaves it empty and blocks creation
    // rather than inventing a position the server would refuse.
    api
      .limits()
      .then((limits) => {
        const codes = limits.strategies.positions.map((position) => position.code);
        setPositions(codes);
        setStrategy((current) => current || codes[0] || "");
      })
      .catch((error) => setProblem(describeError(error, t)));
  }, []);

  const create = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!name.trim()) return;
    setBusy(true);
    setProblem(null);
    try {
      await api.createBot({ name: name.trim(), level, strategy });
      setName("");
      await load();
    } catch (error) {
      setProblem(error instanceof ApiError ? describeError(error, t) : String(error));
    } finally {
      setBusy(false);
    }
  };

  const change = async (
    bot: BotRow,
    next: Partial<{ level: string; strategy: string }>,
  ) => {
    setProblem(null);
    try {
      await api.updateBot(bot.id, {
        name: bot.name,
        level: (next.level ?? bot.level) as string,
        strategy: (next.strategy ?? bot.strategy) as string,
      });
      await load();
    } catch (error) {
      setProblem(error instanceof ApiError ? describeError(error, t) : String(error));
    }
  };

  const retire = async (bot: BotRow) => {
    setProblem(null);
    try {
      await api.retireBot(bot.id);
      await load();
    } catch (error) {
      setProblem(error instanceof ApiError ? describeError(error, t) : String(error));
    }
  };

  return (
    <section className="card">
      <h2>{t("bots.title")}</h2>
      <p className="muted">{t("bots.hint")}</p>
      {problem && <p className="error">{problem}</p>}

      <form className="inline-form" onSubmit={create}>
        <label>
          {t("bots.name")}
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Market 1" />
        </label>
        <label>
          {t("bots.strategy")}
          <select value={strategy} onChange={(e) => setStrategy(e.target.value as typeof strategy)}>
            {positions.map((code) => (
              <option key={code} value={code}>
                {t(`strategy.${code}`)}
              </option>
            ))}
          </select>
          {t("bots.level")}
          <select value={level} onChange={(e) => setLevel(e.target.value as typeof level)}>
            <option value="LOW">{t("bots.levelLow")}</option>
            <option value="MID">{t("bots.levelMid")}</option>
            <option value="HIGH">{t("bots.levelHigh")}</option>
          </select>
        </label>
        <button type="submit" disabled={busy || !name.trim()}>
          {t("bots.create")}
        </button>
      </form>

      {bots.length === 0 ? (
        <p className="muted">{t("bots.none")}</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>{t("bots.name")}</th>
              <th>{t("bots.strategy")}</th>
              <th>{t("bots.level")}</th>
              <th className="num">{t("bots.version")}</th>
              <th>{t("bots.state")}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {bots.map((bot) => (
              <tr key={bot.id}>
                <td>{bot.name}</td>
                <td>
                  <select
                    value={bot.strategy}
                    onChange={(e) => void change(bot, { strategy: e.target.value })}
                  >
                    {positions.map((code) => (
                      <option key={code} value={code}>
                        {t(`strategy.${code}`)}
                      </option>
                    ))}
                  </select>
                </td>
                <td>
                  <select value={bot.level} onChange={(e) => void change(bot, { level: e.target.value })}>
                    <option value="LOW">{t("bots.levelLow")}</option>
                    <option value="MID">{t("bots.levelMid")}</option>
                    <option value="HIGH">{t("bots.levelHigh")}</option>
                  </select>
                </td>
                <td className="num">{bot.version}</td>
                <td>{bot.active ? t("bots.active") : t("bots.retired")}</td>
                <td>
                  {bot.active && (
                    <button className="ghost" onClick={() => void retire(bot)}>
                      {t("bots.retire")}
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {/* D128: the strategies are a teaching taxonomy, not decoration — say what each one does. */}
      <p className="muted">{t("bots.strategyHint")}</p>
      <p className="muted">{t("bots.seatHint")}</p>
    </section>
  );
}
