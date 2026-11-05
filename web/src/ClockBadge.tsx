import { useEffect, useState } from "react";
import type { Run } from "./api";
import { useI18n } from "./i18n";

/**
 * How long is left in this round (D53), counted down on the screen.
 *
 * <p>The server owns the deadline: this renders `clockRemainingSeconds` from the last refresh and
 * ticks locally between them, so the class sees the same number whether their connection is quick or
 * slow. A paused clock does not tick; an untimed round says so.
 */
export default function ClockBadge({ run }: { run: Run }) {
  const { t } = useI18n();
  const [left, setLeft] = useState(run.clockRemainingSeconds ?? -1);

  // Re-anchor whenever the server sends a new deadline (open, pause, extend, resume, new round).
  useEffect(() => {
    setLeft(run.clockRemainingSeconds ?? -1);
  }, [run.id, run.currentRound, run.closesAt, run.clockPaused, run.clockRemainingSeconds]);

  const paused = Boolean(run.clockPaused);
  const untimed = (run.clockRemainingSeconds ?? -1) < 0;

  useEffect(() => {
    if (paused || untimed || left <= 0) return;
    const timer = window.setInterval(() => setLeft((value) => Math.max(0, value - 1)), 1000);
    return () => window.clearInterval(timer);
  }, [paused, untimed, left > 0]);

  if (!run.roundOpen) {
    return null;
  }
  if (untimed) {
    return <p className="muted clock-badge">{t("clock.untimed")}</p>;
  }

  const minutes = Math.floor(left / 60);
  const seconds = left % 60;
  const time = `${minutes}:${String(seconds).padStart(2, "0")}`;

  return (
    <p className={`clock-badge ${left <= 60 ? "negative" : "muted"}`}>
      {paused
        ? t("clock.paused", { time })
        : left <= 0
          ? t("clock.over")
          : t("clock.left", { time })}
    </p>
  );
}
