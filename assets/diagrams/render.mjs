// Renders every .svg in this folder to the .png beside it, at twice its size. The icons load from Iconify, which
// can refuse a burst of requests, so each diagram is retried and a PNG is written only once every icon has loaded.
// Run from anywhere: node assets/diagrams/render.mjs (it uses the Playwright installed for the frontend's tests).
import { readdirSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const { chromium } = createRequire(join(here, "../../frontend/package.json"))("@playwright/test");
const ATTEMPTS = 3;

const browser = await chromium.launch();
let failed = false;
for (const name of readdirSync(here).filter((file) => file.endsWith(".svg")).sort()) {
  const source = join(here, name);
  const target = source.replace(/\.svg$/, ".png");
  let problems = [];
  for (let attempt = 1; attempt <= ATTEMPTS; attempt++) {
    const page = await browser.newPage({ deviceScaleFactor: 2 });
    problems = [];
    page.on("response", (response) => {
      if (response.status() >= 400) problems.push(`${response.status()} ${response.url()}`);
    });
    page.on("requestfailed", (request) => problems.push(`failed ${request.url()}`));
    await page.goto(`file://${source}`, { waitUntil: "networkidle" });
    const [width, height] = await page.evaluate(() => {
      const svg = document.documentElement;
      return [svg.width.baseVal.value, svg.height.baseVal.value];
    });
    if (problems.length === 0) {
      await page.setViewportSize({ width, height });
      await page.screenshot({ path: target, clip: { x: 0, y: 0, width, height } });
      console.log(`${name} -> ${name.replace(/\.svg$/, ".png")} (${width * 2}x${height * 2})`);
    }
    await page.close();
    if (problems.length === 0) break;
    if (attempt < ATTEMPTS) await new Promise((resolve) => setTimeout(resolve, 5000 * attempt));
  }
  if (problems.length > 0) {
    failed = true;
    console.error(`${name}: some icons didn't load, so its PNG is unchanged:\n  ${problems.join("\n  ")}`);
  }
}
await browser.close();
process.exit(failed ? 1 : 0);
