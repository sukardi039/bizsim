import { useCallback, useEffect, useRef, useState } from "react";
import PlayerConsole from "./PlayerConsole";
import Trainees from "./Trainees";
import TemplatesPanel from "./TemplatesPanel";
import PeoplePanel from "./PeoplePanel";
import StartSession from "./StartSession";
import CompaniesPanel from "./CompaniesPanel";
import PackagesPanel from "./PackagesPanel";
import { useI18n, LOCALES, type Locale } from "./i18n";
import { describeError } from "./errors";
import {
  api,
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

function errorText(error: unknown, t: (key: string, params?: Record<string, string | number>) => string): string {
  return describeError(error, t);
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
        // A console diagnostic, not interface text: no translation needed here.
        console.warn("stream ticket failed", error);
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
  const { t } = useI18n();
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
      setProblem(errorText(error, t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="card login" onSubmit={submit}>
      <h1>{t("login.title")}</h1>
      <p className="muted">{t("login.subtitle")}</p>
      <label>
        {t("login.tenant")}
        <input value={tenantCode} onChange={(e) => setTenant(e.target.value)} autoComplete="organization" />
      </label>
      <label>
        {t("login.email")}
        <input value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" />
      </label>
      <label>
        {t("login.password")}
        <input
          type="password"
          value={secret}
          onChange={(e) => setSecret(e.target.value)}
          autoComplete="current-password"
        />
      </label>
      {problem && <p className="error">{problem}</p>}
      <button type="submit" disabled={busy}>
        {busy ? t("login.signingIn") : t("login.signIn")}
      </button>
    </form>
  );
}

/* ------------------------------------------------------------------ console */

export default function App() {
  const { t, adopt, locale, setLocale } = useI18n();
  const [signedIn, setSignedIn] = useState(Boolean(getToken()));
  // Who is signed in decides the screen: a trainee gets the decision form, everything else gets the
  // facilitator console. The role comes from the token, never from the client.
  const [identity, setIdentity] = useState<{ userId: number; role: string } | null>(null);
  const [identityProblem, setIdentityProblem] = useState<string | null>(null);
  /** Sessions drive a class; Templates is the setup workbench. Same shell, two views. */
  const [view, setView] = useState<"runs" | "packages" | "templates" | "companies" | "people">("runs");
  const [runs, setRuns] = useState<Run[]>([]);
  // Only the id is state. The run object is derived, so refreshing the list cannot produce a new
  // object identity and re-trigger the effects that fetch the scoreboard — that was an endless loop.
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const selected = runs.find((run) => run.id === selectedId) ?? null;
  const [board, setBoard] = useState<ScoreRow[]>([]);
  const [boardRound, setBoardRound] = useState<number | null>(null);
  const [feed, setFeed] = useState<FeedItem[]>([]);
  const [problem, setProblem] = useState<string | null>(null);


  // Ruling of 3 Nov 2026: preparing and running are separate jobs. A builder never loads sessions;
  // a facilitator never sees the builders' tools.
  const canRun = identity === null || identity.role !== "BUILDER";
  const canBuild = identity === null || identity.role === "BUILDER" || identity.role === "ADMIN";

  const refreshRuns = useCallback(async () => {
    if (!canRun) return;
    try {
      setRuns(await api.runs());
    } catch (error) {
      setProblem(errorText(error, t));
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

  // A builder lands in the setup workspace; a facilitator lands in sessions.
  useEffect(() => {
    if (identity?.role === "BUILDER") setView("packages");
    if (identity?.role === "FACILITATOR") setView("runs");
  }, [identity]);

  useEffect(() => {
    if (selectedId !== null) void refreshBoard(selectedId);
  }, [selectedId, refreshBoard]);

  useEffect(() => {
    if (!signedIn) {
      setIdentity(null);
      setIdentityProblem(null);
      return;
    }
    let cancelled = false;
    api
      .me()
      .then((me) => {
        if (cancelled) return;
        setIdentity({ userId: me.userId, role: me.role });
        adopt(me.locale);
      })
      .catch((error) => {
        if (!cancelled) setIdentityProblem(errorText(error, t));
      });
    return () => {
      cancelled = true;
    };
  }, [signedIn]);

  const signOut = () => {
    clearToken();
    setSignedIn(false);
    setIdentity(null);
  };

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

  const act = async (action: "next" | "close") => {
    if (!selected) return;
    setProblem(null);
    try {
      if (action === "next") await api.nextRound(selected.id);
      else await api.closeRound(selected.id);
      await refreshRuns();
      await refreshBoard(selected.id);
    } catch (error) {
      setProblem(errorText(error, t));
    }
  };

  /**
   * Deletes the whole simulation. A confirm dialog, not a typed name: this is a pilot tool, the data
   * is a rehearsal, and two clicks is the right friction. The list refresh is what proves it worked.
   */
  const remove = async () => {
    if (!selected) return;
    const sure = window.confirm(t("run.deleteConfirm", { id: selected.id }));
    if (!sure) return;
    setProblem(null);
    try {
      await api.deleteRun(selected.id);
      setSelectedId(null);
      setBoard([]);
      setBoardRound(null);
      setFeed([]);
      await refreshRuns();
    } catch (error) {
      setProblem(errorText(error, t));
    }
  };

  if (!signedIn) return <Login onDone={() => setSignedIn(true)} />;
  if (identityProblem) {
    return (
      <div className="card login">
        <p className="error">{identityProblem}</p>
        <button onClick={signOut}>Sign in again</button>
      </div>
    );
  }
  if (!identity) {
    return (
      <div className="card login">
        <p className="muted">Signing in…</p>
      </div>
    );
  }
  if (identity.role === "PLAYER") {
    return <PlayerConsole userId={identity.userId} onSignOut={signOut} />;
  }

  return (
    <div className="shell">
      <aside>
        <div className="brand">
          <strong>bizsim</strong>
          <span className="brand-right">
            <select
              className="lang"
              value={locale}
              onChange={(e) => setLocale(e.target.value as Locale)}
              aria-label="language"
            >
              {LOCALES.map((l) => (
                <option key={l.code} value={l.code}>
                  {l.label}
                </option>
              ))}
            </select>
            <button className="link" onClick={signOut}>
              {t("app.signOut")}
            </button>
          </span>
        </div>

        <div className="nav">
          {canRun && (
            <button className={view === "runs" ? "active" : ""} onClick={() => setView("runs")}>
              {t("nav.sessions")}
            </button>
          )}
          {canBuild && (
            <>
              <button className={view === "packages" ? "active" : ""} onClick={() => setView("packages")}>
                {t("nav.packages")}
              </button>
              <button className={view === "templates" ? "active" : ""} onClick={() => setView("templates")}>
                {t("nav.templates")}
              </button>
              <button className={view === "companies" ? "active" : ""} onClick={() => setView("companies")}>
                {t("nav.companies")}
              </button>
            </>
          )}
          <button className={view === "people" ? "active" : ""} onClick={() => setView("people")}>
            {t("nav.people")}
          </button>
        </div>


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

        {view === "packages" ? (
          <PackagesPanel />
        ) : view === "companies" ? (
          <CompaniesPanel />
        ) : view === "people" ? (
          <PeoplePanel />
        ) : view === "templates" ? (
          <TemplatesPanel />
        ) : !selected ? (
          <StartSession
            onStarted={(run) => {
              setSelectedId(run.id);
              void refreshRuns();
            }}
          />
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
                  {t("run.openNext")}
                </button>
                <button className="primary" onClick={() => act("close")} disabled={selected.status !== "OPEN"}>
                  {t("run.compute")}
                </button>
                <button className="danger" onClick={remove}>
                  {t("run.delete")}
                </button>
              </div>
            </header>

            <section className="card">
              <h2>{t("score.title")}{boardRound !== null ? ` · ${t("score.round", { round: boardRound })}` : ""}</h2>
              {board.length === 0 ? (
                <p className="muted">{t("score.empty")}</p>
              ) : (
                <table>
                  <thead>
                    <tr>
                      <th className="num">{t("score.rank")}</th>
                      <th>{t("score.company")}</th>
                      <th className="num">{t("score.profit")}</th>
                      <th className="num">{t("score.cash")}</th>
                      <th className="num">{t("score.share")}</th>
                      <th className="num">{t("score.stock")}</th>
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

            <Trainees run={selected} onChanged={refreshRuns} />

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
