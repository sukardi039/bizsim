import { useCallback, useEffect, useState } from "react";
import {
  api,
  type CompanyProfile,
  type SimulationPackage,
  type TemplateRow,
} from "./api";
import { describeError } from "./errors";
import { useI18n } from "./i18n";

/**
 * Setup → Packages (builder only).
 *
 * This is the artefact that crosses the boundary between the two jobs: the builder prepares a
 * template plus the companies that will compete and publishes it; a facilitator later starts
 * sessions from it. Publishing is not a save — a package is immutable, and retires rather than
 * disappears, so the sessions it ran keep a name to point at.
 */
export default function PackagesPanel() {
  const { t } = useI18n();
  const [packages, setPackages] = useState<SimulationPackage[]>([]);
  const [templates, setTemplates] = useState<TemplateRow[]>([]);
  const [companies, setCompanies] = useState<CompanyProfile[]>([]);
  const [draft, setDraft] = useState({ name: "", templateKey: "", companyIds: [] as number[] });
  const [busy, setBusy] = useState<number | "new" | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [packageList, templateList, companyList] = await Promise.all([
        api.packages(),
        api.templates(),
        api.companies(),
      ]);
      setPackages(packageList);
      setTemplates(templateList);
      setCompanies(companyList);
      setDraft((current) => ({
        ...current,
        templateKey: current.templateKey || templateList[0]?.key || "",
      }));
    } catch (error) {
      setProblem(describeError(error, t));
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  const publish = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy("new");
    setProblem(null);
    setNotice(null);
    try {
      const made = await api.publishPackage({
        name: draft.name.trim(),
        templateKey: draft.templateKey,
        companyProfileIds: draft.companyIds,
      });
      setNotice(t("packages.published", { name: made.name }));
      setDraft({ name: "", templateKey: draft.templateKey, companyIds: [] });
      await load();
    } catch (error) {
      setProblem(describeError(error, t));
    } finally {
      setBusy(null);
    }
  };

  const retire = async (entry: SimulationPackage) => {
    if (!window.confirm(t("packages.retireConfirm", { name: entry.name }))) return;
    setBusy(entry.id);
    setProblem(null);
    setNotice(null);
    try {
      await api.retirePackage(entry.id);
      setNotice(t("packages.retired", { name: entry.name }));
      await load();
    } catch (error) {
      setProblem(describeError(error, t));
    } finally {
      setBusy(null);
    }
  };

  const toggle = (id: number) =>
    setDraft((current) => ({
      ...current,
      companyIds: current.companyIds.includes(id)
        ? current.companyIds.filter((value) => value !== id)
        : [...current.companyIds, id],
    }));

  const companyName = (id: number) =>
    companies.find((company) => company.id === id)?.name ?? `#${id}`;

  return (
    <>
      <section className="card">
        <h2>{t("packages.title")}</h2>
        <p className="muted">{t("packages.hint")}</p>
        {notice && <p className="banner good">{notice}</p>}
        {problem && <p className="error">{problem}</p>}
        {packages.length === 0 ? (
          <p className="muted">{t("packages.empty")}</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>{t("packages.name")}</th>
                <th>{t("start.template")}</th>
                <th>{t("start.companies")}</th>
                <th>{t("packages.status")}</th>
                <th>{t("packages.publishedAt")}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {packages.map((entry) => (
                <tr key={entry.id}>
                  <td>{entry.name}</td>
                  <td className="muted">
                    {entry.templateKey} · v{entry.templateVersion}
                  </td>
                  <td className="muted">
                    {entry.companyProfileIds.map(companyName).join(" · ")}
                  </td>
                  <td>
                    <span className={`tag ${entry.status === "PUBLISHED" ? "round-closed" : ""}`}>
                      {entry.status.toLowerCase()}
                    </span>
                  </td>
                  <td className="muted">{entry.publishedAt}</td>
                  <td className="num">
                    {entry.status === "PUBLISHED" && (
                      <button className="danger" onClick={() => retire(entry)} disabled={busy === entry.id}>
                        {t("packages.retire")}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="card">
        <h2>{t("packages.publish")}</h2>
        <form onSubmit={publish}>
          <div className="row">
            <label>
              {t("packages.name")}
              <input
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
                placeholder={t("packages.namePlaceholder")}
                required
              />
            </label>
            <label>
              {t("start.template")}
              <select
                value={draft.templateKey}
                onChange={(e) => setDraft({ ...draft, templateKey: e.target.value })}
              >
                {templates.map((template) => (
                  <option key={template.key} value={template.key}>
                    {template.name}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <h3 className="pick-head">{t("start.companies")}</h3>
          {companies.length === 0 && <p className="muted">{t("start.noCompanies")}</p>}
          <ul className="picks">
            {companies.map((company) => (
              <li key={company.id}>
                <label className="inline">
                  <input
                    type="checkbox"
                    checked={draft.companyIds.includes(company.id)}
                    onChange={() => toggle(company.id)}
                  />
                  {company.name}
                  <small className="muted">
                    {company.products.length === 0 ? t("start.fullLine") : company.products.join(" · ")}
                  </small>
                </label>
              </li>
            ))}
          </ul>

          <div className="buttons">
            <button
              className="primary"
              type="submit"
              disabled={busy === "new" || !draft.name.trim() || draft.companyIds.length === 0}
            >
              {busy === "new" ? t("packages.publishing") : t("packages.publishAction")}
            </button>
          </div>
        </form>
      </section>
    </>
  );
}
