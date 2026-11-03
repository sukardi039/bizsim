import { useCallback, useEffect, useState } from "react";
import { api, type UserRow } from "./api";
import { useI18n } from "./i18n";
import { describeError } from "./errors";

function errorText(error: unknown, t: (key: string, params?: Record<string, string | number>) => string): string {
  return describeError(error, t);
}

/**
 * Setup → People.
 *
 * An account here is a person, not a seat (D22): it exists before any session and outlives them.
 * A trainee who has already played cannot be deleted — their decisions reference the account — so
 * the tool for "they left" is deactivation, which stops the login and keeps the history.
 */
export default function PeoplePanel() {
  const { t } = useI18n();
  const [users, setUsers] = useState<UserRow[]>([]);
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [secret, setSecret] = useState("");
  const [busy, setBusy] = useState<number | "new" | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setUsers(await api.users());
    } catch (error) {
      setProblem(errorText(error, t));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const create = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy("new");
    setProblem(null);
    setNotice(null);
    try {
      const created = await api.createUser(email.trim(), displayName.trim() || email.trim(), secret);
      setNotice(`${created.email} can now sign in. Seat them on a company in the Sessions view.`);
      setEmail("");
      setDisplayName("");
      setSecret("");
      await load();
    } catch (error) {
      setProblem(errorText(error, t));
    } finally {
      setBusy(null);
    }
  };

  const toggle = async (user: UserRow) => {
    setBusy(user.id);
    setProblem(null);
    setNotice(null);
    try {
      await api.setUserActive(user.id, !user.active);
      setNotice(`${user.email} is now ${user.active ? "deactivated" : "active"}.`);
      await load();
    } catch (error) {
      setProblem(errorText(error, t));
    } finally {
      setBusy(null);
    }
  };

  const remove = async (user: UserRow) => {
    if (!window.confirm(`Delete ${user.email}? Only possible because they have never played.`)) return;
    setBusy(user.id);
    setProblem(null);
    setNotice(null);
    try {
      await api.deleteUser(user.id);
      setNotice(`${user.email} was deleted.`);
      await load();
    } catch (error) {
      setProblem(errorText(error, t));
    } finally {
      setBusy(null);
    }
  };

  return (
    <>
      <section className="card">
        <h2>{t("people.title")}</h2>
        <p className="muted">
          {t("people.hint")}
        </p>
        {notice && <p className="banner good">{notice}</p>}
        {problem && <p className="error">{problem}</p>}
        <table>
          <thead>
            <tr>
              <th>{t("people.name")}</th>
              <th>{t("people.email")}</th>
              <th>{t("people.role")}</th>
              <th>{t("people.status")}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {users.map((user) => (
              <tr key={user.id}>
                <td>{user.displayName}</td>
                <td>{user.email}</td>
                <td className="muted">{user.role.toLowerCase()}</td>
                <td>
                  <span className={`tag ${user.active ? "round-closed" : ""}`}>
                    {user.active ? t("people.active") : t("people.deactivated")}
                  </span>
                </td>
                <td className="num">
                  {user.role === "PLAYER" && (
                    <span className="buttons">
                      <button onClick={() => toggle(user)} disabled={busy === user.id}>
                        {user.active ? t("people.deactivate") : t("people.activate")}
                      </button>
                      <button className="danger" onClick={() => remove(user)} disabled={busy === user.id}>
                        {t("people.delete")}
                      </button>
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section className="card">
        <h2>{t("people.add")}</h2>
        <form className="seat-form" onSubmit={create}>
          <label>
            {t("people.name")}
            <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="Ada" />
          </label>
          <label>
            {t("people.email")}
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
              required
            />
          </label>
          <button type="submit" disabled={busy === "new" || !email.trim() || secret.length < 8}>
            {busy === "new" ? t("people.creating") : t("people.create")}
          </button>
        </form>
      </section>
    </>
  );
}
