import { useEffect, useLayoutEffect, useRef, useState } from "react";

/**
 * A numeric field that reads like an accounting field: thousands grouped while you type, no spinner
 * arrows, no arrow-key stepping.
 *
 * <p>Why not `type="number"`: the browser draws spinner buttons and makes Up/Down a fine adjustment.
 * In a decision form full of millions, a stray click on a spinner changes 1,000,000 to 1,000,001 —
 * a silent error the trainee will not notice and the round will compute. A text field with a numeric
 * keypad hint (`inputMode`) keeps the keyboard, drops the arrows, and lets the value be formatted.
 *
 * <p>The grouping is applied on every keystroke with the caret put back where it was (counted in
 * digits, not characters), so editing the middle of `1,250,000` does not jump to the end. Decimal
 * fields keep what was typed — `1000.50` stays `1,000.50` while focused — and are normalised on blur.
 */
const GROUP_SEPARATOR = ",";
const DECIMAL_MARK = ".";

/** 1234567 -> "1,234,567". Both supported languages group with a comma. */
export function groupDigits(digits: string): string {
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, GROUP_SEPARATOR);
}

/** A finite number as it should read in a field: grouped, at most `decimals` fraction digits. */
export function formatNumeric(value: number, decimals: number): string {
  if (!Number.isFinite(value)) return "";
  const rounded = decimals === 0 ? Math.round(value) : Number(value.toFixed(decimals));
  const raw = String(rounded);
  const negative = raw.startsWith("-");
  const [whole, fraction] = (negative ? raw.slice(1) : raw).split(".");
  return `${negative ? "-" : ""}${groupDigits(whole)}${fraction ? DECIMAL_MARK + fraction : ""}`;
}

interface Cleaned {
  intPart: string;
  fraction: string;
  hasMark: boolean;
}

/**
 * Keeps digits and at most one decimal mark, drops commas/spaces/letters, trims leading zeros and
 * the fraction to the allowed length. `"1,2a5.678"` with `decimals = 2` becomes `{int: "125", frac: "67"}`.
 */
export function cleanNumericText(text: string, decimals: number): Cleaned {
  const digits = text.replace(/[^0-9.]/g, "");
  const mark = digits.indexOf(DECIMAL_MARK);
  const rawInt = (mark === -1 ? digits : digits.slice(0, mark)).replace(/^0+(?=\d)/, "");
  const rawFraction = mark === -1 ? "" : digits.slice(mark + 1).replace(/\./g, "");
  return {
    intPart: rawInt,
    fraction: decimals === 0 ? "" : rawFraction.slice(0, decimals),
    hasMark: decimals > 0 && mark !== -1,
  };
}

export function numericValue(cleaned: Cleaned): number {
  const value = Number(`${cleaned.intPart || "0"}.${cleaned.fraction || "0"}`);
  return Number.isFinite(value) ? value : 0;
}

function displayOf(cleaned: Cleaned): string {
  if (!cleaned.intPart && !cleaned.hasMark) return "";
  return `${groupDigits(cleaned.intPart || "0")}${cleaned.hasMark ? DECIMAL_MARK + cleaned.fraction : ""}`;
}

export default function NumberInput({
  value,
  onChange,
  decimals = 0,
  min,
  max,
  disabled,
  className,
  id,
  ariaLabel,
}: {
  value: number;
  onChange: (value: number) => void;
  /** Fraction digits allowed. 0 (the default) means whole numbers: units, people, machines. */
  decimals?: number;
  min?: number;
  max?: number;
  disabled?: boolean;
  className?: string;
  id?: string;
  ariaLabel?: string;
}) {
  const [text, setText] = useState(() => formatNumeric(value, decimals));
  const [focused, setFocused] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  /** Digits the caret sat after, so a re-format can put it back in the same place. */
  const caretDigits = useRef<number | null>(null);

  // The parent is the source of truth: a reset, a suggested plan or a poll can change the value
  // under the field. While the trainee is typing we only accept a change we did not cause.
  useEffect(() => {
    if (!focused) {
      setText(formatNumeric(value, decimals));
      return;
    }
    if (numericValue(cleanNumericText(text, decimals)) !== value) {
      setText(formatNumeric(value, decimals));
    }
    // `text` is deliberately not a dependency: this effect reacts to the value, not to typing.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value, decimals, focused]);

  useLayoutEffect(() => {
    const wanted = caretDigits.current;
    if (wanted === null || !input.current) return;
    caretDigits.current = null;
    const el = input.current;
    let seen = 0;
    let position = 0;
    for (; position < el.value.length; position++) {
      if (/[0-9]/.test(el.value[position])) {
        seen++;
        if (seen === wanted) {
          position++;
          break;
        }
      }
    }
    el.setSelectionRange(position, position);
  }, [text]);

  const clampValue = (next: number) => {
    let result = next;
    if (min !== undefined && result < min) result = min;
    if (max !== undefined && result > max) result = max;
    return result;
  };

  return (
    <input
      ref={input}
      id={id}
      aria-label={ariaLabel}
      className={className}
      // A text field: no spinner, and Up/Down cannot nudge the number.
      type="text"
      inputMode={decimals > 0 ? "decimal" : "numeric"}
      autoComplete="off"
      spellCheck={false}
      disabled={disabled}
      value={text}
      onFocus={(event) => {
        setFocused(true);
        // Put the caret at the end, not in front of a formatted number: the common action on a
        // money field is to replace it.
        const el = event.currentTarget;
        window.requestAnimationFrame(() => el.setSelectionRange(el.value.length, el.value.length));
      }}
      onBlur={() => {
        setFocused(false);
        const cleaned = cleanNumericText(text, decimals);
        const next = clampValue(numericValue(cleaned));
        setText(formatNumeric(next, decimals));
        if (next !== value) onChange(next);
      }}
      onKeyDown={(event) => {
        // No arrow-key fine adjustment, ever: a stray press must not change a figure.
        if (event.key === "ArrowUp" || event.key === "ArrowDown") {
          event.preventDefault();
        }
      }}
      onChange={(event) => {
        const el = event.target;
        const caret = el.selectionStart ?? el.value.length;
        const digitsBefore = (el.value.slice(0, caret).match(/[0-9]/g) ?? []).length;
        const cleaned = cleanNumericText(el.value, decimals);
        caretDigits.current = digitsBefore;
        setText(displayOf(cleaned));
        onChange(numericValue(cleaned));
      }}
    />
  );
}
