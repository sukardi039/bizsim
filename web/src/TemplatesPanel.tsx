import { useCallback, useEffect, useState } from "react";
import { api, type TemplateRow } from "./api";
import { useI18n } from "./i18n";
import TemplateEditor from "./TemplateEditor";
import { describeError } from "./errors";

function errorText(error: unknown, t: (key: string, params?: Record<string, string | number>) => string): string {
  return describeError(error, t);
}

/**
 * Setup → Templates (S6a/S6b).
 *
 * The shipped template is global and read-only; a tenant copies it and owns the copy. Editing the
 * copy arrives with S6c, so this screen lists and duplicates — and says so, rather than showing
 * buttons that do nothing.
 */
export default function TemplatesPanel() {
  const { t } = useI18n();
  const [templates, setTemplates] = useState<TemplateRow[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  /** Which template is open in the editor; null shows the list. */
  const [editing, setEditing] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setTemplates(await api.templates());
    } catch (error) {
      setProblem(errorText(error, t));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const duplicate = async (template: TemplateRow) => {
    const name = window.prompt("Name for the copy", `${template.name} (copy)`);
    if (name === null) return;
    setBusy(template.key);
    setProblem(null);
    setNotice(null);
    try {
      const copy = await api.copyTemplate(template.key, name.trim() || undefined);
      setNotice(`Copied to "${copy.name}" (${copy.key}). It is a draft of your own; editing comes next.`);
      await load();
    } catch (error) {
      setProblem(errorText(error, t));
    } finally {
      setBusy(null);
    }
  };

  /**
   * D131: soft removal. Confirmed first, because the row leaves the library — and refused by the server when a
   * package, a part or a played run still points at the template, which the message explains.
   */
  const remove = async (template: TemplateRow) => {
    if (!window.confirm(t("templates.deleteConfirm", { name: template.name }))) return;
    setBusy(template.key);
    setProblem(null);
    setNotice(null);
    try {
      await api.deleteTemplate(template.key);
      setNotice(t("templates.deleted", { name: template.name }));
      await load();
    } catch (error) {
      setProblem(errorText(error, t));
    } finally {
      setBusy(null);
    }
  };

  if (editing) {
    return <TemplateEditor templateKey={editing} onLeave={() => { setEditing(null); void load(); }} />;
  }

  return (
    <section className="card">
      <h2>{t("templates.title")}</h2>
      <p className="muted">{t("templates.intro")}</p>
      {notice && <p className="banner good">{notice}</p>}
      {problem && <p className="error">{problem}</p>}
      <table>
        <thead>
          <tr>
            <th>{t("templates.title")}</th>
            <th>{t("templates.owner")}</th>
            <th>{t("templates.status")}</th>
            <th className="num">{t("templates.products")}</th>
            <th className="num">{t("templates.rounds")}</th>
            <th>{t("templates.basedOn")}</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {templates.map((template) => (
            <tr key={template.key}>
              <td>
                <button className="link" onClick={() => setEditing(template.key)}>
                  {template.name}
                </button>
                <small className="muted" style={{ display: "block" }}>{template.key}</small>
              </td>
              <td>{template.tenantId == null ? t("templates.global") : t("templates.yours")}</td>
              <td>
                <span className={`tag ${template.status === "PUBLISHED" ? "round-closed" : ""}`}>
                  {template.status.toLowerCase()}
                </span>
              </td>
              <td className="num">{template.products}</td>
              <td className="num">{template.rounds}</td>
              <td className="muted">{template.basedOn ?? "—"}</td>
              <td className="num">
                <button onClick={() => duplicate(template)} disabled={busy === template.key}>
                  {busy === template.key ? t("templates.copying") : t("templates.duplicate")}
                </button>
                {/* D131: a shipped template is a shared reference and stays for ever; only a tenant's own
                    copy can be removed, and only when nothing points at it. */}
                {template.tenantId != null && (
                  <button
                    className="ghost"
                    onClick={() => void remove(template)}
                    disabled={busy === template.key}
                  >
                    {t("templates.delete")}
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
