/**
 * D102: the shared position map, built at build time.
 *
 * <p>The console draws the map with `PositionMap`. The class needs the **same** picture on a page they can
 * open without an account, and Spring cannot run a React component — so the build renders it here, from
 * the console's own code, and stores the geometry as a JSON template. The backend fills in the round's
 * figures and the class language. A second drawing in Java would drift from the console's, which is
 * exactly the defect D101 was.
 *
 * <p>The dataset below is deliberately permissive: every price index and every vertical index is unique,
 * so no two bubbles share a coordinate, no trail runs backwards, and the substitution the backend does is
 * unambiguous. What the template carries is geometry — scale-invariant, because both axes are indices
 * around 1.00. What it does **not** carry is any string: names are `{{nameN}}` and each figure is
 * `<num>`, a token the backend replaces with the round's number at the same precision.
 *
 * Output: `public/map-template.json`, bundled with the console and copied into the backend image.
 * `--check` fails instead of writing, which is how the test guards the backend's substitution.
 */
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import PositionMap, { verticalAxisFor } from "../src/PositionMap";
import { Context as I18nContext, en, zh } from "../src/i18n";

// The output is written to the working directory, which is `web/` for `npm run` and for the test —
// esbuild rewrites `import.meta.url` to a `file://` string, so it must not be used to find paths here.
const OUT = resolve(process.cwd(), "public", "map-template.json");
const CHECK = process.argv.includes("--check");

/**
 * Slots for the largest class the console seats (D118: thirty companies).
 *
 * The template is a *layout*, not data: every slot carries a distinct price index and vertical index, a
 * plausible share and a two-step trail, and the page substitutes the real figures into it. Thirty slots
 * are generated rather than hand-written so the grid stays even and the file stays reviewable; the six
 * hand-written slots this replaces were the same idea for a smaller ceiling.
 *
 * Price runs across six tiers, vertical across five steps: every pair is unique, which is what the
 * substitution relies on when it drops the slots a smaller class does not use.
 */
const PRICE_TIERS = [0.7031, 0.8279, 0.9187, 1.0713, 1.1851, 1.2917];
const VERTICAL_STEPS = [0.7413, 0.8621, 0.9319, 1.0217, 1.1333];
/**
 * D119: the number of slots is the class-size ceiling from limits.json — the same file the server
 * validates and the console reads. Deriving it here from the grid (6 x 5 = 30) was the last place the
 * ceiling was stated a second time.
 */
const LIMITS = JSON.parse(readFileSync(
  resolve(process.cwd(), "..", "backend", "src", "main", "resources", "limits.json"), "utf8"));
const SLOT_COUNT = LIMITS.classSize.max;

const SLOTS = Array.from({ length: SLOT_COUNT }, (_, index) => {
  const tier = index % PRICE_TIERS.length;
  const step = Math.floor(index / PRICE_TIERS.length);
  const price = PRICE_TIERS[tier];
  const vertical = VERTICAL_STEPS[step];
  return {
    price,
    vertical,
    // Shares ascend across the class so the template's own figures are plausible in any order.
    share: 3.11 + index * 0.93,
    trail: [
      [price - 0.0413, vertical - 0.0317],
      [price - 0.0211, vertical - 0.0119],
    ],
  };
});

function provider(locale, children) {
  const dict = locale === "zh" ? zh : en;
  const value = {
    locale,
    t: (key) => dict[key] ?? en[key] ?? key,
    money: (v) => String(v),
    number: (v) => String(v),
    setLocale: () => undefined,
    adopt: () => undefined,
    applySession: () => undefined,
  };
  return createElement(I18nContext.Provider, { value }, children);
}

const pointsFor = () =>
  SLOTS.map((slot, index) => ({
    company: `{{name${index}}}`,
    priceIndex: slot.price,
    qualityIndex: slot.vertical,
    share: slot.share,
    brand: 100,
    own: false,
    trail: [
      ...slot.trail.map(([price, quality]) => ({ priceIndex: price, qualityIndex: quality })),
      { priceIndex: slot.price, qualityIndex: slot.vertical },
    ],
  }));

/**
 * Pins the axis ticks to the template's own constant values, then tokens every remaining figure.
 *
 * Ticks are the axis scale, not data: they read 0.70 … 1.30 on every page, so they are left in place and
 * never substituted — a stray tick would otherwise consume a company's share. What is tokenised is the
 * one figure per bubble, written by the component as `<digits>%`.
 */
const TICK = /(class="position-tick"[^>]*>)-?\d+(?:\.\d+)?</g;

function tokenise(svg) {
  return svg
    .replace(TICK, "$1{tick}<")
    .replace(/>-?\d+(?:\.\d+)?%</g, ">__NUM__%<");
}

function build(locale) {
  const points = pointsFor();
  const svg = renderToStaticMarkup(
    provider(locale, createElement(PositionMap, { points, axis: verticalAxisFor(points), standalone: true })),
  );
  return tokenise(svg);
}

const STRING_KEYS = [
  "debrief.positionMap",
  "debrief.positionMapHint",
  "debrief.positionFlatQuality",
  "debrief.positionPrice",
  "debrief.positionQuality",
  "debrief.positionBrand",
  "debrief.quadrantCheap",
  "debrief.quadrantPremium",
  "debrief.quadrantCheapUnknown",
  "debrief.quadrantPremiumStrong",
  "debrief.quadrantStuck",
  "debrief.quadrantOverpriced",
  "debrief.unserved",
  // D112: the pie's two readings. Without these the shared page rendered the unserved slice and the
  // coverage figure with no label at all — an unnamed slice is worse than no slice, because the reader
  // cannot tell whether the gap is demand or a drawing error.
  "debrief.coverage",
  "debrief.coverage",
  "debrief.ofSales",
  "debrief.noMarket",
  "debrief.company",
  "debrief.share",
  "debrief.round",
  // The shared picture omits the four quadrant words (they collide with the bubbles at phone size), so
  // its caption names them instead.
  "debrief.positionMapQuadrants",
];
const pick = (dict) => Object.fromEntries(STRING_KEYS.map((key) => [key, dict[key] ?? key]));
const STRINGS = { en: pick(en), zh: pick(zh) };

/**
 * Puts the tick values back, in order, and records how many there are: the backend replaces `{tick}` with
 * this list one for one, so a change to the axis in the component fails its check instead of drifting.
 */
const TICKS = [0.7, 0.85, 1, 1.15, 1.3];
function withTicks(svg) {
  let index = 0;
  const filled = svg.replace(/\{tick\}/g, () => TICKS[index++ % TICKS.length].toFixed(2));
  return filled;
}

const english = withTicks(build("en"));
const chinese = withTicks(build("zh"));

const countOf = (haystack, needle) => haystack.split(needle).length - 1;

const layout = {
  version: 1,
  /** Quality or brand: decided by the backend per round, so both variants are precomputed. */
  variants: {
    quality: { axis: "quality", svg: english.replace(/__AXIS__/g, ""), svgZh: chinese },
    brand: { axis: "brand", svg: english, svgZh: chinese },
  },
  slots: SLOTS.map((slot, index) => ({
    name: `{{name${index}}}`,
    price: slot.price,
    vertical: slot.vertical,
    share: slot.share,
  })),
  /** The captions and axis titles the shared page prints, so it says exactly what the console says. */
  strings: {
    en: { ...STRINGS.en },
    zh: { ...STRINGS.zh },
  },
  /** How many times each token occurs in one SVG — the backend checks its own substitution against this. */
  tokens: {
    number: "__NUM__",
    counts: {
      names: countOf(english, "{{name"),
      numbers: countOf(english, "__NUM__"),
      /** Ticks and the frame carry no numbers, but the axis is unchanged between variants. */
      numbersZh: countOf(chinese, "__NUM__"),
    },
  },
};

if (CHECK) {
  const same = JSON.stringify(JSON.parse(readFileSync(OUT, "utf8"))) === JSON.stringify(layout);
  if (!same) {
    console.error("map-template.json is stale: rebuild it with `node scripts/map-template.mjs`");
    process.exit(1);
  }
  console.log(
    `map-template.json is current (${layout.tokens.counts.numbers} figures, ${layout.tokens.counts.names} names)`,
  );
} else {
  mkdirSync(dirname(OUT), { recursive: true });
  writeFileSync(OUT, JSON.stringify(layout));
  console.log(
    `map-template.json: ${SLOTS.length} bubbles, ${layout.tokens.counts.numbers} figures, ` +
      `${layout.tokens.counts.names} name slots`,
  );
}
