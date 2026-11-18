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
 * Six slots, each with a distinct price, a distinct vertical index, a distinct share and a two-step trail
 * — six being the largest class the console seats.
 */
const SLOTS = [
  { price: 0.7031, vertical: 0.7413, share: 4.13, trail: [[0.8123, 0.8031], [0.7531, 0.7719]] },
  { price: 0.8279, vertical: 0.8621, share: 9.37, trail: [[0.9013, 0.9217], [0.8611, 0.8893]] },
  { price: 0.9187, vertical: 0.9319, share: 14.29, trail: [[0.9721, 0.9337], [0.9433, 0.9321]] },
  { price: 1.0713, vertical: 1.0217, share: 18.71, trail: [[1.0131, 0.9773], [1.0419, 0.9987]] },
  { price: 1.1851, vertical: 1.1333, share: 23.47, trail: [[1.1013, 1.0231], [1.1431, 1.0779]] },
  { price: 1.2917, vertical: 1.2479, share: 29.83, trail: [[1.2113, 1.1831], [1.2531, 1.2173]] },
];

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
