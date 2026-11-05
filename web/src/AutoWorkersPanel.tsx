import { useEffect, useState } from "react";
import { api, ApiError, type BotRow } from "./api";
import { useI18n } from "./i18n";
import { describeError } from "./errors";

/**
 * The auto-worker library (D47): reusable opponents a builder creates once and seats in packages.
 *
 * <p>Level is how well it plays, personality is what it wants. The name carries the level as a
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
  const [personality, setPersonality] = useState<"CAUTIOUS" | "BALANCED" | "AGGRESSIVE">(
    "BALANCED",
  );

  const load = async () => {
    try {
      setBots(await api.bots());
    } catch (error) {
      setProblem(describeError(error, t));
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const create = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!name.trim()) return;
    setBusy(true);
    setProblem(null);
    try {
      await api.createBot({ name: name.trim(), level, personality });
      setName("");
      await load();
    } catch (error) {
      setProblem(error instanceof ApiError ? describeError(error, t) : String(error));
    } finally {
      setBusy(false);
    }
  };

  const change = async (bot: BotRow, next: Partial<{ level: string; personality: string }>) => {
    setProblem(null);
    try {
      await api.updateBot(bot.id, {
        name: bot.name,
        level: (next.level ?? bot.level) as string,
        personality: (next.personality ?? bot.personality) as string,
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
          {t("bots.level")}
          <select value={level} onChange={(e) => setLevel(e.target.value as typeof level)}>
            <option value="LOW">{t("bots.levelLow")}</option>
            <option value="MID">{t("bots.levelMid")}</option>
            <option value="HIGH">{t("bots.levelHigh")}</option>
          </select>
        </label>
        <label>
          {t("bots.personality")}
          <select
            value={personality}
            onChange={(e) => setPersonality(e.target.value as typeof personality)}
          >
            <option value="CAUTIOUS">{t("bots.cautious")}</option>
            <option value="BALANCED">{t("bots.balanced")}</option>
            <option value="AGGRESSIVE">{t("bots.aggressive")}</option>
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
              <th>{t("bots.level")}</th>
              <th>{t("bots.personality")}</th>
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
                  <select value={bot.level} onChange={(e) => void change(bot, { level: e.target.value })}>
                    <option value="LOW">{t("bots.levelLow")}</option>
                    <option value="MID">{t("bots.levelMid")}</option>
                    <option value="HIGH">{t("bots.levelHigh")}</option>
                  </select>
                </td>
                <td>
                  <select
                    value={bot.personality}
                    onChange={(e) => void change(bot, { personality: e.target.value })}
                  >
                    <option value="CAUTIOUS">{t("bots.cautious")}</option>
                    <option value="BALANCED">{t("bots.balanced")}</option>
                    <option value="AGGRESSIVE">{t("bots.aggressive")}</option>
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
      <p className="muted">{t("bots.seatHint")}</p>
    </section>
  );
}
