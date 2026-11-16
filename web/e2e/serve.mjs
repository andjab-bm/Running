// Starts the real server for the browser tests, on an empty data directory, with the map
// background disabled so the tests never depend on a tile host. Run `npm run build` first:
// the server serves the web app from `web/build`.
import { spawn } from 'node:child_process';
import { mkdirSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const data = fileURLToPath(new URL('../.e2e-data', import.meta.url));
rmSync(data, { recursive: true, force: true });
mkdirSync(data, { recursive: true });

const env = { ...process.env, PORT: process.env.E2E_PORT ?? '8099', DATA_DIR: data };
// E2E_BASEMAP=1 keeps the default map background, for the design screenshots.
if (!process.env.E2E_BASEMAP) env.MAP_STYLE_URL = '';
const server = spawn('cargo', ['run', '--manifest-path', '../server/Cargo.toml'], { stdio: 'inherit', env });
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.kill());
server.on('exit', (code) => process.exit(code ?? 0));
