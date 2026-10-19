#!/usr/bin/env node
/**
 * Pasta — a private clipboard for all your screens.
 *
 * Deliberately dependency-free: Node's built-in HTTP server, SQLite, crypto
 * and file system are enough for a small, single-user clipboard relay.
 */

import http from 'node:http';
import path from 'node:path';
import crypto from 'node:crypto';
import os from 'node:os';
import { promises as fs, createReadStream, existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DatabaseSync } from 'node:sqlite';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const PUBLIC_DIR = path.join(ROOT, 'public');

// Load configuration before any derived constants are computed.
loadEnv(path.join(ROOT, 'pasta.env'));
loadEnv(path.join(ROOT, '.env'));

const DATA_DIR = path.resolve(process.env.PASTA_DATA_DIR || path.join(ROOT, 'data'));
const UPLOAD_DIR = path.join(DATA_DIR, 'uploads');
const DB_FILE = path.join(DATA_DIR, 'pasta.db');
const VERSION = '1.0.0';
const HOST = '127.0.0.1';
const PORT = Number(process.env.PASTA_PORT || 4317);
const PUBLIC_URL = (process.env.PASTA_PUBLIC_URL || '').replace(/\/+$/, '');
const MAX_JSON = 600 * 1024;
const MAX_TEXT = 100_000;
const MAX_IMAGE = 20 * 1024 * 1024;
const SESSION_TTL = 30 * 24 * 60 * 60 * 1000;
const COOKIE_NAME = 'pasta_session';

// ---------------------------------------------------------------------------
// Tiny .env loader (keeps startup a single command, with no dotenv package).
// ---------------------------------------------------------------------------

function loadEnv(file) {
  if (!existsSync(file)) return;
  for (const raw of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq < 1) continue;
    const key = line.slice(0, eq).trim();
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key) || key in process.env) continue;
    let value = line.slice(eq + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    process.env[key] = value;
  }
}

loadEnv(path.join(ROOT, 'pasta.env'));
loadEnv(path.join(ROOT, '.env'));

await fs.mkdir(DATA_DIR, { recursive: true });
await fs.mkdir(UPLOAD_DIR, { recursive: true });

// ---------------------------------------------------------------------------
// Passphrase. Generated once if it was not supplied in pasta.env.
// ---------------------------------------------------------------------------

function makePassphrase() {
  const words = [
    'amber', 'apron', 'basil', 'birch', 'cobalt', 'copper', 'coral', 'cedar',
    'daisy', 'denim', 'ember', 'fable', 'fern', 'fig', 'garnet', 'harbor',
    'indigo', 'ivory', 'juniper', 'kettle', 'lantern', 'lemon', 'linen', 'maple',
    'marble', 'meadow', 'mango', 'nutmeg', 'olive', 'onyx', 'opal', 'pepper',
    'pebble', 'plum', 'poppy', 'quartz', 'raven', 'rosemary', 'saffron', 'sage',
    'salt', 'silk', 'spruce', 'squash', 'thyme', 'tulip', 'velvet', 'violet',
    'walnut', 'willow'
  ];
  const pick = () => words[crypto.randomInt(words.length)];
  return `${pick()}-${pick()}-${pick()}-${pick()}-${pick()}-${crypto.randomInt(10, 100)}`;
}

async function resolvePassword() {
  if (process.env.PASTA_PASSWORD) return process.env.PASTA_PASSWORD;
  const generatedFile = path.join(DATA_DIR, 'initial-password.txt');
  try {
    const existing = (await fs.readFile(generatedFile, 'utf8')).trim();
    if (existing) return existing;
  } catch {}
  const password = makePassphrase();
  await fs.writeFile(generatedFile, `${password}\n`, { mode: 0o600 });
  console.warn(`[pasta] No PASTA_PASSWORD set. Generated one and saved it to ${generatedFile}`);
  return password;
}

const PASSWORD = await resolvePassword();
if (PASSWORD.length < 12) {
  console.warn('[pasta] Warning: the passphrase is shorter than 12 characters.');
}
const PASSWORD_SALT = crypto.randomBytes(16);
const PASSWORD_HASH = crypto.scryptSync(PASSWORD, PASSWORD_SALT, 64, { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 });

function verifyPassword(candidate) {
  if (typeof candidate !== 'string' || candidate.length > 512) return false;
  const attempt = crypto.scryptSync(candidate, PASSWORD_SALT, 64, { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 });
  return crypto.timingSafeEqual(attempt, PASSWORD_HASH);
}

// ---------------------------------------------------------------------------
// Storage
// ---------------------------------------------------------------------------

const db = new DatabaseSync(DB_FILE);
db.exec(`
  PRAGMA journal_mode = WAL;
  PRAGMA synchronous = NORMAL;
  PRAGMA busy_timeout = 5000;

  CREATE TABLE IF NOT EXISTS clips (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('text', 'image')),
    text TEXT,
    file_name TEXT,
    mime TEXT,
    size INTEGER NOT NULL DEFAULT 0,
    width INTEGER,
    height INTEGER,
    pinned INTEGER NOT NULL DEFAULT 0,
    device TEXT,
    created_at INTEGER NOT NULL
  );
  CREATE INDEX IF NOT EXISTS clips_created_idx ON clips (created_at DESC);

  CREATE TABLE IF NOT EXISTS sessions (
    id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    label TEXT NOT NULL,
    kind TEXT NOT NULL DEFAULT 'web',
    created_at INTEGER NOT NULL,
    last_used INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
  );
  CREATE INDEX IF NOT EXISTS sessions_expiry_idx ON sessions (expires_at);
`);

const q = {
  insertText: db.prepare(`
    INSERT INTO clips (id, kind, text, pinned, device, created_at)
    VALUES (?, 'text', ?, 0, ?, ?)
  `),
  insertImage: db.prepare(`
    INSERT INTO clips (id, kind, file_name, mime, size, width, height, pinned, device, created_at)
    VALUES (?, 'image', ?, ?, ?, ?, ?, 0, ?, ?)
  `),
  byId: db.prepare('SELECT * FROM clips WHERE id = ?'),
  delete: db.prepare('DELETE FROM clips WHERE id = ?'),
  pin: db.prepare('UPDATE clips SET pinned = ? WHERE id = ?'),
  list: db.prepare(`
    SELECT * FROM clips
    WHERE created_at < ?
      AND (? = '' OR kind = ?)
      AND (? = '' OR instr(lower(coalesce(text, '')), ?) > 0 OR instr(lower(coalesce(file_name, '')), ?) > 0)
    ORDER BY created_at DESC
    LIMIT ?
  `),
  stats: db.prepare(`
    SELECT count(*) AS clips,
           coalesce(sum(size), 0) AS bytes,
           sum(CASE WHEN kind = 'text' THEN 1 ELSE 0 END) AS texts,
           sum(CASE WHEN kind = 'image' THEN 1 ELSE 0 END) AS images
    FROM clips
  `),
  insertSession: db.prepare(`
    INSERT INTO sessions (id, token_hash, label, kind, created_at, last_used, expires_at)
    VALUES (?, ?, ?, ?, ?, ?, ?)
  `),
  sessionByToken: db.prepare('SELECT * FROM sessions WHERE token_hash = ? AND expires_at > ?'),
  touchSession: db.prepare('UPDATE sessions SET last_used = ? WHERE id = ?'),
  deleteSession: db.prepare('DELETE FROM sessions WHERE id = ?'),
  deleteSessionByToken: db.prepare('DELETE FROM sessions WHERE token_hash = ?'),
  listSessions: db.prepare('SELECT id, label, kind, created_at, last_used, expires_at FROM sessions WHERE expires_at > ? ORDER BY last_used DESC'),
  purgeSessions: db.prepare('DELETE FROM sessions WHERE expires_at <= ?')
};

function newId() {
  return crypto.randomBytes(12).toString('base64url');
}

function tokenHash(token) {
  return crypto.createHash('sha256').update(token).digest('hex');
}

function clipFromRow(row) {
  return {
    id: row.id,
    kind: row.kind,
    text: row.kind === 'text' ? row.text : null,
    image: row.kind === 'image' ? {
      url: `/api/clips/${row.id}/content`,
      downloadUrl: `/api/clips/${row.id}/content?download=1`,
      name: row.file_name,
      mime: row.mime,
      size: row.size,
      width: row.width,
      height: row.height
    } : null,
    pinned: Boolean(row.pinned),
    device: row.device || 'Unknown device',
    createdAt: row.created_at
  };
}

// ---------------------------------------------------------------------------
// Little helpers
// ---------------------------------------------------------------------------

function clientIp(req) {
  const cf = req.headers['cf-connecting-ip'];
  if (cf && !Array.isArray(cf)) return cf.trim();
  const forwarded = req.headers['x-forwarded-for'];
  if (forwarded && !Array.isArray(forwarded)) return forwarded.split(',')[0].trim();
  return req.socket.remoteAddress || 'unknown';
}

function isSecure(req) {
  if (req.socket.encrypted) return true;
  const proto = req.headers['x-forwarded-proto'];
  return typeof proto === 'string' && proto.split(',')[0].trim() === 'https';
}

function parseCookies(header) {
  const out = {};
  if (!header) return out;
  for (const part of header.split(';')) {
    const eq = part.indexOf('=');
    if (eq < 0) continue;
    const key = part.slice(0, eq).trim();
    if (!key) continue;
    try { out[key] = decodeURIComponent(part.slice(eq + 1).trim()); } catch {}
  }
  return out;
}

function getToken(req) {
  const auth = req.headers.authorization;
  if (typeof auth === 'string' && auth.toLowerCase().startsWith('bearer ')) {
    return auth.slice(7).trim();
  }
  return parseCookies(req.headers.cookie)[COOKIE_NAME] || null;
}

function authenticate(req) {
  const token = getToken(req);
  if (!token || token.length > 200) return null;
  const session = q.sessionByToken.get(tokenHash(token), Date.now());
  if (!session) return null;
  const now = Date.now();
  if (now - session.last_used > 60_000) q.touchSession.run(now, session.id);
  return { ...session, token };
}

function sessionCookie(token, req) {
  const bits = [
    `${COOKIE_NAME}=${encodeURIComponent(token)}`,
    'Path=/',
    'HttpOnly',
    'SameSite=Lax',
    `Max-Age=${Math.floor(SESSION_TTL / 1000)}`
  ];
  if (isSecure(req)) bits.push('Secure');
  return bits.join('; ');
}

function clearSessionCookie(req) {
  const bits = [`${COOKIE_NAME}=`, 'Path=/', 'HttpOnly', 'SameSite=Lax', 'Max-Age=0'];
  if (isSecure(req)) bits.push('Secure');
  return bits.join('; ');
}

function securityHeaders(req) {
  return {
    'Content-Security-Policy': [
      "default-src 'self'",
      "script-src 'self'",
      "style-src 'self' 'unsafe-inline'",
      "img-src 'self' blob: data:",
      "font-src 'self'",
      "connect-src 'self'",
      "media-src 'self' blob:",
      "object-src 'none'",
      "base-uri 'none'",
      "form-action 'self'",
      "frame-ancestors 'none'"
    ].join('; '),
    'Cross-Origin-Opener-Policy': 'same-origin',
    'Cross-Origin-Resource-Policy': 'same-origin',
    'Referrer-Policy': 'no-referrer',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'X-Robots-Tag': 'noindex, nofollow, noarchive',
    'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), clipboard-read=(self), clipboard-write=(self)',
    ...(isSecure(req) ? { 'Strict-Transport-Security': 'max-age=31536000; includeSubDomains' } : {})
  };
}

function sendJson(req, res, status, body, extra = {}) {
  if (res.writableEnded) return;
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    ...securityHeaders(req),
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
    'Cache-Control': 'no-store',
    ...extra
  });
  res.end(payload);
}

function fail(req, res, status, message, extra = {}) {
  sendJson(req, res, status, { error: message }, extra);
}

function readBody(req, max) {
  return new Promise((resolve, reject) => {
    const declared = Number(req.headers['content-length'] || 0);
    if (declared > max) {
      const err = new Error('That is a little too large.');
      err.status = 413;
      reject(err);
      req.resume();
      return;
    }
    const chunks = [];
    let total = 0;
    let settled = false;
    req.on('data', (chunk) => {
      if (settled) return;
      total += chunk.length;
      if (total > max) {
        settled = true;
        const err = new Error('That is a little too large.');
        err.status = 413;
        reject(err);
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (settled) return;
      settled = true;
      resolve(Buffer.concat(chunks));
    });
    req.on('error', (err) => {
      if (settled) return;
      settled = true;
      reject(err);
    });
  });
}

async function readJson(req, max = MAX_JSON) {
  const body = await readBody(req, max);
  if (!body.length) return {};
  try {
    return JSON.parse(body.toString('utf8'));
  } catch {
    const err = new Error('Invalid JSON.');
    err.status = 400;
    throw err;
  }
}

function sameOrigin(req) {
  const origin = req.headers.origin;
  if (!origin) return true;
  const host = String(req.headers['x-forwarded-host'] || req.headers.host || '').split(',')[0].trim();
  try {
    return new URL(origin).host === host;
  } catch {
    return false;
  }
}

function cleanLabel(value, fallback) {
  if (typeof value !== 'string') return fallback;
  const cleaned = value.replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, 48);
  return cleaned || fallback;
}

function headerValue(req, name) {
  const value = req.headers[name];
  return typeof value === 'string' ? value : '';
}

function decodeHeader(value, fallback = '') {
  if (!value) return fallback;
  try { return decodeURIComponent(value); } catch { return fallback; }
}

function safeFilename(name, fallback) {
  const base = path.basename(String(name || '').replace(/\\/g, '/')).replace(/[\u0000-\u001f\u007f<>:"/\\|?*]/g, '').trim();
  return (base || fallback).slice(0, 120);
}

// ---------------------------------------------------------------------------
// Image sniffing. We never trust a content type for files served from origin.
// ---------------------------------------------------------------------------

function sniffImage(buf) {
  if (buf.length >= 8 && buf.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) {
    return { mime: 'image/png', ext: 'png' };
  }
  if (buf.length >= 3 && buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) {
    return { mime: 'image/jpeg', ext: 'jpg' };
  }
  if (buf.length >= 6) {
    const head = buf.subarray(0, 6).toString('ascii');
    if (head === 'GIF87a' || head === 'GIF89a') return { mime: 'image/gif', ext: 'gif' };
  }
  if (buf.length >= 12 && buf.subarray(0, 4).toString('ascii') === 'RIFF' && buf.subarray(8, 12).toString('ascii') === 'WEBP') {
    return { mime: 'image/webp', ext: 'webp' };
  }
  return null;
}

// ---------------------------------------------------------------------------
// Live events (SSE)
// ---------------------------------------------------------------------------

const eventClients = new Set();

function broadcast(event) {
  const payload = `event: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`;
  for (const res of eventClients) {
    try { res.write(payload); } catch { eventClients.delete(res); }
  }
}

setInterval(() => {
  for (const res of eventClients) {
    try { res.write(`: ping ${Date.now()}\n\n`); } catch { eventClients.delete(res); }
  }
}, 25_000).unref();

setInterval(() => {
  try { q.purgeSessions.run(Date.now()); } catch (err) { console.error('[pasta] session purge failed', err.message); }
}, 60 * 60 * 1000).unref();

// ---------------------------------------------------------------------------
// Rate limiting for login
// ---------------------------------------------------------------------------

const loginAttempts = new Map();

function loginRate(ip) {
  const now = Date.now();
  const windowMs = 15 * 60 * 1000;
  let entry = loginAttempts.get(ip);
  if (!entry || entry.resetAt <= now) {
    entry = { count: 0, resetAt: now + windowMs };
    loginAttempts.set(ip, entry);
  }
  return {
    blocked: entry.count >= 8,
    retryAfter: Math.max(1, Math.ceil((entry.resetAt - now) / 1000)),
    hit() { entry.count += 1; },
    reset() { loginAttempts.delete(ip); }
  };
}

setInterval(() => {
  const now = Date.now();
  for (const [ip, entry] of loginAttempts) {
    if (entry.resetAt <= now) loginAttempts.delete(ip);
  }
}, 10 * 60 * 1000).unref();

// ---------------------------------------------------------------------------
// Static files
// ---------------------------------------------------------------------------

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.txt': 'text/plain; charset=utf-8',
  '.apk': 'application/vnd.android.package-archive'
};

async function serveStatic(req, res, pathname) {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    fail(req, res, 405, 'Method not allowed.');
    return;
  }

  if (pathname === '/robots.txt') {
    res.writeHead(200, { ...securityHeaders(req), 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'public, max-age=3600' });
    res.end('User-agent: *\nDisallow: /\n');
    return;
  }

  let rel;
  try {
    rel = pathname === '/' ? '/index.html' : decodeURIComponent(pathname);
  } catch {
    fail(req, res, 400, 'Bad path.');
    return;
  }
  if (rel.includes('\0') || rel.split('/').some((part) => part === '..' || part.startsWith('.'))) {
    fail(req, res, 403, 'Forbidden.');
    return;
  }

  const full = path.resolve(PUBLIC_DIR, `.${rel}`);
  if (full !== PUBLIC_DIR && !full.startsWith(PUBLIC_DIR + path.sep)) {
    fail(req, res, 403, 'Forbidden.');
    return;
  }

  let stat;
  try {
    stat = await fs.stat(full);
  } catch {
    fail(req, res, 404, 'Not found.');
    return;
  }
  if (!stat.isFile()) {
    fail(req, res, 404, 'Not found.');
    return;
  }

  const ext = path.extname(full).toLowerCase();
  const isHtml = ext === '.html';
  const isServiceWorker = path.basename(full) === 'sw.js';
  const revalidate = isHtml || isServiceWorker || ext === '.css' || ext === '.js' || ext === '.webmanifest';
  const etag = `W/"${stat.size.toString(16)}-${Math.floor(stat.mtimeMs).toString(16)}"`;
  const headers = {
    ...securityHeaders(req),
    'Content-Type': MIME[ext] || 'application/octet-stream',
    'Content-Length': stat.size,
    'Cache-Control': revalidate ? 'no-cache' : 'public, max-age=604800',
    'ETag': etag,
    'Last-Modified': stat.mtime.toUTCString()
  };
  if (ext === '.apk') {
    headers['Content-Disposition'] = 'attachment; filename="Pasta.apk"';
    headers['Cache-Control'] = 'public, max-age=300';
  }
  if (req.headers['if-none-match'] === etag) {
    res.writeHead(304, headers);
    res.end();
    return;
  }
  res.writeHead(200, headers);
  if (req.method === 'HEAD') {
    res.end();
    return;
  }
  createReadStream(full).pipe(res).on('error', () => res.destroy());
}

// ---------------------------------------------------------------------------
// API
// ---------------------------------------------------------------------------

async function handleApi(req, res, url) {
  const { pathname, searchParams } = url;
  const method = req.method;

  // Public -----------------------------------------------------------------
  if (pathname === '/api/health' && method === 'GET') {
    sendJson(req, res, 200, { ok: true, service: 'pasta', version: VERSION, time: Date.now() });
    return;
  }

  if (pathname === '/api/auth/login' && method === 'POST') {
    const ip = clientIp(req);
    const rate = loginRate(ip);
    if (rate.blocked) {
      fail(req, res, 429, 'Too many attempts. Try again in a few minutes.', { 'Retry-After': String(rate.retryAfter) });
      return;
    }
    const body = await readJson(req, 8 * 1024);
    if (!verifyPassword(body.password)) {
      rate.hit();
      await new Promise((resolve) => setTimeout(resolve, 350));
      fail(req, res, 401, 'That passphrase is not right.');
      return;
    }
    rate.reset();
    const label = cleanLabel(body.label, 'Browser');
    const kind = req.headers['x-pasta-client'] === 'android' ? 'android' : 'web';
    const token = crypto.randomBytes(32).toString('base64url');
    const sid = newId();
    const now = Date.now();
    q.insertSession.run(sid, tokenHash(token), label, kind, now, now, now + SESSION_TTL);
    sendJson(req, res, 200, {
      ok: true,
      token,
      session: { id: sid, label, kind, createdAt: now, expiresAt: now + SESSION_TTL },
      publicUrl: PUBLIC_URL || null
    }, { 'Set-Cookie': sessionCookie(token, req) });
    return;
  }

  // Everything below is private --------------------------------------------
  const auth = authenticate(req);

  // This one is intentionally friendly when signed out, so the login screen
  // can ask "am I already unlocked?" without a console 401.
  if (pathname === '/api/auth/me' && method === 'GET') {
    if (!auth) {
      sendJson(req, res, 200, { authenticated: false, publicUrl: PUBLIC_URL || null, version: VERSION });
      return;
    }
    sendJson(req, res, 200, {
      authenticated: true,
      publicUrl: PUBLIC_URL || null,
      version: VERSION,
      session: { id: auth.id, label: auth.label, kind: auth.kind, createdAt: auth.created_at, expiresAt: auth.expires_at }
    });
    return;
  }

  if (!auth) {
    if (pathname === '/api/events' && method === 'GET') {
      res.writeHead(401, { ...securityHeaders(req), 'Content-Type': 'text/plain' });
      res.end('Unauthorized');
      return;
    }
    fail(req, res, 401, 'Please sign in.');
    return;
  }

  if (method !== 'GET' && method !== 'HEAD' && !sameOrigin(req)) {
    fail(req, res, 403, 'Cross-origin request refused.');
    return;
  }

  if (pathname === '/api/auth/logout' && method === 'POST') {
    q.deleteSession.run(auth.id);
    sendJson(req, res, 200, { ok: true }, { 'Set-Cookie': clearSessionCookie(req) });
    return;
  }

  if (pathname === '/api/sessions' && method === 'GET') {
    const sessions = q.listSessions.all(Date.now()).map((s) => ({
      id: s.id,
      label: s.label,
      kind: s.kind,
      createdAt: s.created_at,
      lastUsed: s.last_used,
      expiresAt: s.expires_at,
      current: s.id === auth.id
    }));
    sendJson(req, res, 200, { sessions });
    return;
  }

  const sessionMatch = pathname.match(/^\/api\/sessions\/([A-Za-z0-9_-]{4,64})$/);
  if (sessionMatch && method === 'DELETE') {
    q.deleteSession.run(sessionMatch[1]);
    const extra = sessionMatch[1] === auth.id ? { 'Set-Cookie': clearSessionCookie(req) } : {};
    sendJson(req, res, 200, { ok: true }, extra);
    return;
  }

  if (pathname === '/api/stats' && method === 'GET') {
    const stats = q.stats.get();
    sendJson(req, res, 200, {
      clips: stats.clips || 0,
      texts: stats.texts || 0,
      images: stats.images || 0,
      bytes: stats.bytes || 0
    });
    return;
  }

  // Live stream ------------------------------------------------------------
  if (pathname === '/api/events' && method === 'GET') {
    res.writeHead(200, {
      ...securityHeaders(req),
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache, no-transform',
      'Connection': 'keep-alive',
      'X-Accel-Buffering': 'no'
    });
    res.write(`retry: 2500\n\n: connected ${new Date().toISOString()}\n\n`);
    eventClients.add(res);
    req.on('close', () => eventClients.delete(res));
    return;
  }

  // Clip collection --------------------------------------------------------
  if (pathname === '/api/clips' && method === 'GET') {
    const limit = Math.max(1, Math.min(100, Number(searchParams.get('limit')) || 60));
    const before = Number(searchParams.get('before')) || Number.MAX_SAFE_INTEGER;
    const kind = searchParams.get('kind') || '';
    const search = (searchParams.get('q') || '').trim().slice(0, 200);
    const normalizedKind = kind === 'text' || kind === 'image' ? kind : '';
    const rows = q.list.all(before, normalizedKind, normalizedKind, search, search.toLowerCase(), search.toLowerCase(), limit + 1);
    const hasMore = rows.length > limit;
    const clips = rows.slice(0, limit).map(clipFromRow);
    sendJson(req, res, 200, { clips, hasMore, nextBefore: hasMore ? clips[clips.length - 1].createdAt : null });
    return;
  }

  if (pathname === '/api/clips' && method === 'POST') {
    const body = await readJson(req);
    const text = typeof body.text === 'string' ? body.text : '';
    if (!text.trim()) {
      fail(req, res, 400, 'There is nothing to paste.');
      return;
    }
    if (text.length > MAX_TEXT) {
      fail(req, res, 413, `Text is limited to ${MAX_TEXT.toLocaleString()} characters.`);
      return;
    }
    const device = cleanLabel(body.device, auth.label);
    const id = newId();
    const now = Date.now();
    q.insertText.run(id, text, device, now);
    const clip = clipFromRow(q.byId.get(id));
    broadcast({ type: 'clip-created', clip });
    sendJson(req, res, 201, { clip });
    return;
  }

  // Image upload -----------------------------------------------------------
  if (pathname === '/api/clips/image' && method === 'POST') {
    const declared = Number(req.headers['content-length'] || 0);
    if (declared > MAX_IMAGE) {
      fail(req, res, 413, 'Images are limited to 20 MB.');
      return;
    }
    const body = await readBody(req, MAX_IMAGE);
    if (!body.length) {
      fail(req, res, 400, 'The image was empty.');
      return;
    }
    const detected = sniffImage(body);
    if (!detected) {
      fail(req, res, 415, 'Only PNG, JPEG, GIF and WebP images are supported.');
      return;
    }
    const id = newId();
    const storedName = `${id}.${detected.ext}`;
    const filePath = path.join(UPLOAD_DIR, storedName);
    await fs.writeFile(filePath, body, { flag: 'wx' });
    const device = cleanLabel(decodeHeader(headerValue(req, 'x-pasta-device')), auth.label);
    const originalName = safeFilename(decodeHeader(headerValue(req, 'x-pasta-filename')), `image.${detected.ext}`);
    const width = Math.max(0, Math.min(30_000, Number(headerValue(req, 'x-pasta-width')) || 0)) || null;
    const height = Math.max(0, Math.min(30_000, Number(headerValue(req, 'x-pasta-height')) || 0)) || null;
    const now = Date.now();
    try {
      q.insertImage.run(id, originalName, detected.mime, body.length, width, height, device, now);
    } catch (err) {
      await fs.unlink(filePath).catch(() => {});
      throw err;
    }
    const clip = clipFromRow(q.byId.get(id));
    broadcast({ type: 'clip-created', clip });
    sendJson(req, res, 201, { clip });
    return;
  }

  // Single clip ------------------------------------------------------------
  const contentMatch = pathname.match(/^\/api\/clips\/([A-Za-z0-9_-]{4,64})\/content$/);
  if (contentMatch && method === 'GET') {
    const row = q.byId.get(contentMatch[1]);
    if (!row || row.kind !== 'image' || !row.file_name) {
      fail(req, res, 404, 'Image not found.');
      return;
    }
    const stored = await fs.readdir(UPLOAD_DIR).then((files) => files.find((f) => f.startsWith(`${row.id}.`))).catch(() => null);
    if (!stored) {
      fail(req, res, 404, 'Image file is missing.');
      return;
    }
    const filePath = path.join(UPLOAD_DIR, stored);
    let stat;
    try { stat = await fs.stat(filePath); } catch { fail(req, res, 404, 'Image file is missing.'); return; }
    const download = searchParams.get('download') === '1';
    const asciiName = row.file_name.replace(/[^\x20-\x7e]/g, '_').replace(/["\\]/g, '_');
    const headers = {
      ...securityHeaders(req),
      'Content-Type': row.mime,
      'Content-Length': stat.size,
      'Cache-Control': 'private, max-age=31536000, immutable',
      'ETag': `"${row.id}"`,
      'Content-Disposition': `${download ? 'attachment' : 'inline'}; filename="${asciiName}"; filename*=UTF-8''${encodeURIComponent(row.file_name)}`
    };
    if (req.headers['if-none-match'] === `"${row.id}"`) {
      res.writeHead(304, headers);
      res.end();
      return;
    }
    res.writeHead(200, headers);
    createReadStream(filePath).pipe(res).on('error', () => res.destroy());
    return;
  }

  const clipMatch = pathname.match(/^\/api\/clips\/([A-Za-z0-9_-]{4,64})$/);
  if (clipMatch) {
    const row = q.byId.get(clipMatch[1]);
    if (!row) {
      fail(req, res, 404, 'Clip not found.');
      return;
    }
    if (method === 'GET') {
      sendJson(req, res, 200, { clip: clipFromRow(row) });
      return;
    }
    if (method === 'PATCH') {
      const body = await readJson(req, 8 * 1024);
      if (typeof body.pinned !== 'boolean') {
        fail(req, res, 400, 'Expected a pinned boolean.');
        return;
      }
      q.pin.run(body.pinned ? 1 : 0, row.id);
      const clip = clipFromRow(q.byId.get(row.id));
      broadcast({ type: 'clip-updated', clip });
      sendJson(req, res, 200, { clip });
      return;
    }
    if (method === 'DELETE') {
      q.delete.run(row.id);
      if (row.kind === 'image') {
        const files = await fs.readdir(UPLOAD_DIR).catch(() => []);
        await Promise.all(files.filter((f) => f.startsWith(`${row.id}.`)).map((f) => fs.unlink(path.join(UPLOAD_DIR, f)).catch(() => {})));
      }
      broadcast({ type: 'clip-deleted', id: row.id });
      sendJson(req, res, 200, { ok: true });
      return;
    }
    fail(req, res, 405, 'Method not allowed.');
    return;
  }

  fail(req, res, 404, 'Not found.');
}

// ---------------------------------------------------------------------------
// Server
// ---------------------------------------------------------------------------

const server = http.createServer(async (req, res) => {
  const started = Date.now();
  let url;
  try {
    url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  } catch {
    fail(req, res, 400, 'Bad request.');
    return;
  }

  const pathname = url.pathname;
  const isApi = pathname === '/api' || pathname.startsWith('/api/');
  const isHealth = pathname === '/healthz';

  try {
    if (isHealth) {
      sendJson(req, res, 200, { ok: true, service: 'pasta', version: VERSION, uptime: Math.floor(process.uptime()) });
    } else if (isApi) {
      await handleApi(req, res, url);
    } else {
      await serveStatic(req, res, pathname);
    }
  } catch (err) {
    if (err && err.status) {
      if (!res.writableEnded) fail(req, res, err.status, err.message);
    } else {
      console.error('[pasta] unhandled request error', err);
      if (!res.writableEnded) fail(req, res, 500, 'Something went wrong on the server.');
    }
  } finally {
    if (isApi) {
      const ms = Date.now() - started;
      console.log(`${new Date().toISOString()} ${req.method} ${pathname} ${res.statusCode} ${ms}ms`);
    }
  }
});

server.requestTimeout = 120_000;
server.headersTimeout = 35_000;
server.keepAliveTimeout = 70_000;
server.timeout = 0;

server.listen(PORT, HOST, () => {
  const lan = Object.values(os.networkInterfaces()).flat()
    .filter((n) => n && n.family === 'IPv4' && !n.internal)
    .map((n) => n.address);
  console.log('');
  console.log(`  Pasta ${VERSION}`);
  console.log(`  Local:   http://${HOST}:${PORT}`);
  if (PUBLIC_URL) console.log(`  Public:  ${PUBLIC_URL}`);
  if (lan.length) console.log(`  LAN:     http://${lan[0]}:${PORT} (not exposed by default)`);
  console.log(`  Data:    ${DATA_DIR}`);
  console.log('');
});

function shutdown(signal) {
  console.log(`\n[pasta] ${signal} received, shutting down.`);
  for (const client of eventClients) {
    try { client.end(); } catch {}
  }
  server.close(() => {
    try { db.close(); } catch {}
    process.exit(0);
  });
  setTimeout(() => process.exit(0), 3000).unref();
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
