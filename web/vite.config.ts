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
		proxy: { '/api': 'http://localhost:8080' }
	},
	test: {
		include: ['src/**/*.test.ts'],
		passWithNoTests: true
	}
});
