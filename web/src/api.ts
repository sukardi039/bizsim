/** Thin typed wrapper over the REST API. The token lives in localStorage, not in a URL. */

// Empty by default: the console calls its own origin and nginx forwards /api to the backend.
// Set VITE_API_BASE_URL at build time only when the API lives on another origin.
const ORIGIN = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
const BASE = ORIGIN + "/api";
const STORAGE = "bizsim.session";

export interface ApiProblemBody {
  code: string;
  params?: Record<string, string | number>;
  message: string;
}

export class ApiError extends Error {
  constructor(readonly status: number, readonly body: any) {
    super(body?.error ?? `HTTP ${status}`);
  }

  /** The translatable reason (D23): the interface owns the wording, not the server. */
  get code(): string | undefined {
    return typeof this.body?.code === "string" ? this.body.code : undefined;
  }

  get params(): Record<string, string> {
    const raw = this.body?.params;
    if (!raw || typeof raw !== "object") return {};
    const params: Record<string, string> = {};
    for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
      params[key] = String(value);
    }
    return params;
  }

  /** Every rule a submission broke, as codes with parameters. */
  get problems(): ApiProblemBody[] {
    const list = this.body?.problems;
    if (!Array.isArray(list)) return [];
    return list.filter(
      (entry): entry is ApiProblemBody =>
        !!entry && typeof entry === "object" && typeof (entry as ApiProblemBody).code === "string",
    );
  }

  /** The server's English messages — a fallback for codes this client does not know yet. */
  get details(): string[] {
    if (this.problems.length > 0) return this.problems.map((problem) => problem.message);
    if (Array.isArray(this.body?.details)) return this.body.details.filter((s: unknown) => typeof s === "string");
    return [];
  }
}

export function eventStreamUrl(runId: number, ticket: string): string {
  return `${BASE}/runs/${runId}/events?ticket=${encodeURIComponent(ticket)}`;
}

export function getToken(): string {
  return localStorage.getItem(STORAGE) ?? "";
}

export function setToken(token: string): void {
  localStorage.setItem(STORAGE, token);
}

export function clearToken(): void {
  localStorage.removeItem(STORAGE);
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getToken();
  const response = await fetch(BASE + path, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init.headers ?? {}),
    },
  });
  const text = await response.text();
  let body: any = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
  }
  if (!response.ok) throw new ApiError(response.status, body);
  return body as T;
}

export interface Team {
  id: number;
  name: string;
  companyName: string;
  cash: number;
  brandScore?: number;
  status?: string;
  machineWorkers?: number;
}

export interface RunPlayer {
  id: number;
  teamId: number;
  userId: number;
  roleKey: string;
  displayName: string;
}

export interface Run {
  id: number;
  status: string;
  currentRound: number;
  scenarioKey: string;
  rounds: number;
  cycles: number;
  teams: Team[];
  players: RunPlayer[];
}

export interface StatementRow {
  round: number;
  revenue: number;
  cogs: number;
  payroll: number;
  marketing: number;
  rnd_spend: number;
  fixed_cost: number;
  holding_cost: number;
  hiring_cost: number;
  capacity_cost: number;
  report_cost: number;
  profit: number;
  cash_after: number;
  brand_after: number;
  lost_sales: number;
  capacity_used: number;
  capacity_available: number;
}

export interface ScoreRow {
  round: number;
  team_id: number;
  company_name: string;
  profit: number;
  cash: number;
  market_share: number;
  stock: number;
  brand?: number;
}

export interface ContextProduct {
  code: string;
  name: string;
  price: number;
  unitCost: number;
  quality: number;
  stock: number;
  unitsPerWorker: number;
}

export interface ContextChannel {
  code: string;
  name: string;
  reachWeight: number;
  brandAlpha: number;
}

export interface ContextProject {
  code: string;
  name: string;
  targetProduct: string;
  advantageType: string;
  advantageValue: number;
  durationMonths: number;
  partialBenefit: boolean;
  benefitCurve: string;
  benefitExponent: number;
  overfunding: string;
  hintLow: number;
  hintHigh: number;
  invested: number;
  monthsInvested: number;
  status: string;
}

/** One company's monthly submission (D1: one decision per company per month). */
export interface DecisionInput {
  production: { product: string; units: number }[];
  price: { product: string; unitPrice: number }[];
  marketing: { channel: string; spend: number }[];
  manpower: { production: number };
  rnd: { project: string; invest: number }[];
  capacityPurchase: number;
  marketReport: boolean;
}

export interface MarketForecast {
  round: number;
  demandIndex: number;
  costIndex: number;
  wageIndex: number;
  trendStrength: number;
  trendLabel: string;
  band: number;
  outlook: string;
}

export interface TemplateRound {
  round: number;
  demandIndex: number;
  costIndex: number;
  wageIndex: number;
  trendStrength: number;
  trendLabel: string;
  note: string;
}

export interface TemplateDetail {
  key: string;
  name: string;
  tenantId?: number | null;
  status: string;
  basedOn?: string | null;
  editable: boolean;
  settings: Record<string, unknown>;
  environment: TemplateRound[];
  products: TemplateProduct[];
  channels: TemplateChannel[];
  projects: TemplateProject[];
  events: TemplateEvent[];
}

export interface TemplateProduct {
  code: string;
  name: string;
  basePrice: number;
  baseUnitCost: number;
  baseQuality: number;
  baseDemand: number;
  unitsPerWorker: number;
  incomeElasticity: number;
  trendAffinity: number;
}

export interface TemplateChannel {
  code: string;
  name: string;
  reachWeight: number;
  brandAlpha: number;
}

export interface TemplateProject {
  code: string;
  name: string;
  targetProduct: string | null;
  advantageType: string;
  advantageValue: number;
  hiddenCost: number;
  hintBand: number;
  durationMonths: number;
  partialBenefit: boolean;
  benefitCurve: string;
  benefitExponent: number;
  overfunding: string;
}

export interface TemplateEvent {
  code: string;
  name: string;
  description: string;
  earliestRound: number;
  latestRound: number;
  probability: number;
  durationMonths: number;
  demandMultiplier: number;
  costMultiplier: number;
  wageMultiplier: number;
  targetProduct?: string | null;
}

export interface CompanyProfile {
  id: number;
  name: string;
  companyName: string;
  products: string[];
  notes?: string | null;
  tenantId?: number | null;
  basedOn?: number | null;
}

export interface SimulationPackage {
  id: number;
  name: string;
  /** The frozen copy sessions run on. */
  templateKey: string;
  /** The draft the builder published from, for display. */
  sourceTemplateKey: string;
  templateVersion: number;
  companyProfileIds: number[];
  status: string;
  publishedAt: string;
}

export interface UserRow {
  id: number;
  email: string;
  displayName: string;
  role: string;
  active: boolean;
}

export interface TemplateRow {
  key: string;
  name: string;
  /** Absent for the shipped global template: the API omits nulls. */
  tenantId?: number | null;
  status: string;
  basedOn?: string | null;
  version: number;
  products: number;
  rounds: number;
}

export interface DecisionFormContext {
  runId: number;
  round: number;
  closesAt?: string | null;
  cash: number;
  brand: number;
  status: string;
  machineWorkers: number;
  products: ContextProduct[];
  staff: Record<string, number>;
  salaries: Record<string, number>;
  fixedCostPerMonth: number;
  holdingCostPerUnit: number;
  channels: ContextChannel[];
  machineCostPerWorker: number;
  rndProjects: ContextProject[];
  market: {
    round: number;
    demandIndex: number;
    costIndex: number;
    wageIndex: number;
    trendStrength: number;
    trendLabel: string;
    outlook: string;
    events: unknown[];
  };
  marketReportCost: number;
  /** True when this company already has a decision stored for the open round. */
  submitted: boolean;
  /** The stored decision, so the form can reopen on it instead of an empty month. */
  decision?: DecisionInput | null;
}

export const api = {
  login: (tenantCode: string, email: string, password: string) =>
    request<{ token: string }>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ tenantCode, email, password }),
    }),
  me: () =>
    request<{
      userId: number;
      tenantId: number;
      tenantCode: string;
      role: string;
      /** "en" or "zh"; resolved server-side (person, else tenant, else English). */
      locale: string;
    }>("/me"),
  setMyLocale: (locale: string) =>
    request<{ locale: string }>("/me/locale", {
      method: "PUT",
      body: JSON.stringify({ locale }),
    }),
  runs: () => request<Run[]>("/runs"),
  run: (id: number) => request<Run>(`/runs/${id}`),
  createRun: (name: string, scenarioKey: string, teamNames: string[], cycles: number) =>
    request<Run>("/runs", {
      method: "POST",
      body: JSON.stringify({
        name,
        scenarioKey,
        cycles,
        teams: teamNames.map((teamName) => ({ name: teamName, companyName: teamName })),
      }),
    }),
  nextRound: (runId: number) => request<{ number: number }>(`/runs/${runId}/rounds/next`, { method: "POST" }),
  closeRound: (runId: number) => request<unknown>(`/runs/${runId}/rounds/close`, { method: "POST" }),
  /** Deletes the simulation and everything played in it. Owner or admin only; not undoable. */
  deleteRun: (runId: number) => request<unknown>(`/runs/${runId}`, { method: "DELETE" }),
  statements: (runId: number, teamId: number) =>
    request<StatementRow[]>(`/runs/${runId}/teams/${teamId}/statements`),
  scoreboard: (runId: number, round?: number) =>
    request<ScoreRow[]>(`/runs/${runId}/scoreboard${round ? `?round=${round}` : ""}`),
  context: (runId: number, teamId: number) =>
    request<DecisionFormContext>(`/runs/${runId}/teams/${teamId}/context`),
  /** Facilitator/admin only: makes the account a trainee signs in with. */
  createUser: (email: string, displayName: string, password: string, role = "PLAYER") =>
    request<{ id: number; email: string; displayName: string; role: string }>("/users", {
      method: "POST",
      body: JSON.stringify({ email, displayName, password, role }),
    }),
  addPlayer: (runId: number, teamId: number, userId: number, roleKey = "OWNER") =>
    request<RunPlayer>(`/runs/${runId}/players`, {
      method: "POST",
      body: JSON.stringify({ teamId, userId, roleKey }),
    }),
  templates: () => request<TemplateRow[]>("/templates"),
  copyTemplate: (key: string, name?: string) =>
    request<TemplateRow>(`/templates/${encodeURIComponent(key)}/copy`, {
      method: "POST",
      body: JSON.stringify({ name: name ?? "" }),
    }),
  templateDetail: (key: string) =>
    request<TemplateDetail>(`/templates/${encodeURIComponent(key)}`),
  saveTemplateSettings: (key: string, settings: Record<string, unknown>) =>
    request<TemplateDetail>(`/templates/${encodeURIComponent(key)}/settings`, {
      method: "PUT",
      body: JSON.stringify(settings),
    }),
  saveTemplateEnvironment: (key: string, rounds: TemplateRound[]) =>
    request<TemplateDetail>(`/templates/${encodeURIComponent(key)}/environment`, {
      method: "PUT",
      body: JSON.stringify({ rounds }),
    }),
  saveTemplateProducts: (key: string, products: TemplateProduct[]) =>
    request<TemplateDetail>(`/templates/${encodeURIComponent(key)}/products`, {
      method: "PUT",
      body: JSON.stringify({ products }),
    }),
  saveTemplateChannels: (key: string, channels: TemplateChannel[]) =>
    request<TemplateDetail>(`/templates/${encodeURIComponent(key)}/channels`, {
      method: "PUT",
      body: JSON.stringify({ channels }),
    }),
  saveTemplateEvents: (key: string, events: TemplateEvent[]) =>
    request<TemplateDetail>(`/templates/${encodeURIComponent(key)}/events`, {
      method: "PUT",
      body: JSON.stringify({ events }),
    }),
  saveTemplateProjects: (key: string, projects: TemplateProject[]) =>
    request<TemplateDetail>(`/templates/${encodeURIComponent(key)}/projects`, {
      method: "PUT",
      body: JSON.stringify({ projects }),
    }),
  users: () => request<UserRow[]>("/users"),
  companies: () => request<CompanyProfile[]>("/companies"),
  packages: () => request<SimulationPackage[]>("/packages"),
  tidyPreview: () =>
    request<{ id: number; name: string; frozenKey: string; publishedAt: string }[]>("/packages/tidy-up"),
  tidyUp: () =>
    request<{ removed: number; names: string[] }>("/packages/tidy-up", { method: "POST" }),
  publishPackage: (body: { name: string; templateKey: string; companyProfileIds: number[] }) =>
    request<SimulationPackage>("/packages", { method: "POST", body: JSON.stringify(body) }),
  retirePackage: (id: number) =>
    request<unknown>(`/packages/${id}/retire`, { method: "POST" }),
  /** Execution: a facilitator starts a session from a published package. */
  startSession: (packageId: number, body: { name: string; traineeIds: number[] }) =>
    request<Run>(`/packages/${packageId}/sessions`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
  copyCompany: (id: number) =>
    request<CompanyProfile>(`/companies/${id}/copy`, { method: "POST" }),
  saveCompany: (id: number, body: { name: string; companyName: string; products: string[]; notes?: string }) =>
    request<CompanyProfile>(`/companies/${id}`, { method: "PUT", body: JSON.stringify(body) }),
  createCompany: (body: { name: string; companyName: string; products: string[]; notes?: string }) =>
    request<CompanyProfile>("/companies", { method: "POST", body: JSON.stringify(body) }),
  /** Starts a session from the prepared set: template + company profiles + trainees. */
  startPrepared: (body: {
    name: string;
    templateKey: string;
    companyProfileIds: number[];
    traineeIds: number[];
  }) =>
    request<Run>("/runs/prepared", {
      method: "POST",
      body: JSON.stringify(body),
    }),
  resetUserPassword: (id: number, password: string) =>
    request<unknown>(`/users/${id}/password`, {
      method: "PUT",
      body: JSON.stringify({ password }),
    }),
  changeMyPassword: (currentPassword: string, newPassword: string) =>
    request<unknown>("/me/password", {
      method: "POST",
      body: JSON.stringify({ currentPassword, newPassword }),
    }),
  setUserRole: (id: number, role: string) =>
    request<{ id: number; email: string; displayName: string; role: string; active: boolean }>(
      `/users/${id}/role`,
      { method: "PATCH", body: JSON.stringify({ role }) },
    ),
  deleteUser: (id: number) => request<unknown>(`/users/${id}`, { method: "DELETE" }),
  setUserActive: (id: number, active: boolean) =>
    request<unknown>(`/users/${id}/${active ? "activate" : "deactivate"}`, { method: "POST" }),
  submitDecision: (runId: number, teamId: number, decision: DecisionInput) =>
    request<{ submitted: boolean }>(`/runs/${runId}/teams/${teamId}/decisions`, {
      method: "POST",
      body: JSON.stringify(decision),
    }),
  marketReport: (runId: number, teamId: number) =>
    request<{ purchasedForRound: number; cost: number; current: any; forecast: MarketForecast[] }>(
      `/runs/${runId}/teams/${teamId}/market-report`,
    ),
  streamTicket: (runId: number) =>
    request<{ ticket: string; expiresInSeconds: number }>(`/runs/${runId}/stream-ticket`, {
      method: "POST",
    }),
};
