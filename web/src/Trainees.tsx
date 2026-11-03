import { useCallback, useEffect, useState } from "react";
import { api, type Run, type Team, type UserRow } from "./api";
import { useI18n } from "./i18n";
import { describeError } from "./errors";

function errorText(error: unknown, t: (key: string, params?: Record<string, string | number>) => string): string {
  return describeError(error, t);
}

/**
 * Seating for one company: an existing account (D22: the person already exists) or a new one.
 *
 * Both halves are needed. Preparing a roster in Setup → People and seating it here is the normal
 * path; creating an account on the spot stays as the walk-in fallback, because a real class always
 * has one person who is not on the list.
 */
function SeatRow({ run, team, seated, available, onChanged }: {
  run: Run;
  team: Team;
  seated: { displayName: string; roleKey: string }[];
  available: UserRow[];
  onChanged: () => void;
}) {
  const { t } = useI18n();
  const [pick, setPick] = useState("");
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [secret, setSecret] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const seatExisting = async () => {
    if (!pick) return;
    setBusy(true);
    setProblem(null);
    setDone(null);
    try {
      await api.addPlayer(run.id, team.id, Number(pick));
      setDone(t("trainees.seated"));
      setPick("");
      onChanged();
    } catch (error) {
      setProblem(errorText(error, t));
    } finally {
      setBusy(false);
    }
  };

  const create = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setProblem(null);
    setDone(null);
    try {
      const created = await api.createUser(email.trim(), displayName.trim() || email.trim(), secret);
      await api.addPlayer(run.id, team.id, created.id);
      setDone(`${created.email} created and seated on ${team.companyName}.`);
      setEmail("");
      setDisplayName("");
      setSecret("");
      onChanged();
    } catch (error) {
      setProblem(errorText(error, t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="seat">
      <div className="seat-head">
        <strong>{team.companyName}</strong>
        <span className="muted">
          {seated.length === 0
            ? t("trainees.none")
            : seated.map((p) => `${p.displayName} (${p.roleKey.toLowerCase()})`).join(" · ")}
        </span>
      </div>

      {available.length > 0 && (
        <div className="seat-form">
          <label style={{ gridColumn: "span 3" }}>
            {t("trainees.seatExisting")}
            <select value={pick} onChange={(e) => setPick(e.target.value)}>
              <option value="">{t("trainees.pick")}</option>
              {available.map((user) => (
                <option key={user.id} value={user.id}>
                  {user.displayName} · {user.email}
                </option>
              ))}
            </select>
          </label>
          <button type="button" onClick={seatExisting} disabled={!pick || busy}>
            {busy ? t("trainees.seating") : t("trainees.seat")}
          </button>
        </div>
      )}

      <form className="seat-form" onSubmit={create}>
        <label>
          {t("trainees.new")}
          <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder={t("trainees.namePlaceholder")} />
        </label>
        <label>
          {t("trainees.email")}
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="ada@school.test"
            required
          />
        </label>
        <label>
          {t("trainees.password")}
          <input
            type="text"
            value={secret}
            onChange={(e) => setSecret(e.target.value)}
            placeholder={t("trainees.passwordPlaceholder")}
            required
          />
        </label>
        <button type="submit" disabled={busy || !email.trim() || secret.length < 8}>
          {t("trainees.createSeat")}
        </button>
      </form>

      {done && <p className="banner good">{done}</p>}
      {problem && <p className="error">{problem}</p>}
    </div>
  );
}

export default function Trainees({ run, onChanged }: { run: Run; onChanged: () => void }) {
  const { t } = useI18n();
  const [users, setUsers] = useState<UserRow[]>([]);

  const loadUsers = useCallback(async () => {
    try {
      setUsers(await api.users());
    } catch {
      setUsers([]); // the roster is a convenience; seating still works without it
    }
  }, []);

  useEffect(() => {
    void loadUsers();
  }, [loadUsers, run.id]);

  // An account already seated in this run cannot be seated twice, and a deactivated one cannot sign in.
  const unseated = users.filter(
    (user) =>
      user.role === "PLAYER" &&
      user.active &&
      !run.players.some((seat) => seat.userId === user.id),
  );

  return (
    <section className="card">
      <h2>{t("trainees.title")}</h2>
      <p className="muted">
        {t("trainees.hint")}
      </p>
      {run.teams.map((team) => (
        <SeatRow
          key={team.id}
          run={run}
          team={team}
          seated={run.players.filter((p) => p.teamId === team.id)}
          available={unseated}
          onChanged={() => {
            void loadUsers();
            onChanged();
          }}
        />
      ))}
    </section>
  );
}
