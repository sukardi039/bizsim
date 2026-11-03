import { useCallback, useEffect, useState } from "react";
import { api, type CompanyProfile } from "./api";
import { describeError } from "./errors";
import { useI18n } from "./i18n";

/**
 * Setup → Companies.
 *
 * A profile is the reusable definition of a company: its name and which products of the template it
 * carries. Ruling: one shipped profile that any tenant may copy and adapt, reusable across
 * simulations — so a cohort is assembled by ticking existing companies, not by typing names.
 */
export default function CompaniesPanel() {
  const { t } = useI18n();
  const [profiles, setProfiles] = useState<CompanyProfile[]>([]);
  const [draft, setDraft] = useState({ name: "", companyName: "", products: "" });
  const [editing, setEditing] = useState<Record<number, { name: string; companyName: string; products: string }>>({});
  const [busy, setBusy] = useState<number | "new" | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setProfiles(await api.companies());
    } catch (error) {
      setProblem(describeError(error, t));
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  const asCodes = (value: string) =>
    value.split(/[,;\s]+/).map((code) => code.trim()).filter(Boolean);

  const create = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy("new");
    setProblem(null);
    setNotice(null);
    try {
      await api.createCompany({
        name: draft.name.trim(),
        companyName: draft.companyName.trim() || draft.name.trim(),
        products: asCodes(draft.products),
      });
      setDraft({ name: "", companyName: "", products: "" });
      setNotice(t("companies.created"));
      await load();
    } catch (error) {
      setProblem(describeError(error, t));
    } finally {
      setBusy(null);
    }
  };

  const copy = async (profile: CompanyProfile) => {
    setBusy(profile.id);
    setProblem(null);
    setNotice(null);
    try {
      const made = await api.copyCompany(profile.id);
      setNotice(t("companies.copied", { name: made.name }));
      await load();
    } catch (error) {
      setProblem(describeError(error, t));
    } finally {
      setBusy(null);
    }
  };

  const save = async (profile: CompanyProfile) => {
    const row = editing[profile.id] ?? {
      name: profile.name,
      companyName: profile.companyName,
      products: profile.products.join(", "),
    };
    setBusy(profile.id);
    setProblem(null);
    setNotice(null);
    try {
      await api.saveCompany(profile.id, {
        name: row.name.trim(),
        companyName: row.companyName.trim(),
        products: asCodes(row.products),
      });
      setNotice(t("companies.saved"));
      await load();
    } catch (error) {
      setProblem(describeError(error, t));
    } finally {
      setBusy(null);
    }
  };

  return (
    <>
      <section className="card">
        <h2>{t("companies.title")}</h2>
        <p className="muted">{t("companies.hint")}</p>
        {notice && <p className="banner good">{notice}</p>}
        {problem && <p className="error">{problem}</p>}
        <table>
          <thead>
            <tr>
              <th>{t("companies.name")}</th>
              <th>{t("companies.legalName")}</th>
              <th>{t("companies.products")}</th>
              <th>{t("templates.owner")}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {profiles.map((profile) => {
              const row = editing[profile.id] ?? {
                name: profile.name,
                companyName: profile.companyName,
                products: profile.products.join(", "),
              };
              const owned = profile.tenantId != null;
              return (
                <tr key={profile.id}>
                  <td>
                    {owned ? (
                      <input
                        value={row.name}
                        onChange={(e) => setEditing((c) => ({ ...c, [profile.id]: { ...row, name: e.target.value } }))}
                      />
                    ) : (
                      profile.name
                    )}
                  </td>
                  <td>
                    {owned ? (
                      <input
                        value={row.companyName}
                        onChange={(e) => setEditing((c) => ({ ...c, [profile.id]: { ...row, companyName: e.target.value } }))}
                      />
                    ) : (
                      profile.companyName || "—"
                    )}
                  </td>
                  <td>
                    {owned ? (
                      <input
                        value={row.products}
                        placeholder={t("companies.allProducts")}
                        onChange={(e) => setEditing((c) => ({ ...c, [profile.id]: { ...row, products: e.target.value } }))}
                      />
                    ) : profile.products.length === 0 ? (
                      <span className="muted">{t("companies.allProducts")}</span>
                    ) : (
                      profile.products.join(", ")
                    )}
                  </td>
                  <td className="muted">{owned ? t("templates.yours") : t("templates.global")}</td>
                  <td className="num">
                    <span className="buttons">
                      {owned ? (
                        <button onClick={() => save(profile)} disabled={busy === profile.id}>
                          {busy === profile.id ? t("editor.saving") : t("editor.save")}
                        </button>
                      ) : (
                        <button onClick={() => copy(profile)} disabled={busy === profile.id}>
                          {busy === profile.id ? t("templates.copying") : t("templates.duplicate")}
                        </button>
                      )}
                    </span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </section>

      <section className="card">
        <h2>{t("companies.add")}</h2>
        <form className="seat-form" onSubmit={create}>
          <label>
            {t("companies.name")}
            <input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} required />
          </label>
          <label>
            {t("companies.legalName")}
            <input
              value={draft.companyName}
              onChange={(e) => setDraft({ ...draft, companyName: e.target.value })}
              placeholder={t("companies.sameAsName")}
            />
          </label>
          <label>
            {t("companies.products")}
            <input
              value={draft.products}
              onChange={(e) => setDraft({ ...draft, products: e.target.value })}
              placeholder={t("companies.allProducts")}
            />
          </label>
          <button type="submit" disabled={busy === "new" || !draft.name.trim()}>
            {busy === "new" ? t("people.creating") : t("companies.create")}
          </button>
        </form>
      </section>
    </>
  );
}
