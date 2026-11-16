// Walks through the app the way its owner does, against the real server on an empty data
// directory: first start, import, the list and its filters, the activity page, settings.
// The tests share that server and run in order; each one builds on what the previous ones left.
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

test.describe.configure({ mode: 'serial' });

const fixture = (name: string) => readFileSync(fileURLToPath(new URL(`../../server/tests/fixtures/${name}`, import.meta.url)));

/** A GPX run of about 3 km heading north, one point every five seconds, started `daysAgo` days ago. */
function gpx(daysAgo: number): Buffer {
	const start = Date.now() - daysAgo * 86_400_000;
	const points = Array.from({ length: 240 }, (_, i) => {
		const lat = (48.85 + i * 0.000115).toFixed(6);
		const time = new Date(start + i * 5000).toISOString();
		return `<trkpt lat="${lat}" lon="2.350000"><ele>${(35 + Math.sin(i / 20) * 4).toFixed(1)}</ele><time>${time}</time></trkpt>`;
	});
	return Buffer.from(
		`<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" creator="e2e" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>e2e</name><trkseg>${points.join('')}</trkseg></trk></gpx>`
	);
}

const WRITE = { 'X-Requested-With': 'e2e' };

/** Gives an activity a title through the API, so later steps can find it by name. */
async function rename(request: APIRequestContext, id: string, title: string) {
	const res = await request.patch(`/api/v1/activities/${id}`, { headers: WRITE, data: { title } });
	expect(res.ok()).toBeTruthy();
}

/** The id of the activity a result row of the import screen links to. */
async function importedId(page: Page, file: string): Promise<string> {
	const href = await page.locator('.frow', { hasText: file }).getByRole('link', { name: 'Voir' }).getAttribute('href');
	return href!.split('/').pop()!;
}

const nav = (page: Page, name: string) => page.getByRole('navigation', { name: 'Navigation principale' }).getByRole('link', { name });

test.beforeEach(async ({ page }) => {
	// Nothing but the server under test may be contacted.
	await page.route(
		(url) => url.hostname !== '127.0.0.1' && url.hostname !== 'localhost',
		(route) => route.abort()
	);
});

test('first start asks for a profile, then shows an empty list', async ({ page }) => {
	await page.goto('/');
	await expect(page.getByRole('heading', { name: 'Bienvenue' })).toBeVisible();
	const create = page.getByRole('button', { name: 'Créer mon profil' });
	await expect(create).toBeDisabled();
	await page.getByLabel('Votre prénom').fill('Alex');
	await create.click();

	await expect(page.getByRole('heading', { name: 'Activités' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Aucune sortie pour Alex' })).toBeVisible();
});

test('a mixed batch reports imported, duplicate and failed files', async ({ page, request }) => {
	await page.goto('/import');
	await page.locator('#files').setInputFiles([
		{ name: 'trois-tours.fit', mimeType: 'application/octet-stream', buffer: fixture('android-recorder.fit') },
		{ name: 'recente.gpx', mimeType: 'application/gpx+xml', buffer: gpx(2) },
		{ name: 'ancienne.gpx', mimeType: 'application/gpx+xml', buffer: gpx(400) },
		{ name: 'abimee.gpx', mimeType: 'application/gpx+xml', buffer: Buffer.from('this is not a run') }
	]);
	const result = page.locator('section.card', { has: page.getByRole('heading', { name: 'Résultat' }) });
	await expect(result).toBeVisible();
	await expect(result.getByText('3 importées')).toBeVisible();
	await expect(result.getByText('1 échec')).toBeVisible();
	await expect(page.locator('.frow', { hasText: 'abimee.gpx' }).getByRole('link')).toHaveCount(0);

	await rename(request, await importedId(page, 'trois-tours.fit'), 'Trois tours');
	await rename(request, await importedId(page, 'recente.gpx'), 'Récente');
	await rename(request, await importedId(page, 'ancienne.gpx'), 'Ancienne');

	// The same run again, from a file with other content: recognised by its start time and duration.
	await page.locator('#files').setInputFiles([
		{ name: 'trois-tours-bis.fit', mimeType: 'application/octet-stream', buffer: fixture('android-recorder-nohr.fit') }
	]);
	await expect(result.getByText('1 doublon')).toBeVisible();
	await expect(result.getByText('0 importée')).toBeVisible();
});

test('the list shows the runs and filters them by period and by tag', async ({ page, request }) => {
	await page.goto('/');
	const cards = page.locator('a.act');
	await expect(cards).toHaveCount(3);
	await expect(cards.first().locator('.thumb svg')).toBeVisible();

	const period = page.getByRole('group', { name: 'Période' });
	await period.getByRole('button', { name: '3 mois' }).click();
	await expect(page.getByRole('heading', { name: 'Récente' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Ancienne' })).toHaveCount(0);
	await period.getByRole('button', { name: 'Tout' }).click();
	await expect(page.getByRole('heading', { name: 'Ancienne' })).toBeVisible();

	// Tag one run, then filter on that tag.
	const id = (await cards.filter({ hasText: 'Récente' }).getAttribute('href'))!.split('/').pop()!;
	const res = await request.patch(`/api/v1/activities/${id}`, { headers: WRITE, data: { tags: ['Fractionné'] } });
	expect(res.ok()).toBeTruthy();
	await page.reload();
	await page.getByRole('group', { name: 'Filtrer par tag' }).getByRole('button', { name: 'Fractionné' }).click();
	await expect(cards).toHaveCount(1);
	await expect(page.getByRole('heading', { name: 'Récente' })).toBeVisible();

	// A tag and a period that exclude everything: the empty state offers to reset.
	await period.getByRole('button', { name: '3 mois' }).click();
	await page.getByRole('group', { name: 'Filtrer par tag' }).getByRole('button', { name: 'Toutes' }).click();
	await expect(cards.filter({ hasText: 'Ancienne' })).toHaveCount(0);
});

test('the activity page links chart and map, switches measure and table', async ({ page }) => {
	await page.goto('/');
	await page.locator('a.act', { hasText: 'Trois tours' }).click();
	await expect(page.getByRole('heading', { name: 'Trois tours', level: 1 })).toBeVisible();

	// Start and finish markers; the cursor marker only exists while something is hovered.
	const markers = page.locator('.maplibregl-marker');
	await expect(markers).toHaveCount(2);
	const chart = page.getByRole('img', { name: /en fonction de la distance/ });
	// The chart may be below the fold: hover() scrolls it into view first.
	const box = (await chart.boundingBox())!;
	const y = box.height * 0.5;
	await chart.hover({ position: { x: box.width * 0.3, y } });
	await expect(markers).toHaveCount(3);
	const at30 = await markers.last().evaluate((el) => (el as HTMLElement).style.transform);
	await chart.hover({ position: { x: box.width * 0.7, y } });
	await expect.poll(() => markers.last().evaluate((el) => (el as HTMLElement).style.transform)).not.toBe(at30);
	await page.getByRole('heading', { name: 'Trois tours', level: 1 }).hover();
	await expect(markers).toHaveCount(2);

	// Pace is shown first; heart rate is available on this run.
	const measures = page.getByRole('group', { name: 'Donnée affichée' });
	await expect(measures.getByRole('button', { pressed: true })).toHaveCount(1);
	const hr = measures.getByRole('button', { name: /cardiaque|FC/i });
	await hr.click();
	await expect(hr).toHaveAttribute('aria-pressed', 'true');
	await expect(page.getByRole('img', { name: /en fonction de la distance/ })).toBeVisible();

	// Five zones, and the notice that they rest on a default maximum.
	const zones = page.getByRole('region', { name: 'Zones de fréquence cardiaque' });
	await expect(zones.getByText('Indiquez la vôtre')).toBeVisible();
	for (const z of ['Z1', 'Z2', 'Z3', 'Z4', 'Z5']) await expect(zones.getByText(z, { exact: true })).toBeVisible();

	// Kilometre splits by default, the three recorded laps on demand.
	const cut = page.getByRole('region', { name: 'Découpage' });
	await expect(cut.locator('tbody tr')).toHaveCount(6);
	await cut.getByRole('button', { name: 'Tours' }).click();
	await expect(cut.locator('tbody tr')).toHaveCount(3);
});

test('the journal is edited in place', async ({ page }) => {
	await page.goto('/');
	await page.locator('a.act', { hasText: 'Trois tours' }).click();
	await page.getByRole('button', { name: 'Modifier le journal' }).click();
	await page.getByLabel('Titre').fill('Sortie à deux');
	await page.getByLabel('Description').fill('Bonnes jambes.');
	await page.getByRole('button', { name: '7 sur 10' }).click();
	await page.getByLabel('Nouveau tag').fill('Duo');
	await page.getByRole('button', { name: 'Ajouter le tag' }).click();
	await page.getByRole('button', { name: 'Enregistrer' }).click();

	await expect(page.getByRole('heading', { name: 'Sortie à deux', level: 1 })).toBeVisible();
	const journal = page.getByRole('region', { name: 'Journal' });
	await expect(journal.getByText('Bonnes jambes.')).toBeVisible();
	await expect(journal.getByText('7', { exact: true })).toBeVisible();
	await expect(journal.getByText('Duo')).toBeVisible();

	// Still there after a reload: it was saved, not only shown.
	await page.reload();
	await expect(page.getByRole('heading', { name: 'Sortie à deux', level: 1 })).toBeVisible();
});

test('settings recompute the zones and manage the household profiles', async ({ page }) => {
	await page.goto('/settings');
	await expect(page.getByRole('heading', { name: 'Réglages' })).toBeVisible();
	await expect(page.getByText('valeur par défaut de 190 bpm')).toBeVisible();
	await expect(page.getByText('171–190 bpm')).toBeVisible();

	const max = page.getByLabel('Fréquence cardiaque maximale');
	await max.fill('200');
	await max.dispatchEvent('change');
	await expect(page.getByText('180–200 bpm')).toBeVisible();
	await expect(page.getByText('valeur par défaut de 190 bpm')).toHaveCount(0);

	// A second profile starts empty.
	await page.getByRole('button', { name: 'Ajouter un profil' }).click();
	const sheet = page.getByRole('dialog');
	await sheet.getByRole('button', { name: 'Nouveau profil' }).click();
	await sheet.getByLabel('Prénom').fill('Sam');
	await sheet.getByRole('button', { name: 'Créer', exact: true }).click();
	await expect(page.locator('.prow', { hasText: 'Sam' })).toBeVisible();
	const activate = page.locator('.prow', { hasText: 'Sam' }).getByRole('button', { name: 'Activer' });
	if (await activate.isVisible()) await activate.click();
	await expect(page.locator('.prow', { hasText: 'Sam' }).getByText('Actif')).toBeVisible();
	await nav(page, 'Activités').click();
	await expect(page.getByRole('heading', { name: 'Aucune sortie pour Sam' })).toBeVisible();

	// Back to the first profile for the next test.
	await page.goto('/settings');
	await page.locator('.prow', { hasText: 'Alex' }).getByRole('button', { name: 'Activer' }).click();
	await expect(page.locator('.prow', { hasText: 'Alex' }).getByText('Actif')).toBeVisible();
});

test('a run is copied without heart rate, moved, exported and deleted', async ({ page }) => {
	await page.goto('/');
	await page.locator('a.act', { hasText: 'Sortie à deux' }).click();

	// The zones now use the maximum given in the settings.
	await expect(page.getByRole('region', { name: 'Zones de fréquence cardiaque' }).getByText('Indiquez la vôtre')).toHaveCount(0);

	// Export downloads a GPX file.
	const download = page.waitForEvent('download');
	await page.getByRole('link', { name: 'Exporter en GPX' }).click();
	expect((await download).suggestedFilename()).toMatch(/\.gpx$/);

	// Copy to Sam: heart rate stays behind by default.
	await page.getByRole('button', { name: 'Copier vers un profil' }).click();
	const dialog = page.getByRole('dialog');
	await dialog.getByRole('button', { name: 'Sam' }).click();
	await expect(dialog.getByRole('button', { name: /Conserver la fréquence cardiaque/ })).toHaveAttribute('aria-pressed', 'false');
	await dialog.getByRole('button', { name: 'Copier', exact: true }).click();
	await expect(dialog).toHaveCount(0);

	// Move the old run to Sam as well, then delete the recent one.
	await page.goto('/');
	await page.locator('a.act', { hasText: 'Ancienne' }).click();
	await page.getByRole('button', { name: 'Autres actions' }).click();
	await page.getByRole('button', { name: 'Déplacer vers un profil' }).click();
	await dialog.getByRole('button', { name: 'Sam' }).click();
	await dialog.getByRole('button', { name: 'Déplacer', exact: true }).click();
	await expect(page.getByRole('heading', { name: 'Activités' })).toBeVisible();
	await expect(page.locator('a.act')).toHaveCount(2);

	await page.locator('a.act', { hasText: 'Récente' }).click();
	await page.getByRole('button', { name: 'Autres actions' }).click();
	await page.getByRole('button', { name: 'Supprimer' }).click();
	await dialog.getByRole('button', { name: 'Supprimer' }).click();
	await expect(page.getByRole('heading', { name: 'Activités' })).toBeVisible();
	await expect(page.locator('a.act')).toHaveCount(1);
	await expect(page.locator('a.act', { hasText: 'Sortie à deux' })).toBeVisible();

	// Sam has the copy, marked as such and without heart rate, and the moved run.
	await page.getByRole('button', { name: /Changer de profil/ }).first().click();
	await dialog.getByRole('button', { name: /Sam/ }).click();
	await expect(page.locator('a.act')).toHaveCount(2);
	const copy = page.locator('a.act', { hasText: 'Copie' });
	await expect(copy).toHaveCount(1);
	await copy.click();
	await expect(page.getByRole('group', { name: 'Couleur du tracé' }).getByRole('button', { name: 'FC' })).toBeDisabled();
});

test('the trace is drawn on a plain background when the tile host is out of reach', async ({ page }) => {
	// Whatever the server is configured with, pretend a map style is set and its host is blocked
	// (beforeEach aborts every request that leaves the machine).
	await page.route('**/api/v1/config', async (route) => {
		const res = await route.fetch();
		await route.fulfill({ response: res, json: { ...(await res.json()), map_style_url: 'https://tiles.invalid/style.json', map_style_url_dark: 'https://tiles.invalid/style.json' } });
	});
	await page.goto('/');
	await page.locator('a.act').first().click();
	await expect(page.locator('.maplibregl-marker')).toHaveCount(2);
	await expect(page.locator('.maplibregl-canvas')).toBeVisible();
});

test('an unknown activity shows the not-found state', async ({ page }) => {
	for (const id of ['999999', 'does-not-exist']) {
		await page.goto(`/activities/${id}`);
		await expect(page.getByRole('heading', { name: "Cette sortie n'existe pas" })).toBeVisible();
	}
});
