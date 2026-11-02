/** Thin typed wrapper over the REST API. The token lives in localStorage, not in a URL. */

const BASE = "/api";
const STORAGE = "bizsim.session";

export class ApiError extends Error {
  constructor(readonly status: number, readonly body: any) {
    super(body?.error ?? `HTTP ${status}`);
  }

  /** The server reports validation problems as a list; the form shows them verbatim. */
  get details(): string[] {
    if (Array.isArray(this.body?.details)) return this.body.details;
    if (Array.isArray(this.body?.problems)) return this.body.problems;
    return [];
  }
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
  company_name: string;
  cash: number;
  brand?: number;
  status?: string;
}

export interface Run {
  id: number;
  status: string;
  currentRound: number;
  scenarioKey: string;
  rounds: number;
  cycles: number;
  teams: Team[];
  players: { id: number; name: string; team_id: number | null }[];
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

export interface DecisionFormContext {
  products: { code: string; name: string }[];
  channels: { code: string; name: string }[];
  rndProjects: { code: string; name: string; durationMonths: number }[];
  market: { round: number; demandIndex: number; costIndex: number; trendLabel: string; outlook: string };
  marketReportCost: number;
  closesAt?: string;
}

export const api = {
  login: (tenantCode: string, email: string, password: string) =>
    request<{ token: string }>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ tenantCode, email, password }),
    }),
  me: () => request<{ userId: number; tenantId: number; tenantCode: string; role: string }>("/me"),
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
  scoreboard: (runId: number) => request<ScoreRow[]>(`/runs/${runId}/scoreboard`),
  context: (runId: number, teamId: number) =>
    request<DecisionFormContext>(`/runs/${runId}/teams/${teamId}/context`),
  streamTicket: (runId: number) =>
    request<{ ticket: string; expiresInSeconds: number }>(`/runs/${runId}/stream-ticket`, {
      method: "POST",
    }),
};
