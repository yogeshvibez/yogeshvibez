/*
 * Heyogesh Drive host
 *
 * This intentionally uses Node's http module instead of a framework: the API
 * surface stays small, range requests are explicit, and the server can run on
 * a Windows PC without a process manager or an application framework.
 *
 * Put this process behind Cloudflare Tunnel. Bind to 127.0.0.1 (the default),
 * never expose this origin port directly to the public internet.
 */
'use strict';

const http = require('node:http');
const crypto = require('node:crypto');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { pipeline } = require('node:stream/promises');
// Archiver 8 exports format-specific classes instead of the v7 factory.
// Destructuring works through Node 20's CommonJS-to-ESM bridge while retaining
// this server's CommonJS entry point for simple Windows deployment.
const { ZipArchive } = require('archiver');

const PORT = integerEnv('PORT', 8787, 1, 65535);
const BIND_HOST = process.env.BIND_HOST || '127.0.0.1';
const DOCUMENTS_ROOT = path.resolve(
  process.env.DOCUMENTS_ROOT || path.join(os.homedir(), 'Documents'),
);
const PUBLIC_BASE_URL = normalisePublicUrl(
  process.env.PUBLIC_BASE_URL || 'https://storage.heyogesh.dpdns.org',
);
const LOGIN_PASSWORD = process.env.HEYOGESH_DRIVE_PASSWORD || '2608';
const TOKEN_TTL_MS = integerEnv('TOKEN_TTL_HOURS', 12, 1, 168) * 60 * 60 * 1000;
const MEDIA_URL_TTL_MS = integerEnv('MEDIA_URL_TTL_MINUTES', 10, 1, 60) * 60 * 1000;
const MAX_JSON_BYTES = 32 * 1024;
const MAX_LIST_LIMIT = 500;
const MAX_ARCHIVE_ROOTS = 50;
const MAX_ARCHIVE_ENTRIES = integerEnv('MAX_ARCHIVE_ENTRIES', 100000, 1, 1000000);
const ARCHIVE_TTL_MS = integerEnv('ARCHIVE_TTL_MINUTES', 60, 5, 720) * 60 * 1000;
const ARCHIVE_DIR = path.join(os.tmpdir(), 'heyogesh-drive-archives');
const ALLOWED_ORIGIN = process.env.ALLOWED_ORIGIN || '';

// A persisted key keeps sessions and signed player URLs valid across restarts.
// Falling back to a random key is safe for local development but logs users out
// after every restart, which is deliberately visible in the server log.
const SIGNING_KEY = process.env.HEYOGESH_DRIVE_SIGNING_KEY
  ? Buffer.from(process.env.HEYOGESH_DRIVE_SIGNING_KEY, 'utf8')
  : crypto.randomBytes(32);
const USING_EPHEMERAL_SIGNING_KEY = !process.env.HEYOGESH_DRIVE_SIGNING_KEY;

const archiveJobs = new Map();
const loginAttempts = new Map();

const MIME_TYPES = new Map([
  ['.mp4', 'video/mp4'], ['.m4v', 'video/x-m4v'], ['.mkv', 'video/x-matroska'],
  ['.avi', 'video/x-msvideo'], ['.mov', 'video/quicktime'], ['.webm', 'video/webm'],
  ['.mp3', 'audio/mpeg'], ['.wav', 'audio/wav'], ['.flac', 'audio/flac'],
  ['.jpg', 'image/jpeg'], ['.jpeg', 'image/jpeg'], ['.png', 'image/png'],
  ['.gif', 'image/gif'], ['.webp', 'image/webp'], ['.apk', 'application/vnd.android.package-archive'],
  ['.pdf', 'application/pdf'], ['.txt', 'text/plain; charset=utf-8'],
  ['.zip', 'application/zip'], ['.json', 'application/json; charset=utf-8'],
]);

function integerEnv(name, fallback, min, max) {
  const value = Number.parseInt(process.env[name] || '', 10);
  return Number.isInteger(value) && value >= min && value <= max ? value : fallback;
}

function normalisePublicUrl(value) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error('PUBLIC_BASE_URL must be an absolute HTTPS URL.');
  }
  if (url.protocol !== 'https:') {
    throw new Error('PUBLIC_BASE_URL must use HTTPS.');
  }
  url.pathname = url.pathname.replace(/\/$/, '');
  return url.toString().replace(/\/$/, '');
}

function requestId() {
  return crypto.randomUUID();
}

function setBaseHeaders(res, id) {
  res.setHeader('X-Request-Id', id);
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('Cache-Control', 'no-store');
}

function applyCors(req, res) {
  const origin = req.headers.origin;
  if (ALLOWED_ORIGIN && origin === ALLOWED_ORIGIN) {
    res.setHeader('Access-Control-Allow-Origin', origin);
    res.setHeader('Vary', 'Origin');
    res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  }
}

function sendJson(res, status, body) {
  if (res.writableEnded) return;
  const data = Buffer.from(JSON.stringify(body));
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': data.length,
  });
  res.end(data);
}

function sendError(res, status, code, message, id, details) {
  sendJson(res, status, {
    error: { code, message, requestId: id, ...(details ? { details } : {}) },
  });
}

function clientIp(req) {
  const cloudflareIp = req.headers['cf-connecting-ip'];
  return typeof cloudflareIp === 'string' ? cloudflareIp : (req.socket.remoteAddress || 'unknown');
}

function mimeFor(fileName) {
  return MIME_TYPES.get(path.extname(fileName).toLowerCase()) || 'application/octet-stream';
}

function fileKind(fileName, stat) {
  if (stat.isDirectory()) return 'folder';
  const mime = mimeFor(fileName);
  if (mime.startsWith('video/')) return 'video';
  if (mime.startsWith('audio/')) return 'audio';
  if (mime.startsWith('image/')) return 'image';
  if (path.extname(fileName).toLowerCase() === '.apk') return 'apk';
  return 'file';
}

function base64Url(input) {
  return Buffer.from(input).toString('base64url');
}

function hmac(value) {
  return crypto.createHmac('sha256', SIGNING_KEY).update(value).digest('base64url');
}

function safeEqual(left, right) {
  const a = Buffer.from(left || '');
  const b = Buffer.from(right || '');
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function issueSession(subject) {
  const now = Date.now();
  const payload = { sub: subject, iat: now, exp: now + TOKEN_TTL_MS, aud: 'heyogesh-drive' };
  const encoded = base64Url(JSON.stringify(payload));
  return `${encoded}.${hmac(`session.${encoded}`)}`;
}

function verifySession(token) {
  if (!token || typeof token !== 'string') return null;
  const [encoded, signature, ...rest] = token.split('.');
  if (!encoded || !signature || rest.length || !safeEqual(signature, hmac(`session.${encoded}`))) return null;
  try {
    const payload = JSON.parse(Buffer.from(encoded, 'base64url').toString('utf8'));
    if (payload.aud !== 'heyogesh-drive' || !payload.sub || !Number.isFinite(payload.exp) || payload.exp <= Date.now()) return null;
    return payload;
  } catch {
    return null;
  }
}

function bearerSession(req) {
  const header = req.headers.authorization;
  if (typeof header !== 'string' || !header.startsWith('Bearer ')) return null;
  return verifySession(header.slice(7));
}

function signMediaPath(relativePath, expiresAt) {
  return hmac(`media.${expiresAt}.${relativePath}`);
}

function mediaUrlFor(relativePath) {
  const expiresAt = Date.now() + MEDIA_URL_TTL_MS;
  const url = new URL('/api/v1/media', PUBLIC_BASE_URL);
  url.searchParams.set('path', relativePath);
  url.searchParams.set('expires', String(expiresAt));
  url.searchParams.set('signature', signMediaPath(relativePath, expiresAt));
  return { url: url.toString(), expiresAt };
}

function verifyMediaSignature(url) {
  const relativePath = typeof url.searchParams.get('path') === 'string' ? url.searchParams.get('path') : '';
  const expiresAt = Number(url.searchParams.get('expires'));
  const signature = url.searchParams.get('signature') || '';
  if (!relativePath || !Number.isSafeInteger(expiresAt) || expiresAt < Date.now() || expiresAt > Date.now() + MEDIA_URL_TTL_MS + 60_000) {
    return null;
  }
  return safeEqual(signature, signMediaPath(relativePath, expiresAt)) ? relativePath : null;
}

function normaliseRelativePath(value) {
  if (typeof value !== 'string') throw httpError(400, 'INVALID_PATH', 'A path must be a string.');
  const trimmed = value.trim().replace(/\\/g, '/');
  if (!trimmed) return '';
  if (trimmed.includes('\0') || trimmed.startsWith('/') || /^[a-zA-Z]:/.test(trimmed)) {
    throw httpError(400, 'INVALID_PATH', 'The path must be relative to Documents.');
  }
  const normalised = path.posix.normalize(trimmed).replace(/^\.\//, '');
  if (normalised === '..' || normalised.startsWith('../')) {
    throw httpError(403, 'PATH_OUTSIDE_ROOT', 'The requested path is outside Documents.');
  }
  return normalised === '.' ? '' : normalised;
}

function absoluteFromRelative(relativePath) {
  const safePath = normaliseRelativePath(relativePath);
  const absolute = path.resolve(DOCUMENTS_ROOT, ...safePath.split('/').filter(Boolean));
  const relative = path.relative(DOCUMENTS_ROOT, absolute);
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    throw httpError(403, 'PATH_OUTSIDE_ROOT', 'The requested path is outside Documents.');
  }
  return { absolute, relative: relative.split(path.sep).join('/') };
}

// Never follow symbolic links. Without this check a link inside Documents could
// expose an arbitrary path on the Windows machine.
async function assertNoSymlink(absolute, relativePath) {
  let current = DOCUMENTS_ROOT;
  for (const part of relativePath.split('/').filter(Boolean)) {
    current = path.join(current, part);
    let stat;
    try {
      stat = await fsp.lstat(current);
    } catch (error) {
      if (error.code === 'ENOENT') throw httpError(404, 'NOT_FOUND', 'The requested item no longer exists.');
      throw error;
    }
    if (stat.isSymbolicLink()) throw httpError(403, 'SYMLINK_BLOCKED', 'Symbolic links cannot be shared.');
  }
  return fsp.lstat(absolute);
}

async function resolveExisting(relativePath) {
  const target = absoluteFromRelative(relativePath);
  const stat = await assertNoSymlink(target.absolute, target.relative);
  return { ...target, stat };
}

function toApiItem(name, relativePath, stat) {
  return {
    name,
    path: relativePath,
    kind: fileKind(name, stat),
    size: stat.isFile() ? stat.size : null,
    modifiedAt: stat.mtime.toISOString(),
    extension: stat.isFile() ? path.extname(name).slice(1).toLowerCase() : null,
    mimeType: stat.isFile() ? mimeFor(name) : null,
  };
}

async function listFolder(relativePath, offset, limit) {
  const directory = await resolveExisting(relativePath);
  if (!directory.stat.isDirectory()) throw httpError(400, 'NOT_A_FOLDER', 'This item is not a folder.');
  const dirents = await fsp.readdir(directory.absolute, { withFileTypes: true });
  const visible = dirents.filter((entry) => !entry.isSymbolicLink() && entry.name !== 'desktop.ini');
  visible.sort((a, b) => {
    const typeDifference = Number(b.isDirectory()) - Number(a.isDirectory());
    return typeDifference || a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' });
  });
  const page = visible.slice(offset, offset + limit);
  const items = [];
  for (const entry of page) {
    const childRelative = [directory.relative, entry.name].filter(Boolean).join('/');
    try {
      const stat = await assertNoSymlink(path.join(directory.absolute, entry.name), childRelative);
      if (stat.isFile() || stat.isDirectory()) items.push(toApiItem(entry.name, childRelative, stat));
    } catch (error) {
      // A file can disappear while a large folder is being read. Skip only that
      // race; all other errors need to remain visible to the client.
      if (error.code !== 'ENOENT' && error.status !== 404) throw error;
    }
  }
  return {
    path: directory.relative,
    parentPath: directory.relative.includes('/') ? directory.relative.slice(0, directory.relative.lastIndexOf('/')) : null,
    total: visible.length,
    offset,
    limit,
    hasMore: offset + items.length < visible.length,
    items,
  };
}

async function folderTree(relativePath, depth, budget) {
  const directory = await resolveExisting(relativePath);
  if (!directory.stat.isDirectory()) throw httpError(400, 'NOT_A_FOLDER', 'This item is not a folder.');
  const node = toApiItem(path.basename(directory.relative) || 'Documents', directory.relative, directory.stat);
  node.children = [];
  if (depth <= 0 || budget.remaining <= 0) return node;
  const entries = await fsp.readdir(directory.absolute, { withFileTypes: true });
  for (const entry of entries) {
    if (budget.remaining <= 0) break;
    if (!entry.isDirectory() || entry.isSymbolicLink() || entry.name === 'desktop.ini') continue;
    const child = [directory.relative, entry.name].filter(Boolean).join('/');
    budget.remaining -= 1;
    try {
      node.children.push(await folderTree(child, depth - 1, budget));
    } catch (error) {
      if (error.status !== 404) throw error;
    }
  }
  return node;
}

function parseRange(rangeHeader, size) {
  if (!rangeHeader) return null;
  const match = /^bytes=(\d*)-(\d*)$/.exec(rangeHeader);
  if (!match) return { invalid: true };
  let start;
  let end;
  if (match[1] === '' && match[2] === '') return { invalid: true };
  if (match[1] === '') {
    const suffixLength = Number(match[2]);
    if (!Number.isSafeInteger(suffixLength) || suffixLength <= 0) return { invalid: true };
    start = Math.max(0, size - suffixLength);
    end = size - 1;
  } else {
    start = Number(match[1]);
    end = match[2] === '' ? size - 1 : Number(match[2]);
  }
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || start >= size || end < start) return { invalid: true };
  return { start, end: Math.min(end, size - 1) };
}

function contentDisposition(disposition, fileName) {
  const fallback = fileName.replace(/[\\"\r\n]/g, '_');
  return `${disposition}; filename="${fallback}"; filename*=UTF-8''${encodeURIComponent(fileName)}`;
}

async function streamFile(req, res, file, options) {
  const { absolute, stat } = file;
  if (!stat.isFile()) throw httpError(400, 'NOT_A_FILE', 'This item is not a file.');
  const range = parseRange(req.headers.range, stat.size);
  if (range?.invalid) {
    res.writeHead(416, { 'Content-Range': `bytes */${stat.size}` });
    res.end();
    return;
  }
  const start = range ? range.start : 0;
  const end = range ? range.end : stat.size - 1;
  const headers = {
    'Content-Type': mimeFor(absolute),
    'Accept-Ranges': 'bytes',
    'Content-Length': end - start + 1,
    'Content-Disposition': contentDisposition(options.disposition, path.basename(absolute)),
    'X-Download-Bytes': String(stat.size),
  };
  if (options.cacheable) headers['Cache-Control'] = 'private, max-age=60';
  if (range) headers['Content-Range'] = `bytes ${start}-${end}/${stat.size}`;
  res.writeHead(range ? 206 : 200, headers);
  if (req.method === 'HEAD') return res.end();
  const source = fs.createReadStream(absolute, { start, end });
  const stop = () => source.destroy();
  req.once('aborted', stop);
  res.once('close', stop);
  try {
    await pipeline(source, res);
  } catch (error) {
    if (!req.aborted && !res.writableEnded) throw error;
  } finally {
    req.off('aborted', stop);
    res.off('close', stop);
  }
}

function httpError(status, code, message, details) {
  const error = new Error(message);
  error.status = status;
  error.code = code;
  error.details = details;
  return error;
}

async function readJson(req) {
  let received = 0;
  const chunks = [];
  for await (const chunk of req) {
    received += chunk.length;
    if (received > MAX_JSON_BYTES) throw httpError(413, 'PAYLOAD_TOO_LARGE', 'The JSON body is too large.');
    chunks.push(chunk);
  }
  if (!received) throw httpError(400, 'INVALID_JSON', 'A JSON body is required.');
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw httpError(400, 'INVALID_JSON', 'The request body is not valid JSON.');
  }
}

function loginRateLimit(req) {
  const key = clientIp(req);
  const now = Date.now();
  const windowMs = 10 * 60 * 1000;
  const maxAttempts = 8;
  const prior = loginAttempts.get(key) || [];
  const current = prior.filter((time) => time > now - windowMs);
  if (current.length >= maxAttempts) {
    const retryAfterSeconds = Math.ceil((current[0] + windowMs - now) / 1000);
    return { blocked: true, retryAfterSeconds };
  }
  current.push(now);
  loginAttempts.set(key, current);
  return { blocked: false };
}

async function scanEntry(fullPath, archiveName, job, entries) {
  const stat = await fsp.lstat(fullPath);
  if (stat.isSymbolicLink()) return;
  if (stat.isDirectory()) {
    entries.push({ type: 'directory', fullPath, archiveName: `${archiveName.replace(/\/$/, '')}/` });
    const children = await fsp.readdir(fullPath, { withFileTypes: true });
    for (const child of children) {
      if (job.cancelRequested) throw httpError(499, 'ARCHIVE_CANCELLED', 'Archive creation was cancelled.');
      await scanEntry(path.join(fullPath, child.name), `${archiveName.replace(/\/$/, '')}/${child.name}`, job, entries);
      if (entries.length > MAX_ARCHIVE_ENTRIES) {
        throw httpError(413, 'ARCHIVE_TOO_LARGE', `An archive may contain at most ${MAX_ARCHIVE_ENTRIES} entries.`);
      }
    }
    return;
  }
  if (stat.isFile()) {
    entries.push({ type: 'file', fullPath, archiveName, size: stat.size });
    job.sourceBytes += stat.size;
  }
}

function uniqueArchiveName(name, usedNames) {
  const parsed = path.posix.parse(name);
  let candidate = name;
  let index = 2;
  while (usedNames.has(candidate.toLocaleLowerCase())) {
    candidate = `${parsed.name} (${index})${parsed.ext}`;
    index += 1;
  }
  usedNames.add(candidate.toLocaleLowerCase());
  return candidate;
}

async function createArchiveJob(owner, requestedPaths) {
  if (!Array.isArray(requestedPaths) || !requestedPaths.length) {
    throw httpError(400, 'INVALID_SELECTION', 'Select at least one file or folder.');
  }
  if (requestedPaths.length > MAX_ARCHIVE_ROOTS) {
    throw httpError(413, 'TOO_MANY_ROOTS', `Select at most ${MAX_ARCHIVE_ROOTS} root items at once.`);
  }
  const job = {
    id: crypto.randomUUID(), owner, state: 'queued', createdAt: Date.now(), updatedAt: Date.now(),
    sourceBytes: 0, processedBytes: 0, processedFiles: 0, totalFiles: 0,
    error: null, outputPath: null, outputBytes: 0, cancelRequested: false,
  };
  archiveJobs.set(job.id, job);
  void buildArchive(job, requestedPaths);
  return job;
}

async function buildArchive(job, requestedPaths) {
  let outputPath;
  try {
    await fsp.mkdir(ARCHIVE_DIR, { recursive: true });
    job.state = 'scanning';
    job.updatedAt = Date.now();
    const entries = [];
    const usedNames = new Set();
    const uniqueInputs = [...new Set(requestedPaths.map(normaliseRelativePath))];
    for (const relativePath of uniqueInputs) {
      const item = await resolveExisting(relativePath);
      const archiveRoot = uniqueArchiveName(path.basename(item.absolute), usedNames);
      await scanEntry(item.absolute, archiveRoot, job, entries);
    }
    job.totalFiles = entries.filter((entry) => entry.type === 'file').length;
    if (!entries.length) throw httpError(400, 'EMPTY_SELECTION', 'The selection contains no shareable files.');
    if (job.cancelRequested) throw httpError(499, 'ARCHIVE_CANCELLED', 'Archive creation was cancelled.');

    job.state = 'building';
    job.updatedAt = Date.now();
    outputPath = path.join(ARCHIVE_DIR, `${job.id}.zip`);
    const output = fs.createWriteStream(outputPath, { flags: 'wx' });
    const zip = new ZipArchive({ zlib: { level: 6 }, forceZip64: true });
    zip.on('progress', (progress) => {
      job.processedBytes = Math.min(job.sourceBytes, progress.fs.processedBytes || 0);
      job.processedFiles = progress.entries.processed || job.processedFiles;
      job.updatedAt = Date.now();
    });
    zip.on('warning', (warning) => {
      if (warning.code !== 'ENOENT') zip.emit('error', warning);
    });
    for (const entry of entries) {
      if (entry.type === 'directory') zip.append('', { name: entry.archiveName });
      else zip.file(entry.fullPath, { name: entry.archiveName, stats: false });
    }
    await new Promise((resolve, reject) => {
      output.once('close', resolve);
      output.once('error', reject);
      zip.once('error', reject);
      zip.pipe(output);
      zip.finalize().catch(reject);
    });
    if (job.cancelRequested) throw httpError(499, 'ARCHIVE_CANCELLED', 'Archive creation was cancelled.');
    const outputStat = await fsp.stat(outputPath);
    job.outputPath = outputPath;
    job.outputBytes = outputStat.size;
    job.processedBytes = job.sourceBytes;
    job.processedFiles = job.totalFiles;
    job.state = 'ready';
    job.updatedAt = Date.now();
  } catch (error) {
    if (outputPath) await fsp.rm(outputPath, { force: true }).catch(() => {});
    job.state = job.cancelRequested ? 'cancelled' : 'failed';
    job.error = { code: error.code || 'ARCHIVE_FAILED', message: error.message || 'Could not create the archive.' };
    job.updatedAt = Date.now();
  }
}

function archivePayload(job) {
  return {
    id: job.id, state: job.state, sourceBytes: job.sourceBytes,
    processedBytes: job.processedBytes, totalFiles: job.totalFiles,
    processedFiles: job.processedFiles, outputBytes: job.outputBytes,
    error: job.error, updatedAt: new Date(job.updatedAt).toISOString(),
    downloadPath: job.state === 'ready' ? `/api/v1/archives/${job.id}/download` : null,
  };
}

async function serveArchive(req, res, job) {
  const stat = await fsp.stat(job.outputPath);
  const range = parseRange(req.headers.range, stat.size);
  if (range?.invalid) {
    res.writeHead(416, { 'Content-Range': `bytes */${stat.size}` });
    return res.end();
  }
  const start = range ? range.start : 0;
  const end = range ? range.end : stat.size - 1;
  res.writeHead(range ? 206 : 200, {
    'Content-Type': 'application/zip', 'Accept-Ranges': 'bytes',
    'Content-Length': end - start + 1,
    'Content-Disposition': contentDisposition('attachment', `Heyogesh-Drive-${job.id.slice(0, 8)}.zip`),
    'X-Download-Bytes': String(stat.size),
    ...(range ? { 'Content-Range': `bytes ${start}-${end}/${stat.size}` } : {}),
  });
  if (req.method === 'HEAD') return res.end();
  await pipeline(fs.createReadStream(job.outputPath, { start, end }), res);
}

function requireSession(req) {
  const session = bearerSession(req);
  if (!session) throw httpError(401, 'UNAUTHENTICATED', 'Sign in again to continue.');
  return session;
}

async function route(req, res, url, id) {
  const method = req.method || 'GET';
  const pathname = url.pathname;
  if (method === 'OPTIONS') {
    res.writeHead(204);
    return res.end();
  }
  if (method === 'GET' && pathname === '/healthz') return sendJson(res, 200, { status: 'ok' });

  if (method === 'POST' && pathname === '/api/v1/auth/login') {
    const limit = loginRateLimit(req);
    if (limit.blocked) {
      res.setHeader('Retry-After', String(limit.retryAfterSeconds));
      throw httpError(429, 'TOO_MANY_ATTEMPTS', 'Too many sign-in attempts. Try again later.');
    }
    const body = await readJson(req);
    if (typeof body.password !== 'string' || !safeEqual(body.password, LOGIN_PASSWORD)) {
      throw httpError(401, 'INVALID_PASSWORD', 'The password is not valid.');
    }
    const subject = crypto.randomUUID();
    const accessToken = issueSession(subject);
    return sendJson(res, 200, { accessToken, expiresAt: new Date(Date.now() + TOKEN_TTL_MS).toISOString() });
  }

  if ((method === 'GET' || method === 'HEAD') && pathname === '/api/v1/media') {
    const signedPath = verifyMediaSignature(url);
    if (!signedPath) throw httpError(401, 'INVALID_MEDIA_LINK', 'This streaming link has expired.');
    return streamFile(req, res, await resolveExisting(signedPath), { disposition: 'inline', cacheable: true });
  }

  const session = requireSession(req);
  if (method === 'GET' && pathname === '/api/v1/folders') {
    const relativePath = url.searchParams.get('path') || '';
    const offset = Math.max(0, Number.parseInt(url.searchParams.get('offset') || '0', 10) || 0);
    const limit = Math.min(MAX_LIST_LIMIT, Math.max(1, Number.parseInt(url.searchParams.get('limit') || '200', 10) || 200));
    return sendJson(res, 200, await listFolder(relativePath, offset, limit));
  }
  if (method === 'GET' && pathname === '/api/v1/tree') {
    const relativePath = url.searchParams.get('path') || '';
    const depth = Math.min(4, Math.max(0, Number.parseInt(url.searchParams.get('depth') || '2', 10) || 2));
    return sendJson(res, 200, { tree: await folderTree(relativePath, depth, { remaining: 1000 }) });
  }
  if (method === 'GET' && pathname === '/api/v1/open') {
    const relativePath = normaliseRelativePath(url.searchParams.get('path') || '');
    const item = await resolveExisting(relativePath);
    if (!item.stat.isFile()) throw httpError(400, 'NOT_A_FILE', 'Only files can be opened.');
    const media = mediaUrlFor(item.relative);
    return sendJson(res, 200, {
      path: item.relative, mimeType: mimeFor(item.absolute), streamUrl: media.url,
      streamExpiresAt: new Date(media.expiresAt).toISOString(),
      downloadPath: `/api/v1/download?path=${encodeURIComponent(item.relative)}`,
    });
  }
  if ((method === 'GET' || method === 'HEAD') && pathname === '/api/v1/download') {
    return streamFile(req, res, await resolveExisting(url.searchParams.get('path') || ''), { disposition: 'attachment', cacheable: false });
  }
  if (method === 'POST' && pathname === '/api/v1/archives') {
    const body = await readJson(req);
    const job = await createArchiveJob(session.sub, body.paths);
    return sendJson(res, 202, archivePayload(job));
  }
  const archiveMatch = /^\/api\/v1\/archives\/([0-9a-f-]{36})(?:\/(download|cancel))?$/.exec(pathname);
  if (archiveMatch) {
    const job = archiveJobs.get(archiveMatch[1]);
    if (!job || job.owner !== session.sub) throw httpError(404, 'ARCHIVE_NOT_FOUND', 'This archive is no longer available.');
    if (method === 'GET' && !archiveMatch[2]) return sendJson(res, 200, archivePayload(job));
    if ((method === 'GET' || method === 'HEAD') && archiveMatch[2] === 'download') {
      if (job.state !== 'ready') throw httpError(409, 'ARCHIVE_NOT_READY', 'The archive is not ready to download.');
      return serveArchive(req, res, job);
    }
    if (method === 'POST' && archiveMatch[2] === 'cancel') {
      job.cancelRequested = true;
      job.updatedAt = Date.now();
      return sendJson(res, 202, archivePayload(job));
    }
  }
  throw httpError(404, 'ROUTE_NOT_FOUND', 'The requested API endpoint does not exist.');
}

async function main() {
  const rootStat = await fsp.stat(DOCUMENTS_ROOT).catch(() => null);
  if (!rootStat?.isDirectory()) throw new Error(`DOCUMENTS_ROOT is not a readable folder: ${DOCUMENTS_ROOT}`);
  await fsp.mkdir(ARCHIVE_DIR, { recursive: true });
  const server = http.createServer(async (req, res) => {
    const id = requestId();
    setBaseHeaders(res, id);
    applyCors(req, res);
    try {
      const url = new URL(req.url || '/', 'http://localhost');
      await route(req, res, url, id);
    } catch (error) {
      if (res.headersSent) {
        res.destroy();
        return;
      }
      const status = Number.isInteger(error.status) ? error.status : 500;
      if (status >= 500) console.error(`[${id}]`, error);
      sendError(res, status, error.code || 'INTERNAL_ERROR', status >= 500 ? 'The server could not complete this request.' : error.message, id, error.details);
    }
  });
  server.requestTimeout = 0; // long media streams must not be cut off by Node's request timer.
  server.headersTimeout = 30_000;
  server.listen(PORT, BIND_HOST, () => {
    console.log(`Heyogesh Drive host listening on http://${BIND_HOST}:${PORT}`);
    console.log(`Documents root: ${DOCUMENTS_ROOT}`);
    console.log(`Public base URL: ${PUBLIC_BASE_URL}`);
    if (USING_EPHEMERAL_SIGNING_KEY) console.warn('WARNING: HEYOGESH_DRIVE_SIGNING_KEY is not set; sessions end after a restart.');
    if (!process.env.HEYOGESH_DRIVE_PASSWORD) console.warn('WARNING: default password is active. Set HEYOGESH_DRIVE_PASSWORD before public use.');
  });
  const close = () => server.close(() => process.exit(0));
  process.once('SIGINT', close);
  process.once('SIGTERM', close);
}

// Remove temporary ZIP files when a job expires. Archive download is deliberately
// short-lived so the host PC does not quietly fill with old selections.
setInterval(() => {
  const now = Date.now();
  for (const [id, job] of archiveJobs) {
    if (now - job.updatedAt < ARCHIVE_TTL_MS) continue;
    archiveJobs.delete(id);
    if (job.outputPath) void fsp.rm(job.outputPath, { force: true }).catch(() => {});
  }
}, 5 * 60 * 1000).unref();

main().catch((error) => {
  console.error('Unable to start Heyogesh Drive host:', error.message);
  process.exitCode = 1;
});
