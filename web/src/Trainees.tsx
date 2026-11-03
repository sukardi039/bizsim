import { useState } from "react";
import { api, ApiError, type Run, type Team } from "./api";

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    const details = error.details;
    return details.length ? details.join(" · ") : error.message;
  }
  return error instanceof Error ? error.message : "unexpected error";
}

/**
 * One row per company: who is seated, and a form that creates the account and seats it in one step.
 *
 * <p>Both halves are needed: an account alone cannot see a company, and a seat alone needs an account
 * that already exists. The facilitator hands the trainee the email and the initial credential they
 * typed here; there is no email delivery in this product, deliberately.
 */
function SeatRow({ run, team, seated, onSeated }: {
  run: Run;
  team: Team;
  seated: { displayName: string; roleKey: string }[];
  onSeated: () => void;
}) {
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [secret, setSecret] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setProblem(null);
    setDone(null);
    try {
      const created = await api.createUser(email.trim(), displayName.trim() || email.trim(), secret);
      await api.addPlayer(run.id, team.id, created.id);
      setDone(`${created.email} can now sign in and sees ${team.companyName}.`);
      setEmail("");
      setDisplayName("");
      setSecret("");
      onSeated();
    } catch (error) {
      setProblem(errorText(error));
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
            ? "no trainee yet"
            : seated.map((p) => `${p.displayName} (${p.roleKey.toLowerCase()})`).join(" · ")}
        </span>
      </div>
      <form className="seat-form" onSubmit={submit}>
        <label>
          Trainee name
          <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="Ada" />
        </label>
        <label>
          Email they sign in with
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="ada@school.test"
            required
          />
        </label>
        <label>
          Initial password (8+ characters)
          <input
            type="text"
            value={secret}
            onChange={(e) => setSecret(e.target.value)}
            placeholder="hand this to the trainee"
            minLength={8}
            required
          />
        </label>
        <button type="submit" disabled={busy || !email.trim() || secret.length < 8}>
          {busy ? "Creating…" : "Create and seat"}
        </button>
      </form>
      {done && <p className="banner good">{done}</p>}
      {problem && <p className="error">{problem}</p>}
    </div>
  );
}

export default function Trainees({ run, onChanged }: { run: Run; onChanged: () => void }) {
  return (
    <section className="card">
      <h2>Trainees</h2>
      <p className="muted">
        Each company needs one account. Create it here, then hand the trainee the email and password —
        they sign in on the same page and land on their company's decision form.
      </p>
      {run.teams.map((team) => (
        <SeatRow
          key={team.id}
          run={run}
          team={team}
          seated={run.players.filter((p) => p.teamId === team.id)}
          onSeated={onChanged}
        />
      ))}
    </section>
  );
}
