import { useCallback, useEffect, useState } from "react";
import { api, type CompanyProfile, type Run, type TemplateRow, type UserRow } from "./api";
import { describeError } from "./errors";
import { useI18n } from "./i18n";

/**
 * Start a session from the prepared set (S6e).
 *
 * This is the workbench's pay-off and the answer to "everything must be ready before the session":
 * pick a template, pick the companies, tick the trainees, start. The companies are profiles, not
 * names typed on the day, and a trainee is seated the moment the session begins — no scramble.
 */
export default function StartSession({ onStarted }: { onStarted: (run: Run) => void }) {
  const { t } = useI18n();
  const [templates, setTemplates] = useState<TemplateRow[]>([]);
  const [companies, setCompanies] = useState<CompanyProfile[]>([]);
  const [trainees, setTrainees] = useState<UserRow[]>([]);
  const [name, setName] = useState("");
  const [templateKey, setTemplateKey] = useState("");
  const [companyIds, setCompanyIds] = useState<number[]>([]);
  const [traineeIds, setTraineeIds] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [templateList, companyList, userList] = await Promise.all([
        api.templates(),
        api.companies(),
        api.users(),
      ]);
      setTemplates(templateList);
      setCompanies(companyList);
      setTrainees(userList.filter((user) => user.role === "PLAYER" && user.active));
      setTemplateKey((current) => current || templateList[0]?.key || "");
    } catch (error) {
      setProblem(describeError(error, t));
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  const toggle = (list: number[], id: number) =>
    list.includes(id) ? list.filter((value) => value !== id) : [...list, id];

  const start = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setProblem(null);
    try {
      const run = await api.startPrepared({
        name: name.trim() || t("start.defaultName"),
        templateKey,
        companyProfileIds: companyIds,
        traineeIds: traineeIds.slice(0, companyIds.length),
      });
      setName("");
      setCompanyIds([]);
      setTraineeIds([]);
      onStarted(run);
    } catch (error) {
      setProblem(describeError(error, t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card">
      <h2>{t("start.title")}</h2>
      <p className="muted">{t("start.hint")}</p>
      {problem && <p className="error">{problem}</p>}

      <form onSubmit={start}>
        <div className="row">
          <label>
            {t("start.name")}
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder={t("start.defaultName")} />
          </label>
          <label>
            {t("start.template")}
            <select value={templateKey} onChange={(e) => setTemplateKey(e.target.value)}>
              {templates.map((template) => (
                <option key={template.key} value={template.key}>
                  {template.name} · {template.products} {t("templates.products").toLowerCase()}
                </option>
              ))}
            </select>
          </label>
        </div>

        <div className="row">
          <div>
            <h3 className="pick-head">{t("start.companies")}</h3>
            {companies.length === 0 && <p className="muted">{t("start.noCompanies")}</p>}
            <ul className="picks">
              {companies.map((company) => (
                <li key={company.id}>
                  <label className="inline">
                    <input
                      type="checkbox"
                      checked={companyIds.includes(company.id)}
                      onChange={() => setCompanyIds((current) => toggle(current, company.id))}
                    />
                    {company.name}
                    <small className="muted">
                      {company.products.length === 0
                        ? t("start.fullLine")
                        : company.products.join(" · ")}
                    </small>
                  </label>
                </li>
              ))}
            </ul>
          </div>

          <div>
            <h3 className="pick-head">
              {t("start.trainees")} <small className="muted">{t("start.optional")}</small>
            </h3>
            {trainees.length === 0 && <p className="muted">{t("start.noTrainees")}</p>}
            <ul className="picks">
              {trainees.map((trainee) => (
                <li key={trainee.id}>
                  <label className="inline">
                    <input
                      type="checkbox"
                      checked={traineeIds.includes(trainee.id)}
                      onChange={() => setTraineeIds((current) => toggle(current, trainee.id))}
                    />
                    {trainee.displayName}
                    <small className="muted">{trainee.email}</small>
                  </label>
                </li>
              ))}
            </ul>
          </div>
        </div>

        {traineeIds.length > companyIds.length && (
          <p className="error">{t("start.tooManyTrainees")}</p>
        )}

        <div className="buttons">
          <button
            className="primary"
            type="submit"
            disabled={busy || !templateKey || companyIds.length === 0}
          >
            {busy ? t("start.starting") : t("start.start")}
          </button>
        </div>
      </form>
    </section>
  );
}
