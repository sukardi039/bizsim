import { useCallback, useEffect, useRef, useState } from "react";
import {
  api,
  ApiError,
  clearToken,
  eventStreamUrl,
  getToken,
  setToken,
  type Run,
  type ScoreRow,
} from "./api";

/* ------------------------------------------------------------------ helpers */

const money = (value: number) =>
  new Intl.NumberFormat("en-US", { maximumFractionDigits: 0 }).format(value);

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    const details = error.details;
    return details.length ? details.join(" · ") : error.message;
  }
  return error instanceof Error ? error.message : "unexpected error";
}

interface FeedItem {
  id: number;
  type: string;
  text: string;
  at: string;
}

/**
 * Subscribes to a run's server-sent events. Each connection needs a fresh single-use ticket, so the
 * hook asks for one every time it (re)connects — that is also why a dropped connection recovers.
 */
function useRunStream(runId: number | null, onEvent: (type: string, data: any) => void) {
  const handler = useRef(onEvent);
  handler.current = onEvent;

  useEffect(() => {
    if (!runId) return;
    let source: EventSource | null = null;
    let retry: number | undefined;
    let stopped = false;

    const connect = async () => {
      try {
        const { ticket } = await api.streamTicket(runId);
        if (stopped) return;
        source = new EventSource(eventStreamUrl(runId, ticket));
        for (const name of ["connected", "round.opened", "round.closed", "decision.submitted"]) {
          source.addEventListener(name, (event) => {
            const data = (event as MessageEvent).data;
            handler.current(name, data ? JSON.parse(data) : {});
          });
        }
        source.onerror = () => {
          source?.close();
          if (!stopped) retry = window.setTimeout(connect, 2000);
        };
      } catch (error) {
        if (!stopped) retry = window.setTimeout(connect, 2000);
        console.warn("stream ticket failed", errorText(error));
      }
    };

    connect();
    return () => {
      stopped = true;
      window.clearTimeout(retry);
      source?.close();
    };
  }, [runId]);
}

/* ------------------------------------------------------------------ login */

function Login({ onDone }: { onDone: () => void }) {
  const [tenantCode, setTenant] = useState("demo");
  const [email, setEmail] = useState("trainer@demo.test");
  const [secret, setSecret] = useState("demo1234");
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setProblem(null);
    try {
      const { token } = await api.login(tenantCode, email, secret);
      setToken(token);
      onDone();
    } catch (error) {
      setProblem(errorText(error));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="card login" onSubmit={submit}>
      <h1>bizsim</h1>
      <p className="muted">Facilitator console</p>
      <label>
        Tenant
        <input value={tenantCode} onChange={(e) => setTenant(e.target.value)} autoComplete="organization" />
      </label>
      <label>
        Email
        <input value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" />
      </label>
      <label>
        Password
        <input
          type="password"
          value={secret}
          onChange={(e) => setSecret(e.target.value)}
          autoComplete="current-password"
        />
      </label>
      {problem && <p className="error">{problem}</p>}
      <button type="submit" disabled={busy}>
        {busy ? "Signing in…" : "Sign in"}
      </button>
    </form>
  );
}

/* ------------------------------------------------------------------ console */

export default function App() {
  const [signedIn, setSignedIn] = useState(Boolean(getToken()));
  const [runs, setRuns] = useState<Run[]>([]);
  // Only the id is state. The run object is derived, so refreshing the list cannot produce a new
  // object identity and re-trigger the effects that fetch the scoreboard — that was an endless loop.
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const selected = runs.find((run) => run.id === selectedId) ?? null;
  const [board, setBoard] = useState<ScoreRow[]>([]);
  const [boardRound, setBoardRound] = useState<number | null>(null);
  const [feed, setFeed] = useState<FeedItem[]>([]);
  const [problem, setProblem] = useState<string | null>(null);

  const [name, setName] = useState("Pilot session");
  const [teamCount, setTeamCount] = useState(3);
  const [cycles, setCycles] = useState(2);

  const refreshRuns = useCallback(async () => {
    try {
      setRuns(await api.runs());
    } catch (error) {
      setProblem(errorText(error));
    }
  }, []);

  const refreshBoard = useCallback(async (runId: number) => {
    try {
      const rows = await api.scoreboard(runId);
      setBoard(rows);
      setBoardRound(rows.length > 0 ? rows[0].round : null);
    } catch {
      setBoard([]); // no round computed yet
      setBoardRound(null);
    }
  }, []);

  useEffect(() => {
    if (signedIn) void refreshRuns();
  }, [signedIn, refreshRuns]);

  useEffect(() => {
    if (selectedId !== null) void refreshBoard(selectedId);
  }, [selectedId, refreshBoard]);

  const note = useCallback((type: string, data: any) => {
    const text =
      type === "round.opened"
        ? `round ${data.round} opened`
        : type === "round.closed"
          ? `round ${data.round} computed for ${data.teams?.length ?? 0} companies`
          : type === "decision.submitted"
            ? `company ${data.teamId} submitted its decision`
            : "connected";
    setFeed((items) => [{ id: Date.now() + Math.random(), type, text, at: new Date().toLocaleTimeString() }, ...items].slice(0, 40));
    if (type === "round.closed" || type === "round.opened") {
      void refreshRuns();
      if (selectedId !== null) void refreshBoard(selectedId);
    }
  }, [refreshRuns, refreshBoard, selectedId]);

  useRunStream(selectedId, note);

  const create = async (event: React.FormEvent) => {
    event.preventDefault();
    setProblem(null);
    try {
      const teamNames = Array.from({ length: teamCount }, (_, i) => `Company ${i + 1}`);
      const run = await api.createRun(name, "standard-v1", teamNames, cycles);
      setSelectedId(run.id);
      await refreshRuns();
    } catch (error) {
      setProblem(errorText(error));
    }
  };

  const act = async (action: "next" | "close") => {
    if (!selected) return;
    setProblem(null);
    try {
      if (action === "next") await api.nextRound(selected.id);
      else await api.closeRound(selected.id);
      await refreshRuns();
      await refreshBoard(selected.id);
    } catch (error) {
      setProblem(errorText(error));
    }
  };

  if (!signedIn) return <Login onDone={() => setSignedIn(true)} />;

  return (
    <div className="shell">
      <aside>
        <div className="brand">
          <strong>bizsim</strong>
          <button className="link" onClick={() => { clearToken(); setSignedIn(false); }}>
            sign out
          </button>
        </div>

        <form className="card" onSubmit={create}>
          <h2>New run</h2>
          <label>
            Name
            <input value={name} onChange={(e) => setName(e.target.value)} />
          </label>
          <div className="row">
            <label>
              Companies (1–10)
              <input
                type="number"
                min={1}
                max={10}
                value={teamCount}
                onChange={(e) => setTeamCount(Number(e.target.value))}
              />
            </label>
            <label>
              Cycles
              <input
                type="number"
                min={1}
                max={6}
                value={cycles}
                onChange={(e) => setCycles(Number(e.target.value))}
              />
            </label>
          </div>
          <button type="submit">Create</button>
        </form>

        <h2>Runs</h2>
        <ul className="runs">
          {runs.map((run) => (
            <li key={run.id} className={selected?.id === run.id ? "active" : ""}>
              <button className="link" onClick={() => setSelectedId(run.id)}>
                <span>{run.id} · {run.teams.length} companies</span>
                <small>
                  {run.status} · round {run.currentRound}/{run.rounds}
                </small>
              </button>
            </li>
          ))}
          {runs.length === 0 && <li className="muted">no runs yet</li>}
        </ul>
      </aside>

      <main>
        {problem && <p className="error">{problem}</p>}

        {!selected ? (
          <p className="muted">Select a run, or create one.</p>
        ) : (
          <>
            <header className="run-head">
              <div>
                <h1>Run {selected.id}</h1>
                <p className="muted">
                  {selected.status} · round {selected.currentRound} of {selected.rounds}
                  {" · "}
                  {selected.cycles} cycles of {selected.rounds / Math.max(1, selected.cycles)}
                </p>
              </div>
              <div className="buttons">
                <button onClick={() => act("next")} disabled={selected.currentRound >= selected.rounds}>
                  Open next round
                </button>
                <button className="primary" onClick={() => act("close")} disabled={selected.status !== "OPEN"}>
                  Compute round
                </button>
              </div>
            </header>

            <section className="card">
              <h2>Scoreboard{boardRound !== null ? ` · round ${boardRound}` : ""}</h2>
              {board.length === 0 ? (
                <p className="muted">No round computed yet.</p>
              ) : (
                <table>
                  <thead>
                    <tr>
                      <th className="num">#</th>
                      <th>Company</th>
                      <th className="num">Profit</th>
                      <th className="num">Cash</th>
                      <th className="num">Share</th>
                      <th className="num">Stock</th>
                    </tr>
                  </thead>
                  <tbody>
                    {board.map((row, index) => (
                        <tr key={row.team_id}>
                          <td className="num">{index + 1}</td>
                          <td>{row.company_name}</td>
                          <td className={row.profit < 0 ? "num negative" : "num positive"}>{money(row.profit)}</td>
                          <td className="num">{money(row.cash)}</td>
                          <td className="num">{row.market_share.toFixed(1)}%</td>
                          <td className="num">{money(row.stock)}</td>
                        </tr>
                      ))}
                  </tbody>
                </table>
              )}
            </section>

            <section className="card">
              <h2>Live</h2>
              <ul className="feed">
                {feed.map((item) => (
                  <li key={item.id}>
                    <time>{item.at}</time>
                    <span className={`tag ${item.type.replace(".", "-")}`}>{item.type}</span>
                    {item.text}
                  </li>
                ))}
                {feed.length === 0 && <li className="muted">waiting for events…</li>}
              </ul>
            </section>
          </>
        )}
      </main>
    </div>
  );
}
