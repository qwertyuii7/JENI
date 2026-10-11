import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import http from 'node:http';
import { EventDatabase } from '../src/db/database.js';
import { LaptopServer } from '../src/server.js';

describe('Laptop Server Hardening & Security Suite', () => {
  let db: EventDatabase;
  let server: LaptopServer;
  const testPort = 3847;

  beforeAll(async () => {
    db = new EventDatabase({ path: ':memory:' });
    server = new LaptopServer(db, { port: testPort, deviceId: 'test-laptop' });
    await server.start();
  });

  afterAll(async () => {
    await server.stop();
    db.close();
  });

  const rawRequest = (options: http.RequestOptions, body?: string): Promise<{ statusCode: number; body: string }> => {
    return new Promise((resolve, reject) => {
      const req = http.request(
        {
          hostname: '127.0.0.1',
          port: testPort,
          ...options
        },
        (res) => {
          let data = '';
          res.on('data', (c) => {
            data += c;
          });
          res.on('end', () => {
            resolve({ statusCode: res.statusCode || 0, body: data });
          });
        }
      );
      req.on('error', reject);
      if (body) req.write(body);
      req.end();
    });
  };

  it('binds strictly to 127.0.0.1 and rejects invalid Host headers', async () => {
    // 1. Valid Host header -> 200 OK
    const validRes = await rawRequest({
      path: '/api/events',
      method: 'GET',
      headers: { Host: `127.0.0.1:${testPort}` }
    });
    expect(validRes.statusCode).toBe(200);

    // 2. Malicious Host header (evil.com) -> 403 Forbidden
    const evilHostRes = await rawRequest({
      path: '/api/events',
      method: 'GET',
      headers: { Host: 'evil.com' }
    });
    expect(evilHostRes.statusCode).toBe(403);
    const evilData = JSON.parse(evilHostRes.body);
    expect(evilData.error).toContain('Invalid Host header');
  });

  it('rejects state-mutating POST requests without CSRF token or with wrong CSRF token', async () => {
    const postBody = JSON.stringify({ text: 'kal 4 baje DBMS class hai' });

    // 1. Missing CSRF token -> 403 Forbidden
    const noCsrfRes = await rawRequest(
      {
        path: '/api/messages',
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Host: `127.0.0.1:${testPort}`
        }
      },
      postBody
    );
    expect(noCsrfRes.statusCode).toBe(403);
    const noCsrfData = JSON.parse(noCsrfRes.body);
    expect(noCsrfData.error).toContain('CSRF token');

    // 2. Wrong CSRF token -> 403 Forbidden
    const badCsrfRes = await rawRequest(
      {
        path: '/api/messages',
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-csrf-token': 'attacker-crafted-token',
          Host: `127.0.0.1:${testPort}`
        }
      },
      postBody
    );
    expect(badCsrfRes.statusCode).toBe(403);

    // 3. Valid CSRF token -> 200 OK
    const validToken = server.getCsrfToken();
    const goodRes = await rawRequest(
      {
        path: '/api/messages',
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-csrf-token': validToken,
          Host: `127.0.0.1:${testPort}`
        }
      },
      postBody
    );
    expect(goodRes.statusCode).toBe(200);
    const goodData = JSON.parse(goodRes.body);
    expect(goodData.status).toBe('PENDING');
  });

  it('rejects cross-origin POST requests from malicious external origins', async () => {
    const validToken = server.getCsrfToken();
    const postBody = JSON.stringify({ text: 'kal 4 baje DBMS class hai' });

    // Malicious Origin header
    const evilOriginRes = await rawRequest(
      {
        path: '/api/messages',
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-csrf-token': validToken,
          Host: `127.0.0.1:${testPort}`,
          Origin: 'http://malicious-website.com'
        }
      },
      postBody
    );
    expect(evilOriginRes.statusCode).toBe(403);
    const data = JSON.parse(evilOriginRes.body);
    expect(data.error).toContain('Cross-origin');

    // Permitted local Origin
    const localOriginRes = await rawRequest(
      {
        path: '/api/messages',
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-csrf-token': validToken,
          Host: `127.0.0.1:${testPort}`,
          Origin: `http://127.0.0.1:${testPort}`
        }
      },
      postBody
    );
    expect(localOriginRes.statusCode).toBe(200);
  });

  it('supports notification health check and reports health status', async () => {
    // 1. Health status endpoint
    const healthRes = await rawRequest({
      path: '/api/notifications/health',
      method: 'GET',
      headers: { Host: `127.0.0.1:${testPort}` }
    });
    expect(healthRes.statusCode).toBe(200);
    const health = JSON.parse(healthRes.body);
    expect(health.status).toBe('HEALTHY');

    // 2. Test notification trigger with CSRF token
    const testRes = await rawRequest({
      path: '/api/notifications/test',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-csrf-token': server.getCsrfToken(),
        Host: `127.0.0.1:${testPort}`
      }
    });
    expect(testRes.statusCode).toBe(200);
    const testData = JSON.parse(testRes.body);
    expect(testData.success).toBe(true);
  });
});
