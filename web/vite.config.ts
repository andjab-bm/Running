import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vitest/config';

export default defineConfig({
	plugins: [
		sveltekit({
			compilerOptions: {
				// Force runes mode for the project, except for libraries. Can be removed in svelte 6.
				runes: ({ filename }) =>
					filename.split(/[/\\]/).includes('node_modules') ? undefined : true
			},
			// Single-page app: every route is rendered in the browser and the Rust
			// server answers unknown paths with this fallback page.
			adapter: adapter({ fallback: '200.html' })
		})
	],
	// MapLibre starts its worker as an ES module.
	worker: { format: 'es' },
	server: {
		// During `npm run dev`, API calls go to the Rust server started with `cargo run`.
		// The string shorthand would set changeOrigin and rewrite Host to :8080, so the
		// server's same-site guard would see Origin :5173 ≠ Host :8080 and refuse writes.
		proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: false } }
	},
	test: {
		include: ['src/**/*.test.ts'],
		passWithNoTests: true
	}
});
