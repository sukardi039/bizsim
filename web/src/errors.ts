import { ApiError } from "./api";

type Translate = (key: string, params?: Record<string, string | number>) => string;

/**
 * Turns a server failure into words in the reader's language.
 *
 * The server answers with a `code` and `params` (D23) and only sends English prose as a fallback, so
 * a known code is rendered from the catalog and an unknown one degrades to the server's message
 * instead of showing a raw key. Validation answers carry a list, and every entry is translated
 * separately — a trainee with three mistakes sees three sentences, not one summary.
 */
export function describeError(error: unknown, t: Translate): string {
  if (!(error instanceof ApiError)) {
    return error instanceof Error ? error.message : "unexpected error";
  }

  if (error.problems.length > 0) {
    return error.problems.map((problem) => translate(problem.code, problem.params, problem.message, t)).join(" · ");
  }

  if (error.code) {
    return translate(error.code, error.params, error.message, t);
  }

  return error.message;
}

function translate(
  code: string,
  params: Record<string, string | number> | undefined,
  fallback: string,
  t: Translate,
): string {
  const key = `error.${code}`;
  const text = t(key, params);
  return text === key ? fallback : text;
}
