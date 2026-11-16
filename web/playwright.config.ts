import { defineConfig, devices } from '@playwright/test';

const port = Number(process.env.E2E_PORT ?? 8099);

// Browser tests against the real server and the built web app (`npm run build` first).
// The tests share one server and build on each other's data, so they run in order, one at a time.
export default defineConfig({
	testDir: 'e2e',
	testMatch: '**/*.spec.ts',
	fullyParallel: false,
	workers: 1,
	retries: 0,
	timeout: 60_000,
	reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
	use: {
		baseURL: `http://127.0.0.1:${port}`,
		locale: 'fr-FR',
		timezoneId: 'Europe/Paris',
		trace: 'retain-on-failure'
	},
	projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
	webServer: {
		command: 'node e2e/serve.mjs',
		url: `http://127.0.0.1:${port}/api/v1/health`,
		// The first start compiles the server.
		timeout: 900_000,
		reuseExistingServer: false
	}
});
