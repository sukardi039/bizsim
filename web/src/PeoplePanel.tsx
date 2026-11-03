import { useCallback, useEffect, useState } from "react";
import { api, ApiError, type UserRow } from "./api";

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    const details = error.details;
    return details.length ? details.join(" · ") : error.message;
  }
  return error instanceof Error ? error.message : "unexpected error";
}

/**
 * Setup → People.
 *
 * An account here is a person, not a seat (D22): it exists before any session and outlives them.
 * A trainee who has already played cannot be deleted — their decisions reference the account — so
 * the tool for "they left" is deactivation, which stops the login and keeps the history.
 */
export default function PeoplePanel() {
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
      setProblem(errorText(error));
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
      setProblem(errorText(error));
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
      setProblem(errorText(error));
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
      setProblem(errorText(error));
    } finally {
      setBusy(null);
    }
  };

  return (
    <>
      <section className="card">
        <h2>People</h2>
        <p className="muted">
          Trainee accounts belong to the person and outlive a session: seat them in as many sessions as
          you like from the Sessions view. A trainee who has already played is <em>deactivated</em>, not
          deleted — their results stay readable.
        </p>
        {notice && <p className="banner good">{notice}</p>}
        {problem && <p className="error">{problem}</p>}
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Email</th>
              <th>Role</th>
              <th>Status</th>
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
                    {user.active ? "active" : "deactivated"}
                  </span>
                </td>
                <td className="num">
                  {user.role === "PLAYER" && (
                    <span className="buttons">
                      <button onClick={() => toggle(user)} disabled={busy === user.id}>
                        {user.active ? "Deactivate" : "Activate"}
                      </button>
                      <button className="danger" onClick={() => remove(user)} disabled={busy === user.id}>
                        Delete
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
        <h2>Add a trainee</h2>
        <form className="seat-form" onSubmit={create}>
          <label>
            Name
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
              required
            />
          </label>
          <button type="submit" disabled={busy === "new" || !email.trim() || secret.length < 8}>
            {busy === "new" ? "Creating…" : "Create account"}
          </button>
        </form>
      </section>
    </>
  );
}
