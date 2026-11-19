// Segments, sessions, routes, their delivery to the phone and what the activity page gains,
// against the real server. Runs after progress.spec.ts, whose four runs along the same line
// give Alex a segment; Sam, created by app.spec.ts, has none.
import AxeBuilder from '@axe-core/playwright';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';

test.describe.configure({ mode: 'serial' });

const WRITE = { 'X-Requested-With': 'e2e' };
let alex = 0;
let sam = 0;

async function get<T>(request: APIRequestContext, path: string): Promise<T> {
	const res = await request.get(`/api/v1${path}`);
	expect(res.ok(), path).toBeTruthy();
	return res.json();
}
async function send<T>(request: APIRequestContext, method: 'post' | 'put' | 'patch', path: string, data: unknown): Promise<T> {
	const res = await request[method](`/api/v1${path}`, { headers: WRITE, data });
	expect(res.ok(), `${method} ${path}: ${await res.text()}`).toBeTruthy();
	return res.status() === 204 ? (undefined as T) : res.json();
}

/** A planned loop of about 6.3 km, with elevation or without, and no timestamps. */
function routeGpx(name: string, ele: boolean): Buffer {
	const pts = Array.from({ length: 81 }, (_, i) => {
		const a = (i / 80) * 2 * Math.PI;
		const e = ele ? `<ele>${(40 + 15 * Math.sin(a)).toFixed(1)}</ele>` : '';
		return `<rtept lat="${(48.86 + 0.009 * Math.sin(a)).toFixed(6)}" lon="${(2.3 + 0.0135 * Math.cos(a)).toFixed(6)}">${e}</rtept>`;
	});
	return Buffer.from(`<?xml version="1.0"?><gpx version="1.1" creator="e2e" xmlns="http://www.topografix.com/GPX/1/1"><rte><name>${name}</name>${pts.join('')}</rte></gpx>`);
}
/** The same kind of path as a track without times: not a run, but usable as a route. */
function untimedTrack(): Buffer {
	const pts = Array.from({ length: 50 }, (_, i) => `<trkpt lat="${(48.9 + i * 0.0002).toFixed(6)}" lon="2.4"><ele>30</ele></trkpt>`);
	return Buffer.from(`<?xml version="1.0"?><gpx version="1.1" creator="e2e" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>${pts.join('')}</trkseg></trk></gpx>`);
}

async function as(page: Page, profile: number, path: string) {
	await page.addInitScript((id) => localStorage.setItem('running-profile', String(id)), profile);
	await page.goto(path);
	await expect(page.locator('.skeleton')).toHaveCount(0, { timeout: 15_000 });
}

async function lowContrast(page: Page): Promise<string[]> {
	const result = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze();
	return result.violations.flatMap((v) => v.nodes.map((n) => `${n.target.join(' ')} — ${n.any[0]?.message ?? v.help}`));
}

test.beforeEach(async ({ page }) => {
	await page.route(
		(url) => url.hostname !== '127.0.0.1' && url.hostname !== 'localhost',
		(route) => route.abort()
	);
});

test('the navigation has Segments and Planifier', async ({ page, request }) => {
	const { profiles } = await get<{ profiles: { id: number; name: string }[] }>(request, '/profiles');
	alex = profiles.find((p) => p.name === 'Alex')!.id;
	sam = profiles.find((p) => p.name === 'Sam')!.id;
	await as(page, alex, '/');
	const nav = page.getByRole('navigation', { name: 'Navigation principale' });
	await expect(nav.getByRole('link')).toHaveText(['Activités', 'Progression', 'Segments', 'Planifier', 'Réglages']);
	await nav.getByRole('link', { name: 'Segments' }).click();
	await expect(page.getByRole('heading', { name: 'Segments', level: 1 })).toBeVisible();
	await expect(nav.getByRole('link', { name: 'Segments' })).toHaveAttribute('aria-current', 'page');
	await nav.getByRole('link', { name: 'Planifier' }).click();
	await expect(page.getByRole('heading', { name: 'Planifier', level: 1 })).toBeVisible();
});

test('segments: list, page, rename, hide and restore', async ({ page, request }) => {
	// Detection runs in the background after the imports of progress.spec.ts.
	await expect.poll(async () => (await get<{ items: unknown[] }>(request, `/profiles/${alex}/segments`)).items.length, { timeout: 30_000 }).toBeGreaterThan(0);
	await as(page, alex, '/segments');
	const rows = page.locator('tbody tr');
	await expect(rows.first()).toBeVisible();
	await expect(page.getByRole('button', { name: 'Les plus courus' })).toHaveAttribute('aria-pressed', 'true');
	await page.getByRole('button', { name: 'Les plus récents' }).click();
	await expect(rows.first()).toBeVisible();

	await rows.first().getByRole('link').click();
	await expect(page.getByRole('heading', { level: 1 })).toContainText('km');
	const efforts = page.locator('section.card', { has: page.getByRole('heading', { name: 'Tous les passages' }) }).locator('tbody tr');
	expect(await efforts.count()).toBeGreaterThanOrEqual(3);
	// The best is marked; every other effort shows its gap to it.
	await expect(efforts.first()).toContainText('Meilleur');
	await expect(efforts.nth(1)).toContainText(/\+\d+:\d\d/);
	await expect(page.getByRole('img', { name: /dans l'ordre des dates/ })).toBeVisible();

	await page.getByRole('button', { name: 'Renommer' }).click();
	await page.getByLabel('Nom du segment').fill('Le long du quai');
	await page.getByRole('button', { name: 'Enregistrer' }).click();
	await expect(page.getByRole('heading', { name: 'Le long du quai', level: 1 })).toBeVisible();

	await page.getByRole('button', { name: 'Masquer ce segment' }).click();
	await expect(page).toHaveURL(/\/segments$/);
	await expect(page.getByRole('link', { name: 'Le long du quai' })).toHaveCount(0);
	await page.getByRole('button', { name: /Segments masqués \(1\)/ }).click();
	const hidden = page.locator('tbody tr', { hasText: 'Le long du quai' });
	await hidden.getByRole('button', { name: 'Rétablir' }).click();
	await expect(page.getByText('Aucun segment masqué.')).toBeVisible();
	await page.getByRole('button', { name: /Segments masqués/ }).click();
	await expect(page.getByRole('link', { name: 'Le long du quai' })).toBeVisible();
});

test('segments of a profile without any explain how they appear', async ({ page }) => {
	await as(page, sam, '/segments');
	await expect(page.getByRole('heading', { name: 'Pas encore de segment' })).toBeVisible();
	await expect(page.getByText('trois fois la même portion')).toBeVisible();
});

test('session editor: steps, repeats, targets, totals and checks', async ({ page, request }) => {
	await as(page, alex, '/plan/workouts/new');
	await page.getByLabel('Nom').fill('6 × 400 m');
	// Nothing to save without a step.
	await page.getByRole('button', { name: 'Enregistrer' }).click();
	await expect(page.getByRole('alert')).toContainText('au moins une étape');

	await page.getByRole('button', { name: 'Étape', exact: true }).click();
	await page.getByRole('button', { name: 'Bloc répété' }).click();
	await page.getByRole('button', { name: 'Étape', exact: true }).click();
	const kinds = page.getByLabel("Type d'étape");
	await expect(kinds).toHaveCount(4);
	await expect(kinds.nth(0)).toHaveValue('warmup');
	await kinds.nth(3).selectOption('cooldown');

	// A length out of range is refused with an explanation.
	await page.getByLabel('Distance en mètres').fill('20');
	await expect(page.getByRole('alert')).toContainText('50 m');
	await page.getByLabel('Distance en mètres').fill('400');
	await expect(page.getByRole('alert')).toHaveCount(0);

	// Six repeats unfold as twelve steps, eleven without the last recovery.
	await expect(page.getByText('14 étapes une fois')).toBeVisible();
	await page.getByRole('button', { name: 'Sans la dernière récupération' }).click();
	await expect(page.getByText('13 étapes une fois')).toBeVisible();
	// All timed but the 400 m without pace: the duration cannot be known.
	await expect(page.locator('.tile', { hasText: 'Durée' })).toContainText('inconnue');

	// A pace range typed the wrong way round is saved in order.
	const targets = page.getByLabel('Cible', { exact: true });
	await targets.nth(1).selectOption('pace');
	await page.getByLabel('Allure la plus rapide').fill('5:15');
	await page.getByLabel('Allure la plus lente').fill('5:00');
	await expect(page.locator('.tile', { hasText: 'Durée' })).toContainText('≈');
	// A heart-rate zone shows the profile's bounds.
	await targets.nth(0).selectOption('hr_zone');
	await page.getByLabel('Zone cardiaque').selectOption('2');
	await expect(page.getByLabel('Zone cardiaque')).toContainText(/Z2 · \d+–\d+ bpm/);
	// An open-ended recovery: the duration becomes a minimum.
	await page.getByLabel("Fin de l'étape").nth(2).selectOption('lap');
	await expect(page.locator('.tile', { hasText: 'Durée' })).toContainText('au moins');

	await page.getByRole('button', { name: 'Enregistrer' }).click();
	await expect(page).toHaveURL(/\/plan$/);
	const card = page.locator('.lib', { hasText: '6 × 400 m' });
	await expect(card).toBeVisible();
	await expect(card).toContainText('13 étapes');

	const { items } = await get<{ items: { name: string; steps: { type: string; steps?: { target: { type: string; fast?: number; slow?: number } }[] }[] }[] }>(
		request,
		`/profiles/${alex}/workouts`
	);
	const block = items.find((w) => w.name === '6 × 400 m')!.steps.find((s) => s.type === 'repeat')!;
	expect(block.steps![0].target).toEqual({ type: 'pace', fast: 300, slow: 315 });
});

test('sessions: duplicate, copy to a profile, delete, starters', async ({ page, request }) => {
	await as(page, alex, '/plan');
	await page.getByRole('button', { name: 'Dupliquer 6 × 400 m' }).click();
	await expect(page.locator('.lib', { hasText: '6 × 400 m (copie)' })).toBeVisible();
	await expect(page.locator('.lib h3', { hasText: /^6 × 400 m$/ })).toBeVisible();

	await page.getByRole('button', { name: 'Copier 6 × 400 m (copie) vers un profil' }).click();
	await page.getByRole('dialog').getByRole('button', { name: 'Copier' }).click();
	await expect.poll(async () => (await get<{ items: { name: string }[] }>(request, `/profiles/${sam}/workouts`)).items.map((w) => w.name)).toContain('6 × 400 m (copie)');

	await page.getByRole('button', { name: 'Supprimer 6 × 400 m (copie)' }).click();
	await page.getByRole('dialog').getByRole('button', { name: 'Supprimer' }).click();
	await expect(page.locator('.lib', { hasText: '(copie)' })).toHaveCount(0);

	// Sam's copy is independent; once removed, the library is empty and offers starters.
	const samCopy = (await get<{ items: { id: number }[] }>(request, `/profiles/${sam}/workouts`)).items[0];
	expect((await request.delete(`/api/v1/workouts/${samCopy.id}`, { headers: WRITE })).ok()).toBeTruthy();
	await as(page, sam, '/plan');
	await expect(page.getByRole('heading', { name: 'Aucune séance' })).toBeVisible();
	await page.getByRole('button', { name: 'Ajouter Footing facile 30 min' }).click();
	await expect(page.locator('.lib', { hasText: 'Footing facile 30 min' })).toContainText('30:00');
});

test('delivery: waiting, delivered, then waiting again after an edit', async ({ page, request }) => {
	await as(page, alex, '/plan');
	const card = page.locator('.lib', { hasText: '6 × 400 m' });
	await card.getByRole('button', { name: /Envoyer « 6 × 400 m » sur le téléphone/ }).click();
	await expect(card.getByText('En attente du téléphone')).toBeVisible();

	// The phone fetches its plan at home, then states what it holds.
	const plan = await get<{ workouts: { id: number; version: number }[] }>(request, `/profiles/${alex}/plan`);
	await send(request, 'put', `/profiles/${alex}/plan/receipt`, { device: 'Pixel 8', items: plan.workouts.map((w) => ({ kind: 'workout', id: w.id, version: w.version })) });
	await page.reload();
	await expect(card.getByText(/Sur le téléphone depuis aujourd'hui, \d\d:\d\d/)).toBeVisible();
	await expect(page.getByText(/Dernière synchro du téléphone : aujourd'hui/)).toBeVisible();

	await send(request, 'put', `/workouts/${plan.workouts[0].id}`, { note: 'Changée après la synchro.' });
	await page.reload();
	await expect(card.getByText('En attente du téléphone')).toBeVisible();
});

test('routes: import, page with profile, reverse, rename, export, copy, delete', async ({ page, request }) => {
	await as(page, sam, '/plan?tab=routes');
	await expect(page.getByRole('heading', { name: 'Aucun parcours' })).toBeVisible();

	await as(page, alex, '/plan?tab=routes');
	const chooser = page.waitForEvent('filechooser');
	await page.getByRole('button', { name: 'Importer un .gpx' }).first().click();
	await (await chooser).setFiles({ name: 'canal.gpx', mimeType: 'application/gpx+xml', buffer: routeGpx('Boucle du canal', true) });
	await expect(page.getByRole('heading', { name: 'Boucle du canal', level: 1 })).toBeVisible();
	await expect(page.getByText('Boucle', { exact: true })).toBeVisible();
	await expect(page.getByText(/\d+ m D\+ · \d+ m D−/)).toBeVisible();
	await expect(page.getByText('Pas encore couru.')).toBeVisible();

	// Hovering the profile marks the point on the map.
	const markers = page.locator('.maplibregl-marker');
	await expect(markers).toHaveCount(2);
	const chart = page.getByRole('img', { name: /Altitude/ }).first();
	// The chart may be below the fold: hover() scrolls it into view first.
	await chart.hover();
	await expect(markers).toHaveCount(3);

	await expect(page.getByRole('link', { name: 'Exporter en .gpx' })).toHaveAttribute('href', /\/routes\/\d+\/export\.gpx$/);
	await page.getByRole('button', { name: 'Renommer' }).click();
	await page.getByLabel('Nom du parcours').fill('Tour du canal');
	await page.getByRole('button', { name: 'Enregistrer' }).click();
	await expect(page.getByRole('heading', { name: 'Tour du canal', level: 1 })).toBeVisible();

	await page.getByRole('button', { name: 'Créer le parcours inverse' }).click();
	await expect(page.getByRole('heading', { name: 'Tour du canal (inverse)', level: 1 })).toBeVisible();
	await page.getByRole('button', { name: 'Copier vers un profil' }).click();
	await page.getByRole('dialog').getByRole('button', { name: 'Copier' }).click();
	await expect.poll(async () => (await get<{ items: { name: string }[] }>(request, `/profiles/${sam}/routes`)).items.length).toBe(1);
	await page.getByRole('button', { name: 'Supprimer' }).click();
	await page.getByRole('dialog').getByRole('button', { name: 'Supprimer' }).click();
	await expect(page).toHaveURL(/\/plan\?tab=routes$/);
	await expect(page.locator('.lib', { hasText: '(inverse)' })).toHaveCount(0);
	// Sam's copy stays.
	expect((await get<{ items: unknown[] }>(request, `/profiles/${sam}/routes`)).items).toHaveLength(1);

	// A route without elevation says so, and is marked for the phone from the list.
	const res = await request.post(`/api/v1/profiles/${alex}/routes`, {
		headers: WRITE,
		multipart: { files: { name: 'plat.gpx', mimeType: 'application/gpx+xml', buffer: routeGpx('Tour plat', false) } }
	});
	const flat = (await res.json()).results[0].route_id;
	await page.goto(`/plan/routes/${flat}`);
	await expect(page.getByText('D+ indisponible')).toBeVisible();
	await expect(page.getByText("ne contient pas d'altitude")).toBeVisible();
});

test('a file without timing dropped on the activity import can become a route', async ({ page }) => {
	await as(page, alex, '/import');
	await page.locator('input[type="file"]').setInputFiles({ name: 'trace-sans-heure.gpx', mimeType: 'application/gpx+xml', buffer: untimedTrack() });
	const row = page.locator('.frow', { hasText: 'trace-sans-heure.gpx' });
	await expect(row).toContainText('sans horaires');
	await row.getByRole('button', { name: 'Ajouter aux parcours' }).click();
	await row.getByRole('link', { name: 'Voir le parcours' }).click();
	await expect(page.getByRole('heading', { name: 'trace-sans-heure', level: 1 })).toBeVisible();
});

test('activity page: session against targets, route, pacer, records and segments', async ({ page, request }) => {
	const list = await get<{ items: { id: number; title: string }[] }>(request, `/profiles/${alex}/activities?limit=100`);
	const run = list.items.find((a) => a.title === 'Sortie à deux')!;
	const routes = await get<{ items: { id: number; name: string }[] }>(request, `/profiles/${alex}/routes`);
	const route = routes.items.find((r) => r.name === 'Tour du canal')!;
	const step = (kind: string, end: object, target: object = { type: 'none' }) => ({ type: 'step', kind, end, target });
	await send(request, 'patch', `/activities/${run.id}`, {
		workout: {
			id: null,
			name: 'Deux fois 1600',
			steps: [
				step('warmup', { type: 'time', value: 600 }),
				step('effort', { type: 'distance', value: 1600 }, { type: 'pace', fast: 200, slow: 1100 }),
				step('recovery', { type: 'time', value: 120 }, { type: 'pace', fast: 120, slow: 130 }),
				step('effort', { type: 'distance', value: 1600 }),
				step('cooldown', { type: 'time', value: 600 })
			]
		},
		route: { id: route.id, name: route.name },
		pacer: { target: 'time', value: 1830, strategy: 'grade', final_gap: 31 }
	});
	await as(page, alex, `/activities/${run.id}`);

	// Three laps for five steps: the run was cut short.
	const session = page.locator('section.card', { has: page.getByRole('heading', { name: /Séance · Deux fois 1600/ }) });
	await expect(session.getByText("Telle qu'elle était le jour de la course")).toBeVisible();
	const rows = session.locator('tbody tr');
	await expect(rows).toHaveCount(5);
	await expect(rows.nth(1).locator('.target')).toHaveClass(/hit/);
	await expect(rows.nth(2).locator('.target')).toHaveClass(/miss/);
	await expect(rows.nth(3)).toContainText('Non fait : course arrêtée avant');
	await expect(rows.nth(4)).toContainText('Non fait');

	const plan = page.locator('section.card', { has: page.getByRole('heading', { name: 'Parcours et allure cible' }) });
	await expect(plan.getByRole('link', { name: /Tour du canal/ })).toHaveAttribute('href', `/plan/routes/${route.id}`);
	await expect(plan).toContainText("0:31d'avance");
	await expect(plan).toContainText('ajustée à la pente');
	// Leaving the run out of records says so, and can be undone.
	const records = page.locator('section.card', { has: page.getByRole('heading', { name: 'Records dans cette course' }) });
	await records.getByRole('button', { name: 'Exclure des records' }).click();
	await expect(records.getByText('exclue des records et des prédictions')).toBeVisible();
	await records.getByRole('button', { name: 'Exclure des records' }).click();
	await expect(records.getByText('exclue des records et des prédictions')).toHaveCount(0);

	// A route from a portion of the run.
	await page.getByRole('button', { name: 'Créer un parcours' }).click();
	const dialog = page.getByRole('dialog');
	await dialog.getByLabel('Nom').fill('Morceau choisi');
	await dialog.getByRole('button', { name: 'Seulement une portion de la course' }).click();
	await dialog.getByLabel(/Depuis/).fill('1');
	await dialog.getByLabel(/Jusqu'à/).fill('0,95');
	await expect(dialog.getByText("d'au moins 100 m")).toBeVisible();
	await dialog.getByLabel(/Jusqu'à/).fill('3');
	await dialog.getByRole('button', { name: 'Créer le parcours' }).click();
	await expect(page.getByRole('heading', { name: 'Morceau choisi', level: 1 })).toBeVisible();
	await expect(page.getByText(/^2,0\d km$/)).toBeVisible();

	// A run that follows no recurring portion has no segment section at all, not an empty table.
	const lone = Array.from({ length: 120 }, (_, i) => {
		const t = new Date(Date.UTC(2026, 5, 2, 7) + i * 5000).toISOString();
		return `<trkpt lat="45.2" lon="${(5.7 + i * 0.00017).toFixed(6)}"><ele>210</ele><time>${t}</time></trkpt>`;
	});
	const imported = await request.post(`/api/v1/profiles/${alex}/imports`, {
		headers: WRITE,
		multipart: {
			files: {
				name: 'ailleurs.gpx',
				mimeType: 'application/gpx+xml',
				buffer: Buffer.from(`<?xml version="1.0"?><gpx version="1.1" creator="e2e" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>${lone.join('')}</trkseg></trk></gpx>`)
			}
		}
	});
	const without = (await imported.json()).results[0].activity_id as number;
	await expect.poll(async () => (await get<{ busy: boolean }>(request, `/profiles/${alex}/derive-status`)).busy, { timeout: 30_000 }).toBe(false);
	await page.goto(`/activities/${without}`);
	await expect(page.getByRole('heading', { name: 'Records dans cette course' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Segments', level: 2 })).toHaveCount(0);

	// A run over Alex's segment lists its passage with its rank, linked to the segment.
	const { items } = await get<{ items: { id: number }[] }>(request, `/profiles/${alex}/segments`);
	const seg = await get<{ efforts: { activity_id: number }[] }>(request, `/segments/${items[0].id}`);
	await page.goto(`/activities/${seg.efforts[0].activity_id}`);
	const segs = page.locator('section.card', { has: page.getByRole('heading', { name: 'Segments', level: 2 }) });
	await expect(segs.getByRole('link', { name: /Le long du quai/ })).toHaveAttribute('href', `/segments/${items[0].id}`);
	await expect(segs).toContainText('Meilleur temps');
	await expect(segs).toContainText(/1ᵉʳ de vos \d+ passages/);
});

for (const scheme of ['light', 'dark'] as const) {
	for (const viewport of [
		{ width: 360, height: 780 },
		{ width: 1440, height: 900 }
	]) {
		test(`contrast and width of the new screens, ${scheme} at ${viewport.width} px`, async ({ page, request }) => {
			await page.emulateMedia({ colorScheme: scheme });
			await page.setViewportSize(viewport);
			const seg = (await get<{ items: { id: number }[] }>(request, `/profiles/${alex}/segments`)).items[0];
			const wk = (await get<{ items: { id: number }[] }>(request, `/profiles/${alex}/workouts`)).items[0];
			const rt = (await get<{ items: { id: number }[] }>(request, `/profiles/${alex}/routes`)).items[0];
			const run = (await get<{ items: { id: number; title: string }[] }>(request, `/profiles/${alex}/activities?limit=100`)).items.find((a) => a.title === 'Sortie à deux')!;
			for (const path of ['/segments', `/segments/${seg.id}`, '/plan', '/plan?tab=routes', `/plan/workouts/${wk.id}`, `/plan/routes/${rt.id}`, `/activities/${run.id}`]) {
				await as(page, alex, path);
				expect.soft(await lowContrast(page), path).toEqual([]);
				const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
				expect.soft(overflow, `${path} scrolls sideways`).toBeLessThanOrEqual(0);
			}
		});
	}
}
