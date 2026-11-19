// The Progression section — statistics, records, load — against the real server.
// Runs after app.spec.ts and design.spec.ts, on the data they left, and adds four recent runs
// for the active profile so that there is a recent history to show.
import AxeBuilder from '@axe-core/playwright';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';

test.describe.configure({ mode: 'serial' });

const WRITE = { 'X-Requested-With': 'e2e' };

/** A GPX run of about 3 km heading north in about 20 minutes, started `daysAgo` days ago at 07:00 UTC. */
function gpx(daysAgo: number, heartRate?: number): Buffer {
	const day = new Date(Date.now() - daysAgo * 86_400_000);
	const start = Date.UTC(day.getUTCFullYear(), day.getUTCMonth(), day.getUTCDate(), 7);
	const points = Array.from({ length: 240 }, (_, i) => {
		const lat = (48.85 + i * 0.000115).toFixed(6);
		const hr = heartRate ? `<extensions><hr>${heartRate}</hr></extensions>` : '';
		return `<trkpt lat="${lat}" lon="2.350000"><ele>35.0</ele><time>${new Date(start + i * 5000).toISOString()}</time>${hr}</trkpt>`;
	});
	return Buffer.from(
		`<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" creator="e2e" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>${points.join('')}</trkseg></trk></gpx>`
	);
}

let alex = 0;

async function api<T>(request: APIRequestContext, path: string): Promise<T> {
	const res = await request.get(`/api/v1${path}`);
	expect(res.ok(), path).toBeTruthy();
	return res.json();
}

/** Opens a tab of the section with Alex as the active profile. */
async function open(page: Page, tab: 'stats' | 'records' | 'load') {
	await page.addInitScript((id) => localStorage.setItem('running-profile', String(id)), alex);
	await page.goto(tab === 'stats' ? '/progress' : `/progress?tab=${tab}`);
	await expect(page.getByRole('heading', { name: 'Progression', level: 1 })).toBeVisible();
	// The test server is a debug build: an aggregate over the whole history can take a few seconds.
	await expect(page.locator('.skeleton')).toHaveCount(0, { timeout: 15_000 });
}

test.beforeEach(async ({ page }) => {
	await page.route(
		(url) => url.hostname !== '127.0.0.1' && url.hostname !== 'localhost',
		(route) => route.abort()
	);
});

test('recent runs are imported and processed in the background', async ({ request }) => {
	const { profiles } = await api<{ profiles: { id: number; name: string }[] }>(request, '/profiles');
	alex = profiles.find((p) => p.name === 'Alex')!.id;
	const files = [1, 3, 10, 40].map((days, i) => ({ days, hr: i === 0 ? undefined : 150 }));
	for (const f of files) {
		const res = await request.post(`/api/v1/profiles/${alex}/imports`, {
			headers: WRITE,
			multipart: { files: { name: `j-${f.days}.gpx`, mimeType: 'application/gpx+xml', buffer: gpx(f.days, f.hr) } }
		});
		expect((await res.json()).results[0].status).toBe('imported');
	}
	// Records, load and segments follow the import; wait until nothing is pending.
	await expect
		.poll(async () => (await api<{ busy: boolean }>(request, `/profiles/${alex}/derive-status`)).busy, { timeout: 30_000 })
		.toBe(false);
});

test('the navigation leads to Progression and its three tabs', async ({ page }) => {
	await page.goto('/');
	const nav = page.getByRole('navigation', { name: 'Navigation principale' });
	await expect(nav.getByRole('link')).toHaveText(['Activités', 'Progression', 'Réglages']);
	// Import is reached from the activity list, and still belongs to that section.
	await page.getByRole('link', { name: 'Importer' }).click();
	await expect(page.getByRole('heading', { name: 'Importer', level: 1 })).toBeVisible();
	await expect(nav.getByRole('link', { name: 'Activités' })).toHaveAttribute('aria-current', 'page');

	await nav.getByRole('link', { name: 'Progression' }).click();
	const tabs = page.getByRole('group', { name: 'Rubriques de la progression' });
	await expect(tabs.getByRole('button', { name: 'Statistiques' })).toHaveAttribute('aria-pressed', 'true');
	await tabs.getByRole('button', { name: 'Records' }).click();
	await expect(page).toHaveURL(/tab=records/);
	// The tab survives a reload.
	await page.reload();
	await expect(tabs.getByRole('button', { name: 'Records' })).toHaveAttribute('aria-pressed', 'true');
	await expect(nav.getByRole('link', { name: 'Progression' })).toHaveAttribute('aria-current', 'page');
});

test('statistics show the calendar, the volume and the progress curves', async ({ page, request }) => {
	const year = new Date().getFullYear();
	const tz = -new Date().getTimezoneOffset();
	const cal = await api<{ days: { date: string }[]; totals: { runs: number; active_days: number } }>(
		request,
		`/profiles/${alex}/stats/calendar?year=${year}&tz_offset_minutes=60`
	);
	void tz;
	await open(page, 'stats');

	// The year's totals and one filled square per day run.
	const calendar = page.locator('section.card', { has: page.getByRole('heading', { name: 'Calendrier' }) });
	await expect(calendar.locator('.tile', { hasText: 'Sorties' }).locator('.v')).toHaveText(String(cal.totals.runs));
	const grid = calendar.getByRole('group', { name: `Calendrier ${year}` });
	await expect(grid.locator('button.l1, button.l2, button.l3, button.l4')).toHaveCount(cal.totals.active_days);

	// A day with a run names it and links to it; a rest day says so.
	await grid.locator('button.l1, button.l2, button.l3, button.l4').last().click();
	const detail = calendar.locator('.daybox');
	await expect(detail.getByRole('link')).not.toHaveCount(0);
	await grid.locator('button.l0:not(:disabled)').first().click();
	await expect(detail).toContainText('Repos.');

	// Another measure recolours the squares; a year without runs is empty, not an error.
	await calendar.getByRole('group', { name: 'Mesure du calendrier' }).getByRole('button', { name: 'Charge' }).click();
	await expect(grid.locator('button.l1, button.l2, button.l3, button.l4')).toHaveCount(cal.totals.active_days);
	await calendar.getByRole('group', { name: 'Année' }).getByRole('button', { name: String(year - 2) }).click();
	await expect(calendar.getByText(`Aucune course en ${year - 2}.`)).toBeVisible();
	await expect(calendar.locator('.tile', { hasText: 'Sorties' }).locator('.v')).toHaveText('0');

	// Twelve bars per week, then per month; the period in progress is marked.
	const volume = page.locator('section.card', { has: page.getByRole('heading', { name: 'Volume' }) });
	await expect(volume.locator('.b')).toHaveCount(12);
	await expect(volume.locator('.b.cur')).toHaveCount(1);
	await volume.getByRole('button', { name: 'Mois' }).click();
	await expect(volume.getByRole('img', { name: /Distance par mois/ })).toBeVisible();
	await expect(volume.locator('.b')).toHaveCount(12);

	// Curves: pace and longest run have values; the tag filter narrows them to tagged runs.
	const curves = page.locator('section.card', { has: page.getByRole('heading', { name: 'Courbes de progression' }) });
	await expect(curves.getByRole('img', { name: /Allure moyenne par mois/ })).toBeVisible();
	await expect(curves.getByRole('img', { name: /Fréquence cardiaque moyenne par mois/ })).toBeVisible();
	await curves.getByLabel('Courses').selectOption({ label: 'Compétition' });
	await expect(curves.getByText('Pas encore de données.')).toHaveCount(3);
});

test('records list the best times, their history and the predictions', async ({ page, request }) => {
	const { distances } = await api<{ distances: { distance: number; record: { moving_time: number; activity_id: number } | null }[] }>(
		request,
		`/profiles/${alex}/records`
	);
	await open(page, 'records');
	await expect(page.getByText('temps en mouvement')).toBeVisible();

	const cards = page.getByRole('group', { name: 'Records par distance' }).getByRole('button');
	await expect(cards).toHaveCount(6);
	// Runs of 3 and 5 km: a record on 400 m and 1 km, none on the marathon.
	expect(distances.find((d) => d.distance === 1000)!.record).not.toBeNull();
	await expect(cards.filter({ hasText: '1 km' }).locator('.t')).toHaveText(/^\d+:\d\d$/);
	await expect(cards.filter({ hasText: 'Marathon' }).first()).toContainText('Pas encore couru');

	// Selecting a distance shows how its record evolved and links to the run that holds it.
	await cards.filter({ hasText: '1 km' }).click();
	await expect(page.getByRole('heading', { name: 'Évolution · 1 km' })).toBeVisible();
	await expect(page.getByRole('img', { name: /Évolution du record sur 1 km/ })).toBeVisible();
	const record = distances.find((d) => d.distance === 1000)!.record!;
	await expect(page.getByRole('link', { name: 'Voir la course du record' })).toHaveAttribute('href', `/activities/${record.activity_id}`);
	await cards.filter({ hasText: 'Marathon' }).first().click();
	await expect(page.getByText('Pas encore de temps sur cette distance.')).toBeVisible();

	// Predictions name the effort they come from; the long ones are only indicative.
	const predictions = page.locator('section.card', { has: page.getByRole('heading', { name: 'Chronos potentiels' }) });
	await expect(predictions.locator('.pred')).toHaveCount(4);
	await expect(predictions.locator('.pred').first()).toContainText("d'après votre");
	await expect(predictions.locator('.pred', { hasText: 'Marathon' }).getByText('Indicatif')).toBeVisible();
	await expect(predictions.getByText('Ce sont des estimations')).toBeVisible();
});

test('records of a profile without recent runs say so', async ({ page, request }) => {
	const { profiles } = await api<{ profiles: { id: number; name: string }[] }>(request, '/profiles');
	const sam = profiles.find((p) => p.name === 'Sam')!.id;
	await page.addInitScript((id) => localStorage.setItem('running-profile', String(id)), sam);
	await page.goto('/progress?tab=records');
	// Sam's runs are a copy of an older run and one from more than a year ago.
	const { predictions } = await api<{ predictions: unknown[] }>(request, `/profiles/${sam}/predictions`);
	if (predictions.length === 0) await expect(page.getByRole('heading', { name: "Pas d'effort récent" })).toBeVisible();
	// Statistics and records are personal: Sam's page does not count Alex's runs.
	const mine = await api<{ totals: { runs: number } }>(request, `/profiles/${sam}/stats/calendar`);
	await page.goto('/progress');
	await expect(page.locator('.tile', { hasText: 'Sorties' }).locator('.v')).toHaveText(String(mine.totals.runs));
});

test('load shows its status, the recovery and how it is computed', async ({ page, request }) => {
	const load = await api<{ status: { code: string }; recovery: { hours_advised: number } | null; days: unknown[] }>(request, `/profiles/${alex}/load`);
	await open(page, 'load');

	await expect(page.getByText('État de la charge')).toBeVisible();
	if (load.status.code === 'not_enough_data') await expect(page.getByText('Pas encore assez de données')).toBeVisible();
	else await expect(page.locator('.status .gauge i.on')).toHaveCount(1);

	// The latest run was yesterday: a recovery is advised, within its bounds.
	expect(load.recovery!.hours_advised).toBeGreaterThanOrEqual(6);
	expect(load.recovery!.hours_advised).toBeLessThanOrEqual(96);
	await expect(page.getByText('Récupération conseillée')).toBeVisible();
	await expect(page.getByText(/h conseillées après votre/)).toBeVisible();
	// That run has no heart rate: its load is marked as estimated.
	await expect(page.getByText('Charge estimée')).toBeVisible();

	await expect(page.getByRole('img', { name: /Charge quotidienne/ })).toBeVisible();
	await expect(page.getByRole('img', { name: /Charge par semaine/ })).toBeVisible();

	// The explanation says what the figures rest on, and what they ignore.
	await page.getByText('Comment est-ce calculé ?').click();
	await expect(page.getByText("ce n'est pas un avis médical")).toBeVisible();
});

async function lowContrast(page: Page): Promise<string[]> {
	const result = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze();
	return result.violations.flatMap((v) => v.nodes.map((n) => `${n.target.join(' ')} — ${n.any[0]?.message ?? v.help}`));
}

for (const scheme of ['light', 'dark'] as const) {
	for (const size of [
		{ name: '360', viewport: { width: 360, height: 780 } },
		{ name: '1440', viewport: { width: 1440, height: 900 } }
	]) {
		test.describe(`${scheme} theme at ${size.name} px`, () => {
			test.use({ colorScheme: scheme, viewport: size.viewport });

			test('the three tabs are readable and do not scroll sideways', async ({ page }) => {
				for (const tab of ['stats', 'records', 'load'] as const) {
					await open(page, tab);
					expect.soft(await lowContrast(page), `${tab} contrast`).toEqual([]);
					const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
					expect.soft(overflow, `${tab} sideways scroll`).toBeLessThanOrEqual(0);
				}
				// The activity list with its person filter and owner badges.
				await page.goto('/');
				await expect(page.locator('a.act').first()).toBeVisible();
				expect.soft(await lowContrast(page), 'household list contrast').toEqual([]);
			});
		});
	}
}
