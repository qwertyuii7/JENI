import path from 'node:path';
import { EventDatabase } from './db/database.js';
import { LaptopServer } from './server.js';

const dbPath = process.env.DB_PATH || path.resolve(process.cwd(), 'schedule_laptop.db');
const db = new EventDatabase({ path: dbPath });
const port = Number(process.env.PORT) || 3000;
const server = new LaptopServer(db, { port, deviceId: 'laptop-local' });

await server.start();
console.log(`\n======================================================`);
console.log(`  Schedule Assistant Laptop Service & Web Dashboard`);
console.log(`  Status: ACTIVE`);
console.log(`  Web Dashboard: http://127.0.0.1:${port}`);
console.log(`  Database: ${dbPath}`);
console.log(`  Binding: 127.0.0.1 (Strict local loopback)`);
console.log(`  Security: CSRF Protected, Host & Origin Validated`);
console.log(`  Press Ctrl+C to stop`);
console.log(`======================================================\n`);

process.on('SIGINT', async () => {
  console.log('\n[LAPTOP] Shutting down server gracefully...');
  await server.stop();
  process.exit(0);
});

process.on('SIGTERM', async () => {
  await server.stop();
  process.exit(0);
});
