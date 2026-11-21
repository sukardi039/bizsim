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
    return error.problems
      .map((problem) => {
        const params = fieldParams(problem.params, t);
        // D120: an empty field is "please fill this in", not the generic "not valid".
        const code = params?.required ? "request.field_required" : problem.code;
        return translate(code, params, problem.message, t);
      })
      .join(" · ");
  }

  if (error.code) {
    return translate(error.code, error.params, error.message, t);
  }

  return error.message;
}

/**
 * D120: a field problem names a field; the name is a word the reader should see in their own language.
 * `email` reads 邮箱 in a Chinese class, and an unknown field keeps its own name rather than disappearing.
 * A constraint that was about an empty value says so, instead of the generic "not valid".
 */
function fieldParams(
  params: Record<string, string | number> | undefined,
  t: Translate,
): Record<string, string | number> | undefined {
  if (!params) {
    return params;
  }
  const next = { ...params };
  if (typeof next.field === "string") {
    const key = `field.${next.field}`;
    const label = t(key);
    if (label !== key) {
      next.field = label;
    }
  }
  next.required =
    next.violation === "NotBlank" || next.violation === "NotNull" || next.violation === "NotEmpty" ? 1 : 0;
  return next;
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
