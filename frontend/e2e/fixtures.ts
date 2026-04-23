import { spawn, type ChildProcess } from 'node:child_process';
import { access, mkdtemp, rm } from 'node:fs/promises';
import net from 'node:net';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { startTMDBStub, type TMDBStub } from './tmdb-stub';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..', '..');
const DEFAULT_BIN = path.join(REPO_ROOT, 'entlib');

const MIN_PASSWORD_LEN = 14; // mirrors backend/cmd/entlib/seed.go

export type TestUser = {
  username: string;
  displayName: string;
  password: string;
};

export type Stack = {
  baseUrl: string;
  tmdb: TMDBStub;
  users: { primary: TestUser; partner: TestUser };
  stop: () => Promise<void>;
};

function requirePasswordEnv(name: string): string {
  const v = process.env[name];
  if (!v) {
    throw new Error(
      `E2E: missing required env var ${name}. Set it to a >=${MIN_PASSWORD_LEN}-char password to seed the test user. Credentials must not be hardcoded.`,
    );
  }
  if (v.length < MIN_PASSWORD_LEN) {
    throw new Error(
      `E2E: ${name} is shorter than the backend's ${MIN_PASSWORD_LEN}-char minimum; refusing to seed.`,
    );
  }
  return v;
}

async function freePort(): Promise<number> {
  return new Promise<number>((resolve, reject) => {
    const srv = net.createServer();
    srv.once('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const port = (srv.address() as net.AddressInfo).port;
      srv.close((err) => (err ? reject(err) : resolve(port)));
    });
  });
}

async function ensureBinary(bin: string): Promise<void> {
  try {
    await access(bin);
  } catch {
    throw new Error(
      `E2E: entlib binary not found at ${bin}. Run 'make build' from the repo root first (or set ENTLIB_BIN to a freshly-built binary).`,
    );
  }
}

async function waitForHealth(baseUrl: string, deadlineMs = 15_000): Promise<void> {
  const started = Date.now();
  let lastErr: unknown = null;
  while (Date.now() - started < deadlineMs) {
    try {
      const res = await fetch(`${baseUrl}/api/health`);
      if (res.ok) return;
      lastErr = new Error(`status ${res.status}`);
    } catch (err) {
      lastErr = err;
    }
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error(
    `E2E: backend at ${baseUrl} did not become healthy within ${deadlineMs}ms (last error: ${String(lastErr)})`,
  );
}

async function runSeed(bin: string, env: NodeJS.ProcessEnv, user: TestUser): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    const child = spawn(
      bin,
      [
        'seed',
        `--display-name=${user.displayName}`,
        `--username=${user.username}`,
        `--password=${user.password}`,
      ],
      { env, stdio: ['ignore', 'pipe', 'pipe'] },
    );
    let stderr = '';
    child.stderr?.on('data', (b) => {
      stderr += b.toString();
    });
    child.on('error', reject);
    child.on('close', (code) =>
      code === 0
        ? resolve()
        : reject(new Error(`seed ${user.username} exited with code ${code}: ${stderr}`)),
    );
  });
}

export async function startStack(): Promise<Stack> {
  const bin = process.env.ENTLIB_BIN ?? DEFAULT_BIN;
  await ensureBinary(bin);

  const users: Stack['users'] = {
    primary: {
      username: process.env.ENTLIB_E2E_USER_PRIMARY ?? 'kira',
      displayName: 'Kira',
      password: requirePasswordEnv('ENTLIB_E2E_USER_PRIMARY_PASSWORD'),
    },
    partner: {
      username: process.env.ENTLIB_E2E_USER_PARTNER ?? 'partner',
      displayName: 'Eileen',
      password: requirePasswordEnv('ENTLIB_E2E_USER_PARTNER_PASSWORD'),
    },
  };

  const tmpDir = await mkdtemp(path.join(tmpdir(), 'entlib-e2e-'));
  const dbPath = path.join(tmpDir, 'entlib.sqlite');
  const port = await freePort();
  const tmdb = await startTMDBStub();

  const env: NodeJS.ProcessEnv = {
    ...process.env,
    ENTLIB_DB_DSN: dbPath,
    ENTLIB_HTTP_ADDR: `127.0.0.1:${port}`,
    ENTLIB_COOKIE_SECURE: 'false',
    ENTLIB_TMDB_BASE_URL: tmdb.url,
    TMDB_API_KEY: 'e2e-stub-key',
  };

  // Seed before serve so we use entlib's own migration + hashing path rather
  // than poking sqlite directly. Two sequential calls — the seed command is
  // upsert-safe but we keep it serial for clarity.
  await runSeed(bin, env, users.primary);
  await runSeed(bin, env, users.partner);

  const child: ChildProcess = spawn(bin, ['serve'], {
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  // Drain output so the process doesn't block on a full pipe buffer. Capture
  // tails for the failure message.
  const stdoutTail: string[] = [];
  const stderrTail: string[] = [];
  const TAIL_LIMIT = 50;
  const pushTail = (sink: string[], chunk: Buffer) => {
    chunk
      .toString()
      .split('\n')
      .filter(Boolean)
      .forEach((line) => {
        sink.push(line);
        if (sink.length > TAIL_LIMIT) sink.shift();
      });
  };
  child.stdout?.on('data', (b: Buffer) => pushTail(stdoutTail, b));
  child.stderr?.on('data', (b: Buffer) => pushTail(stderrTail, b));

  const baseUrl = `http://127.0.0.1:${port}`;

  const cleanup = async (signal: NodeJS.Signals = 'SIGTERM') => {
    if (!child.killed && child.exitCode === null) {
      child.kill(signal);
      await new Promise<void>((resolve) => {
        if (child.exitCode !== null) return resolve();
        child.once('close', () => resolve());
      });
    }
    await tmdb.close().catch(() => {});
    await rm(tmpDir, { recursive: true, force: true }).catch(() => {});
  };

  try {
    await waitForHealth(baseUrl);
  } catch (err) {
    await cleanup('SIGKILL');
    const ctx = [
      `--- entlib stdout (last ${TAIL_LIMIT} lines) ---`,
      ...stdoutTail,
      `--- entlib stderr (last ${TAIL_LIMIT} lines) ---`,
      ...stderrTail,
    ].join('\n');
    throw new Error(`${(err as Error).message}\n${ctx}`);
  }

  return {
    baseUrl,
    tmdb,
    users,
    stop: () => cleanup(),
  };
}
