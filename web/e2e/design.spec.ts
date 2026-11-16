// Design checks on the data left by app.spec.ts (this file runs after it): text contrast on
// every screen in both themes at phone and desktop widths, and, on demand, the screenshots that
// set the built activity page beside the approved prototype in docs/design-direction.md.
//
//   UPDATE_SCREENSHOTS=1 E2E_BASEMAP=1 npm run test:e2e     (rewrites docs/screenshots/)
import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';

const SHOTS = fileURLToPath(new URL('../../docs/screenshots/', import.meta.url));
const PROTOTYPE = pathToFileURL(fileURLToPath(new URL('../../prototype/activity-detail.html', import.meta.url))).href;
const capture = !!process.env.UPDATE_SCREENSHOTS;

const SIZES = [
	{ name: '360', viewport: { width: 360, height: 780 } },
	{ name: '1440', viewport: { width: 1440, height: 900 } }
];
const SCHEMES = ['light', 'dark'] as const;

/** Text whose contrast with its background is under 4.5:1 (3:1 for large text), as axe measures it. */
async function lowContrast(page: Page): Promise<string[]> {
	const result = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze();
	return result.violations.flatMap((v) => v.nodes.map((n) => `${n.target.join(' ')} — ${n.any[0]?.message ?? v.help}`));
}

async function openRun(page: Page) {
	await page.goto('/');
	await page.locator('a.act', { hasText: 'Sortie à deux' }).click();
	await expect(page.getByRole('heading', { name: 'Sortie à deux', level: 1 })).toBeVisible();
	await expect(page.locator('.maplibregl-marker')).toHaveCount(2);
	await expect(page.getByRole('img', { name: /en fonction de la distance/ })).toBeVisible();
}

for (const scheme of SCHEMES) {
	for (const size of SIZES) {
		test.describe(`${scheme} theme at ${size.name} px`, () => {
			test.use({ colorScheme: scheme, viewport: size.viewport });

			test('text contrast reaches 4.5:1 on every screen', async ({ page }) => {
				await openRun(page);
				expect.soft(await lowContrast(page), 'activity page').toEqual([]);
				await page.getByRole('button', { name: 'Modifier le journal' }).click();
				expect.soft(await lowContrast(page), 'journal being edited').toEqual([]);
				for (const [path, heading] of [['/', 'Activités'], ['/import', 'Importer'], ['/settings', 'Réglages']]) {
					await page.goto(path);
					await expect(page.getByRole('heading', { name: heading, level: 1 })).toBeVisible();
					await expect(page.locator('.skeleton')).toHaveCount(0);
					expect.soft(await lowContrast(page), path).toEqual([]);
				}
			});

			test('the page does not scroll sideways', async ({ page }) => {
				await openRun(page);
				const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
				expect(overflow).toBeLessThanOrEqual(0);
			});

			test('screenshots of the built page and of the prototype', async ({ page }) => {
				test.skip(!capture, 'only with UPDATE_SCREENSHOTS=1');
				mkdirSync(SHOTS, { recursive: true });
				await openRun(page);
				// Let map tiles and fonts settle.
				await page.waitForLoadState('networkidle');
				await page.waitForTimeout(1500);
				await page.screenshot({ path: `${SHOTS}app-${scheme}-${size.name}.jpg`, fullPage: true, type: 'jpeg', quality: 72 });

				await page.goto(`${PROTOTYPE}#activite`);
				await page.waitForLoadState('networkidle');
				await page.waitForTimeout(800);
				await page.screenshot({ path: `${SHOTS}prototype-${scheme}-${size.name}.jpg`, fullPage: true, type: 'jpeg', quality: 72 });
			});
		});
	}
}
