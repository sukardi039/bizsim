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
  // Staff roles are an admin's call (only an admin may mint a facilitator or a builder), so the
  // selector appears for admins only; everyone else can still add trainees.
  const [role, setRole] = useState("PLAYER");
  const [canMintStaff, setCanMintStaff] = useState(false);
  const [busy, setBusy] = useState<number | "new" | null>(null);
  /** Off by default: the roster is for assigning people, and someone already in a company is noise. */
  const [showAssigned, setShowAssigned] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [list, me] = await Promise.all([api.users(showAssigned), api.me()]);
      setUsers(list);
      setCanMintStaff(me.role === "ADMIN");
    } catch (error) {
      setProblem(errorText(error, t));
    }
  }, [t, showAssigned]);

  useEffect(() => {
    void load();
  }, [load]);

  const create = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy("new");
    setProblem(null);
    setNotice(null);
    try {
      const created = await api.createUser(
        email.trim(),
        displayName.trim() || email.trim(),
        secret,
        role,
      );
      // Say what this account can actually do: a builder is not seated anywhere.
      setNotice(
        created.role === "BUILDER"
          ? t("people.createdBuilder", { email: created.email })
          : created.role === "FACILITATOR"
            ? t("people.createdFacilitator", { email: created.email })
            : t("people.createdPlayer", { email: created.email }),
      );
      setEmail("");
      setDisplayName("");
      setSecret("");
      setRole("PLAYER");
      await load();
    } catch (error) {
      setProblem(errorText(error, t));
    } finally {
      setBusy(null);
    }
  };

  /** Fix a role without deleting the account: the commonest onboarding mistake. */
  const changeRole = async (user: UserRow, role: string) => {
    setBusy(user.id);
    setProblem(null);
    setNotice(null);
    try {
      const updated = await api.setUserRole(user.id, role);
      setNotice(t("people.roleChanged", { email: updated.email, role: updated.role.toLowerCase() }));
      await load();
    } catch (error) {
      setProblem(errorText(error, t));
      await load(); // put the select back to the stored value
    } finally {
      setBusy(null);
    }
  };

  /** Set a new password for an account. The one thing that cannot be done without a reset. */
  const resetPassword = async (user: UserRow) => {
    const next = window.prompt(t("people.passwordPrompt", { email: user.email }));
    if (next === null) return;
    if (next.length < 8) {
      setProblem(t("people.passwordTooShort"));
      return;
    }
    setBusy(user.id);
    setProblem(null);
    setNotice(null);
    try {
      await api.resetUserPassword(user.id, next);
      setNotice(t("people.passwordChanged", { email: user.email }));
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
        <label className="inline">
          <input
            type="checkbox"
            checked={showAssigned}
            onChange={(e) => setShowAssigned(e.target.checked)}
          />
          {t("people.showAssigned")}
        </label>
        <p className="muted">
          <small>{t("people.showAssignedHint")}</small>
        </p>
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
                <td className="muted">
                  {canMintStaff ? (
                    <select
                      value={user.role}
                      onChange={(e) => void changeRole(user, e.target.value)}
                      disabled={busy === user.id}
                    >
                      <option value="PLAYER">{t("people.player")}</option>
                      <option value="FACILITATOR">{t("people.facilitator")}</option>
                      <option value="BUILDER">{t("people.builder")}</option>
                      <option value="ADMIN">Admin</option>
                    </select>
                  ) : (
                    user.role.toLowerCase()
                  )}
                </td>
                <td>
                  <span className={`tag ${user.active ? "round-closed" : ""}`}>
                    {user.active ? t("people.active") : t("people.deactivated")}
                  </span>
                </td>
                <td className="num">
                  {/* Staff may fix a trainee's credential; a staff account's password stays an
                      administrator's business (server-enforced). */}
                  {(canMintStaff || user.role === "PLAYER") && (
                    <button onClick={() => resetPassword(user)} disabled={busy === user.id}>
                      {t("people.setPassword")}
                    </button>
                  )}
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
          {/* Staff roles are an admin's call, so only an admin sees the selector (server-enforced). */}
          {canMintStaff && (
            <label>
              {t("people.role")}
              <select value={role} onChange={(e) => setRole(e.target.value)}>
                <option value="PLAYER">{t("people.player")}</option>
                <option value="FACILITATOR">{t("people.facilitator")}</option>
                <option value="BUILDER">{t("people.builder")}</option>
              </select>
            </label>
          )}
          <button type="submit" disabled={busy === "new" || !email.trim() || secret.length < 8}>
            {busy === "new" ? t("people.creating") : t("people.create")}
          </button>
        </form>
      </section>
    </>
  );
}
