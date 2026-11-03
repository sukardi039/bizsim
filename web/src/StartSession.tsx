import { useCallback, useEffect, useState } from "react";
import { api, type CompanyProfile, type Run, type SimulationPackage, type UserRow } from "./api";
import { describeError } from "./errors";
import { useI18n } from "./i18n";

/**
 * Execution: start a session from a published package.
 *
 * The facilitator chooses *which prepared package* and *who is in the room* — nothing else. The
 * template, the companies and their product lines were decided by a builder, earlier, and are not
 * editable from here (ruling of 3 Nov 2026). That is the whole point of splitting the two jobs: the
 * person running the class cannot quietly change the economy the class is being graded on.
 */
export default function StartSession({ onStarted }: { onStarted: (run: Run) => void }) {
  const { t } = useI18n();
  const [packages, setPackages] = useState<SimulationPackage[]>([]);
  const [companies, setCompanies] = useState<CompanyProfile[]>([]);
  const [trainees, setTrainees] = useState<UserRow[]>([]);
  const [name, setName] = useState("");
  const [packageId, setPackageId] = useState<number | "">("");
  const [traineeIds, setTraineeIds] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [packageList, companyList, userList] = await Promise.all([
        api.packages(),
        api.companies(),
        api.users(),
      ]);
      const published = packageList.filter((entry) => entry.status === "PUBLISHED");
      setPackages(published);
      setCompanies(companyList);
      setTrainees(userList.filter((user) => user.role === "PLAYER" && user.active));
      setPackageId((current) => current || published[0]?.id || "");
    } catch (error) {
      setProblem(describeError(error, t));
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  const chosen = packages.find((entry) => entry.id === packageId);
  const companyNames = (entry: SimulationPackage) =>
    entry.companyProfileIds
      .map((id) => companies.find((company) => company.id === id)?.name ?? `#${id}`)
      .join(" · ");

  const start = async (event: React.FormEvent) => {
    event.preventDefault();
    if (packageId === "") return;
    setBusy(true);
    setProblem(null);
    try {
      const run = await api.startSession(packageId, {
        name: name.trim() || chosen?.name || t("start.defaultName"),
        traineeIds,
      });
      setName("");
      setTraineeIds([]);
      onStarted(run);
    } catch (error) {
      setProblem(describeError(error, t));
    } finally {
      setBusy(false);
    }
  };

  if (packages.length === 0) {
    return (
      <section className="card">
        <h2>{t("start.title")}</h2>
        <p className="muted">{t("start.noPackages")}</p>
        {problem && <p className="error">{problem}</p>}
      </section>
    );
  }

  return (
    <section className="card">
      <h2>{t("start.title")}</h2>
      <p className="muted">{t("start.hint")}</p>
      {problem && <p className="error">{problem}</p>}

      <form onSubmit={start}>
        <div className="row">
          <label>
            {t("start.name")}
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={chosen?.name ?? t("start.defaultName")}
            />
          </label>
          <label>
            {t("start.package")}
            <select
              value={packageId}
              onChange={(e) => setPackageId(Number(e.target.value))}
            >
              {packages.map((entry) => (
                <option key={entry.id} value={entry.id}>
                  {entry.name}
                </option>
              ))}
            </select>
          </label>
        </div>

        {chosen && (
          <p className="muted">
            {t("start.competing")}: <strong>{companyNames(chosen)}</strong>
            {" · "}
            {chosen.sourceTemplateKey} v{chosen.templateVersion}
          </p>
        )}

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
                  onChange={() =>
                    setTraineeIds((current) =>
                      current.includes(trainee.id)
                        ? current.filter((value) => value !== trainee.id)
                        : [...current, trainee.id],
                    )
                  }
                />
                {trainee.displayName}
                <small className="muted">{trainee.email}</small>
              </label>
            </li>
          ))}
        </ul>

        {/* Tick order seats people in company order, which is easy to get wrong. Show the mapping. */}
        {chosen && (
          <>
            <h3 className="pick-head">{t("start.mapping")}</h3>
            <ul className="picks">
              {chosen.companyProfileIds.map((id, index) => {
                const seated = trainees.find((trainee) => trainee.id === traineeIds[index]);
                return (
                  <li key={id}>
                    <strong>
                      {companies.find((company) => company.id === id)?.name ?? `#${id}`}
                    </strong>{" "}
                    → {seated ? seated.displayName : <span className="muted">{t("start.emptySeat")}</span>}
                  </li>
                );
              })}
            </ul>
          </>
        )}

        {chosen && traineeIds.length > chosen.companyProfileIds.length && (
          <p className="error">{t("start.tooManyTrainees")}</p>
        )}

        <div className="buttons">
          <button className="primary" type="submit" disabled={busy || packageId === ""}>
            {busy ? t("start.starting") : t("start.start")}
          </button>
        </div>
      </form>
    </section>
  );
}
