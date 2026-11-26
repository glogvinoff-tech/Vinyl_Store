import { chromium, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const dist = resolve(root, 'frontend/dist');
const forecast = JSON.parse(execFileSync(process.env.RECOMMENDER_PYTHON || 'python', ['-c',
  "import json; from forecast import build_forecast; print(json.dumps(build_forecast('2026-11-01')))"],
  { cwd: resolve(root, 'recommender'), encoding: 'utf8', windowsHide: true }));
const server = createServer(async (req, res) => {
  try {
    const path = new URL(req.url, 'http://localhost').pathname;
    const file = extname(path) ? resolve(dist, '.' + path) : resolve(dist, 'index.html');
    if (!file.startsWith(dist + sep)) throw Error();
    res.setHeader('Content-Type', ({ '.js': 'application/javascript', '.css': 'text/css', '.jpg': 'image/jpeg', '.svg': 'image/svg+xml', '.ttf': 'font/ttf' })[extname(file)] || 'text/html');
    res.end(await readFile(file));
  } catch { res.statusCode = 404; res.end(); }
});
let browser;
try {
  await new Promise(done => server.listen(0, '127.0.0.1', done));
  browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || 'msedge' });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = false;
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/auth/refresh') return route.fulfill({ json: { accessToken: 'test', user: { id: 'admin', displayName: 'Admin', roles: ['ADMIN'] } } });
    if (path === '/api/analytics/forecast') return route.fulfill(failed ? { status: 503, json: { message: 'Прогноз временно недоступен' } } : { json: forecast });
    if (path === '/api/records') return route.fulfill({ json: [] });
    return route.fulfill({ json: { content: [], totalPages: 1 } });
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/admin?forecast=1`);
  const dialog = page.getByRole('dialog', { name: 'ИИ-прогноз продаж' });
  await expect(dialog).toBeVisible();
  await expect(dialog.locator('tbody tr')).toHaveCount(2);
  await dialog.getByLabel('Детализация').selectOption('days');
  await expect(dialog.locator('tbody tr')).toHaveCount(31);
  await dialog.getByLabel('Детализация').selectOption('years');
  await expect(dialog.locator('tbody tr')).toHaveCount(2);
  await dialog.getByLabel(/^Данные/).selectOption('history');
  await dialog.getByLabel('Детализация').selectOption('months');
  await expect(dialog.locator('tbody tr')).toHaveCount(2);
  await mkdir(resolve(root, '.playwright'), { recursive: true });
  await dialog.screenshot({ path: resolve(root, '.playwright/forecast-desktop.png') });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(dialog).toBeVisible();
  expect(await dialog.evaluate(el => el.getBoundingClientRect().width <= window.innerWidth)).toBe(true);
  await dialog.screenshot({ path: resolve(root, '.playwright/forecast-mobile.png') });
  await dialog.getByRole('button', { name: 'Закрыть', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  failed = true;
  await page.getByRole('button', { name: 'ИИ-прогноз продаж ↗' }).click();
  await expect(dialog.getByRole('alert')).toBeVisible();
  failed = false;
  await dialog.getByRole('button', { name: 'Повторить прогноз' }).click();
  await expect(dialog.locator('tbody tr')).toHaveCount(2);
  expect(errors).toEqual([]);
  console.log('PASS: forecast dialog, monthly/yearly/daily views, history, mobile, close, failure/retry; real Python model output');
} finally { await browser?.close(); server.close(); }
