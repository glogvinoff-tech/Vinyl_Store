import { chromium, expect } from "@playwright/test";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { readFile, mkdir } from "node:fs/promises";
import { resolve, extname, sep } from "node:path";
import { fileURLToPath } from "node:url";

const project = fileURLToPath(new URL("../../", import.meta.url));
const root = resolve(project, "frontend/dist");
const records = Array.from({ length: 12 }, (_, i) => ({
  id: `00000000-0000-0000-0000-${String(i + 1).padStart(12, "0")}`,
  code: `DEMO-${i}`, name: ["Moonlight Session", "Blue Notes", "City Lights", "Night Drive"][i % 4] + ` ${i + 1}`,
  artist: ["The Echoes", "Jazz Quartet", "Morning Club", "Synth Waves"][i % 4],
  genre: ["Rock", "Jazz", "Pop", "Electronic"][i % 4], releaseYear: 2020 + i % 5,
  price: 2500, salePrice: i % 3 === 0 ? 2000 : 2500, discountPercent: i % 3 === 0 ? 20 : 0,
  stock: 10, active: true, tags: [], images: [], cover: "/assets/catalog-03f3c0e006b904b0.jpg",
}));
const python = spawn(process.env.RECOMMENDER_PYTHON || "python", ["-u", "-c",
  "from server import RecommendationServer; s=RecommendationServer(('127.0.0.1',0)); print(s.server_port,flush=True); s.serve_forever()"],
  { cwd: resolve(project, "recommender"), windowsHide: true, stdio: ["ignore", "pipe", "pipe"] });
let browser;
const server = createServer(async (req, res) => {
  try {
    const path = new URL(req.url, "http://localhost").pathname;
    const file = extname(path) ? resolve(root, "." + path) : resolve(root, "index.html");
    if (!file.startsWith(root + sep)) throw new Error("Invalid path");
    const content = await readFile(file);
    res.setHeader("Content-Type", ({ ".js": "application/javascript", ".css": "text/css", ".png": "image/png",
      ".svg": "image/svg+xml", ".jpg": "image/jpeg", ".mp3": "audio/mpeg", ".ttf": "font/ttf" })[extname(file)] || "text/html");
    res.end(content);
  } catch { res.statusCode = 404; res.end(); }
});

try {
  const port = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(Error("Python startup timeout")), 10000);
    python.once("error", error => { clearTimeout(timeout); reject(error); });
    python.stdout.once("data", data => { clearTimeout(timeout); resolve(Number(data.toString().trim())); });
    python.once("exit", code => { clearTimeout(timeout); reject(Error(`Python exited: ${code}`)); });
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL || "msedge", headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  let account = "alice";
  let unavailable = false;
  await page.route("**/api/**", async route => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/auth/refresh") return route.fulfill({ json: { accessToken: "test", user: { id: account, displayName: "Тест", roles: ["VIEWER"] } } });
    if (url.pathname === "/api/catalog/recommendations") {
      if (unavailable) return route.fulfill({ status: 503, json: { message: "Unavailable" } });
      const response = await fetch(`http://127.0.0.1:${port}/recommend`, { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ records, genres: url.searchParams.getAll("genres"), limit: 3 }) });
      const data = await response.json();
      const picks = items => items.map(pick => ({ ...pick, record: records.find(record => record.id === pick.recordId) }));
      return route.fulfill({ json: { ...data, items: picks(data.items), promotion: { ...data.promotion,
        items: picks(data.promotion.items), explanation: "Действующие скидки каталога с учётом вкуса, даты и времени." } } });
    }
    return route.fulfill({ json: { content: records, totalPages: 1 } });
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  const panel = page.locator("#for-you");
  await expect(panel.locator(".album")).toHaveCount(3);
  await panel.getByRole("button", { name: "Рок", exact: true }).click();
  await expect(panel.locator(".album")).toHaveCount(3);
  await expect(panel.locator(".album-name").first()).toHaveText("The Echoes");
  await page.reload();
  await expect(panel.getByRole("button", { name: "Рок", exact: true })).toHaveAttribute("aria-pressed", "true");
  await expect(panel.locator(".album")).toHaveCount(3);
  await mkdir(resolve(project, ".playwright"), { recursive: true });
  await panel.screenshot({ path: resolve(project, ".playwright/recommendations-desktop.png") });
  await page.setViewportSize({ width: 390, height: 844 });
  await panel.screenshot({ path: resolve(project, ".playwright/recommendations-mobile.png") });
  const overflow = await page.evaluate(() => [...document.querySelectorAll("body *")]
    .filter(element => element.getBoundingClientRect().right > window.innerWidth + 1)
    .map(element => ({ tag: element.tagName, className: String(element.className), right: Math.round(element.getBoundingClientRect().right) })).slice(0, 12));
  if (overflow.length) console.log("Existing page overflow diagnostics:", overflow);
  expect(await panel.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
  account = "bob";
  await page.reload();
  await expect(panel.getByRole("button", { name: "Рок", exact: true })).toHaveAttribute("aria-pressed", "false");
  await expect(panel.locator(".album")).toHaveCount(3);
  await panel.getByRole("button", { name: "Джаз", exact: true }).click();
  await expect(panel.locator(".album-name").first()).toHaveText("Jazz Quartet");
  unavailable = true;
  await panel.getByRole("button", { name: "Сбросить вкус" }).click();
  await expect(panel.getByRole("button", { name: "Повторить подбор" })).toBeVisible();
  await expect(page.locator("#popular .album")).toHaveCount(3);
  unavailable = false;
  await panel.getByRole("button", { name: "Повторить подбор" }).click();
  await expect(panel.locator(".album")).toHaveCount(3);
  expect(errors).toEqual([]);
  console.log("PASS: real Python recommendations, taste selection, persistence, account isolation, mobile layout, failure/retry, existing popular albums");
} finally {
  await browser?.close();
  server.close();
  python.kill();
}
