import { useCallback, useEffect, useState } from "react";
import {
  api,
  type CompanyProfile,
  type SimulationPackage,
  type TemplateRow,
  type UserRow,
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
  const { t, locale } = useI18n();
  const [packages, setPackages] = useState<SimulationPackage[]>([]);
  const [templates, setTemplates] = useState<TemplateRow[]>([]);
  const [companies, setCompanies] = useState<CompanyProfile[]>([]);
  const [trainees, setTrainees] = useState<UserRow[]>([]);
  /** Opponents are seatable like people: same picker, labelled so a trainer knows what they seat. */
  const [bots, setBots] = useState<Array<{ id: number; name: string; userId: number | null; active: boolean }>>([]);
  /** company id -> trainee id: the class list is part of the package, so it is chosen here. */
  const [seats, setSeats] = useState<Record<number, string>>({});
  const [draft, setDraft] = useState({
    name: "",
    templateKey: "",
    companyIds: [] as number[],
    // D66: the class language is part of the package, so it starts as the builder's own and can be
    // changed — never blank, because a blank one used to mean "each trainee decides".
    locale: String(locale),
    /** D62: extra parts beyond the first. Empty = a single-simulation package. */
    parts: [] as { name: string; templateKey: string }[],
  });
  const [busy, setBusy] = useState<number | "new" | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [tidy, setTidy] = useState<{ id: number; name: string; publishedAt: string }[]>([]);

  /**
   * The languages the chosen template actually carries (D86/D87): the class language must be one the
   * template has an environment set for, or the class would read a set that does not exist. Shown as a
   * plain list — no free-text "other" — so the choice is always visible.
   */
  const [templateLangs, setTemplateLangs] = useState<string[]>(["en", "zh"]);

  const load = useCallback(async () => {
    // Each list loads on its own. They used to share one Promise.all, so a single failing call
    // emptied the whole form — a builder without the people list lost the template dropdown, and
    // the only clue was "This action is for facilitators" under the wrong heading.
    const failures: string[] = [];
    let templateList: TemplateRow[] = [];
    const settle = async <T,>(label: string, call: Promise<T>, apply: (value: T) => void) => {
      try {
        apply(await call);
      } catch (error) {
        failures.push(`${label}: ${describeError(error, t)}`);
      }
    };
    await Promise.all([
      settle(t("nav.packages"), api.packages(), setPackages),
      settle(t("start.companies"), api.companies(), setCompanies),
      settle(t("start.trainees"), api.trainees(), setTrainees),
      settle(t("nav.bots"), api.bots(), setBots),
      settle(t("packages.tidy"), api.tidyPreview(), setTidy),
      settle(t("start.template"), api.templates(), (list) => {
        templateList = list;
        setTemplates(list);
      }),
    ]);
    setProblem(failures.length > 0 ? failures.join(" · ") : null);
    setDraft((current) => ({
      ...current,
      templateKey: current.templateKey || templateList[0]?.key || "",
    }));
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  // The class language list comes from the template, so it can only offer sets the template has.
  useEffect(() => {
    if (!draft.templateKey) {
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        const detail = await api.templateDetail(draft.templateKey);
        const carried = Array.from(new Set([
          ...(detail.environment ?? []).map((row) => row.lang || "en"),
          ...(detail.events ?? []).map((row) => row.lang || "en"),
        ])).sort();
        if (!cancelled) {
          setTemplateLangs(carried.length === 0 ? ["en", "zh"] : carried);
          setDraft((current) =>
            carried.includes(current.locale)
              ? current
              : { ...current, locale: carried.includes(String(locale)) ? String(locale) : carried[0] },
          );
        }
      } catch {
        // The list stays as it was; publishing still refuses a language the template lacks.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [draft.templateKey, locale]);

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
        // positional: one entry per company, null where the seat is left open
        traineeIds: draft.companyIds.map((id) => (seats[id] ? Number(seats[id]) : null)),
        locale: draft.locale || null,
        // The first part is the package's own template; extra parts make it a programme.
        parts:
          draft.parts.length === 0
            ? undefined
            : [
                { name: draft.name.trim() || t("packages.partOne"), templateKey: draft.templateKey },
                ...draft.parts.filter((part) => part.templateKey),
              ],
      });
      setNotice(t("packages.published", { name: made.name }));
      setDraft({
        name: "",
        templateKey: draft.templateKey,
        companyIds: [],
        locale: String(locale),
        parts: [],
      });
      setSeats({});
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

  const tidyUp = async () => {
    if (!window.confirm(t("packages.tidyConfirm", { count: tidy.length }))) return;
    setBusy("new");
    setProblem(null);
    setNotice(null);
    try {
      const result = await api.tidyUp();
      setNotice(t("packages.tidied", { count: result.removed }));
      await load();
    } catch (error) {
      setProblem(describeError(error, t));
    } finally {
      setBusy(null);
    }
  };

  /** A session holds one company per profile and at most ten of them (decision 5). */
  const MAX_COMPANIES = 10;

  const toggle = (id: number) =>
    setDraft((current) => {
      if (current.companyIds.includes(id)) {
        return { ...current, companyIds: current.companyIds.filter((value) => value !== id) };
      }
      if (current.companyIds.length >= MAX_COMPANIES) {
        return current; // the count line says why; do not silently drop the click
      }
      return { ...current, companyIds: [...current.companyIds, id] };
    });

  /**
   * Why the Publish button is disabled. A disabled button with no explanation is a dead end: the
   * form looks complete and nothing happens.
   */
  const blockers = [
    !draft.name.trim() ? t("packages.blockedName") : null,
    draft.companyIds.length === 0 ? t("packages.blockedCompanies") : null,
    !draft.templateKey ? t("packages.blockedTemplate") : null,
    !draft.locale ? t("packages.blockedLanguage") : null,
  ].filter((entry): entry is string => entry !== null);

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
                    {entry.sourceTemplateKey} · v{entry.templateVersion}
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

      {tidy.length > 0 && (
        <section className="card">
          <h2>{t("packages.tidy")}</h2>
          <p className="muted">{t("packages.tidyHint")}</p>
          <ul>
            {tidy.map((entry) => (
              <li key={entry.id}>
                {entry.name} <small className="muted">· {entry.publishedAt}</small>
              </li>
            ))}
          </ul>
          <div className="buttons">
            <button className="danger" onClick={tidyUp} disabled={busy === "new"}>
              {t("packages.tidyAction", { count: tidy.length })}
            </button>
          </div>
        </section>
      )}

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

          {/* D62: extra simulations in the same package, each with its own template. */}
          <h3 className="pick-head">{t("packages.parts")}</h3>
          <p className="muted">{t("packages.partsHint")}</p>
          {draft.parts.map((part, index) => (
            <div className="row" key={index}>
              <label>
                {t("packages.partName", { position: index + 2 })}
                <input
                  value={part.name}
                  onChange={(e) =>
                    setDraft((current) => ({
                      ...current,
                      parts: current.parts.map((row, at) =>
                        at === index ? { ...row, name: e.target.value } : row,
                      ),
                    }))
                  }
                />
              </label>
              <label>
                {t("start.template")}
                <select
                  value={part.templateKey}
                  onChange={(e) =>
                    setDraft((current) => ({
                      ...current,
                      parts: current.parts.map((row, at) =>
                        at === index ? { ...row, templateKey: e.target.value } : row,
                      ),
                    }))
                  }
                >
                  {templates.map((template) => (
                    <option key={template.key} value={template.key}>
                      {template.name}
                    </option>
                  ))}
                </select>
              </label>
              <button
                type="button"
                className="ghost"
                onClick={() =>
                  setDraft((current) => ({
                    ...current,
                    parts: current.parts.filter((_, at) => at !== index),
                  }))
                }
              >
                {t("packages.removePart")}
              </button>
            </div>
          ))}
          <div className="buttons">
            <button
              type="button"
              className="ghost"
              onClick={() =>
                setDraft((current) => ({
                  ...current,
                  parts: [
                    ...current.parts,
                    {
                      name: "",
                      templateKey: current.templateKey || templates[0]?.key || "",
                    },
                  ],
                }))
              }
            >
              {t("packages.addPart")}
            </button>
          </div>

          <h3 className="pick-head">
            {t("start.companies")}{" "}
            <small className="muted">
              {t("packages.companyCount", {
                count: draft.companyIds.length,
                max: MAX_COMPANIES,
              })}
            </small>
          </h3>
          {companies.length === 0 && <p className="muted">{t("start.noCompanies")}</p>}
          <ul className="picks">
            {companies.map((company) => (
              <li key={company.id}>
                <label className="inline">
                  <input
                    type="checkbox"
                    checked={draft.companyIds.includes(company.id)}
                    disabled={
                      !draft.companyIds.includes(company.id) &&
                      draft.companyIds.length >= MAX_COMPANIES
                    }
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

          <label>
            {t("packages.classLanguage")}
            <select
              value={draft.locale}
              onChange={(e) => setDraft((current) => ({ ...current, locale: e.target.value }))}
            >
              {templateLangs.map((lang) => (
                <option key={lang} value={lang}>
                  {(lang === "en"
                    ? t("editor.catalogEnglish")
                    : lang === "zh"
                      ? t("editor.catalogChinese")
                      : lang.toUpperCase())} ({lang})
                </option>
              ))}
            </select>
            <small className="muted">{t("packages.classLanguageHint")}</small>
          </label>

          {draft.companyIds.length > 0 && (
            <>
              <h3 className="pick-head">{t("packages.classList")}</h3>
              <p className="muted">{t("packages.classListHint")}</p>
              {trainees.length === 0 && <p className="muted">{t("packages.noTrainees")}</p>}
              <table>
                <thead>
                  <tr>
                    <th>{t("start.companies")}</th>
                    <th>{t("start.trainees")}</th>
                  </tr>
                </thead>
                <tbody>
                  {draft.companyIds.map((id) => (
                    <tr key={id}>
                      <td>{companyName(id)}</td>
                      <td>
                        <select
                          value={seats[id] ?? ""}
                          onChange={(e) => setSeats((c) => ({ ...c, [id]: e.target.value }))}
                        >
                          <option value="">{t("packages.seatOpen")}</option>
                          {[
                            ...trainees.map((trainee) => ({
                              id: trainee.id,
                              label: `${trainee.displayName} · ${trainee.email}`,
                            })),
                            ...bots
                              .filter((bot) => bot.active && bot.userId !== null)
                              .map((bot) => ({
                                id: bot.userId as number,
                                label: `${bot.name} · ${t("bots.autoLabel")}`,
                              })),
                          ]
                            .filter((candidate) =>
                              Object.entries(seats).every(
                                ([companyId, picked]) =>
                                  Number(companyId) === id || picked !== String(candidate.id),
                              ),
                            )
                            .map((candidate) => (
                              <option key={candidate.id} value={candidate.id}>
                                {candidate.label}
                              </option>
                            ))}
                        </select>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}

          {blockers.length > 0 && (
            <p className="muted">
              {t("packages.blockedTitle")} {blockers.join(" · ")}
            </p>
          )}
          <div className="buttons">
            <button
              className="primary"
              type="submit"
              disabled={busy === "new" || blockers.length > 0}
            >
              {busy === "new" ? t("packages.publishing") : t("packages.publishAction")}
            </button>
          </div>
        </form>
      </section>
    </>
  );
}
