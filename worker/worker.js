// Cloudflare Worker: football-data.org proxy for the Football Tracker apps.
// Holds the API key (secret FOOTBALL_DATA_KEY) and caches responses at Cloudflare's edge, so every
// user shares one cached copy and stays far below football-data.org's 10 requests/min free limit.

const UPSTREAM = "https://api.football-data.org";
const ALLOWED = /^\/v4\/(matches|competitions\/[A-Z0-9]{2,5}\/standings)$/; // nothing else is proxied
const ALLOWED_PARAMS = ["dateFrom", "dateTo", "competitions"];
const TTL_MATCHES = 10;   // seconds: live scores are at most 10s old
const TTL_STANDINGS = 300;
// ---- accounts: username + password, saved teams stored in D1 (binding DB). Passwords are salted PBKDF2 hashes, never stored or logged in clear.
const ITERATIONS = 100000, TOKEN_DAYS = 180, MAX_ATTEMPTS = 8, WINDOW_MS = 15 * 60_000, MAX_PROFILE = 40_000;
const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
const fromHex = (h) => new Uint8Array(h.match(/../g).map((x) => parseInt(x, 16)));
const reply = (obj, status = 200) => new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json" } });

async function hashPassword(password, saltHex, iterations) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  return hex(await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt: fromHex(saltHex), iterations }, key, 256));
}
async function sha256(text) { return hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text))); }
const randomHex = (bytes) => hex(crypto.getRandomValues(new Uint8Array(bytes)));

async function blocked(env, key) {
  const r = await env.DB.prepare("SELECT count, since FROM attempts WHERE key = ?").bind(key).first();
  return !!r && Date.now() - r.since < WINDOW_MS && r.count >= MAX_ATTEMPTS;
}
async function noteFailure(env, key) {
  const now = Date.now();
  await env.DB.prepare("INSERT INTO attempts (key, count, since) VALUES (?, 1, ?) ON CONFLICT(key) DO UPDATE SET count = CASE WHEN ? - since > ? THEN 1 ELSE count + 1 END, since = CASE WHEN ? - since > ? THEN ? ELSE since END")
    .bind(key, now, now, WINDOW_MS, now, WINDOW_MS, now).run();
}
async function newToken(env, username) {
  const token = randomHex(32);
  await env.DB.prepare("INSERT INTO tokens (hash, username, created) VALUES (?, ?, ?)").bind(await sha256(token), username, Date.now()).run();
  return token;
}
async function whoAmI(request, env) {
  const m = /^Bearer ([0-9a-f]{64})$/.exec(request.headers.get("Authorization") || "");
  if (!m) return null;
  const r = await env.DB.prepare("SELECT username, created FROM tokens WHERE hash = ?").bind(await sha256(m[1])).first();
  return r && Date.now() - r.created < TOKEN_DAYS * 86_400_000 ? { username: r.username, tokenHash: await sha256(m[1]) } : null;
}

async function handleAccounts(request, env, url) {
  const path = url.pathname;
  if (request.method === "POST" && (path === "/auth/register" || path === "/auth/login")) {
    let body;
    try { body = await request.json(); } catch { return reply({ error: "Bad request" }, 400); }
    const username = String(body.username || "").trim().toLowerCase(), password = String(body.password || "");
    if (!/^[a-z0-9_]{3,20}$/.test(username)) return reply({ error: "Username: 3-20 letters, numbers or _" }, 400);
    if (password.length < 8 || password.length > 128) return reply({ error: "Password must be 8-128 characters" }, 400);
    const ip = request.headers.get("CF-Connecting-IP") || "unknown", keys = [`u:${username}`, `ip:${ip}`];
    for (const k of keys) if (await blocked(env, k)) return reply({ error: "Too many attempts. Try again in 15 minutes." }, 429);

    if (path === "/auth/register") {
      if (await env.DB.prepare("SELECT 1 FROM users WHERE username = ?").bind(username).first()) return reply({ error: "That username is taken" }, 409);
      await noteFailure(env, `ip:${ip}`); // counts sign-ups per address too, so one address can't mass-create accounts
      const salt = randomHex(16);
      await env.DB.prepare("INSERT INTO users (username, salt, hash, iterations, created) VALUES (?, ?, ?, ?, ?)")
        .bind(username, salt, await hashPassword(password, salt, ITERATIONS), ITERATIONS, Date.now()).run();
      return reply({ token: await newToken(env, username), username });
    }
    const u = await env.DB.prepare("SELECT salt, hash, iterations FROM users WHERE username = ?").bind(username).first();
    const ok = u && (await hashPassword(password, u.salt, u.iterations)) === u.hash;
    if (!ok) {
      for (const k of keys) await noteFailure(env, k);
      return reply({ error: "Wrong username or password" }, 401);
    }
    await env.DB.prepare("DELETE FROM attempts WHERE key = ?").bind(keys[0]).run();
    return reply({ token: await newToken(env, username), username });
  }

  const me = await whoAmI(request, env);
  if (!me) return reply({ error: "Not signed in" }, 401);

  if (request.method === "POST" && path === "/auth/logout") {
    await env.DB.prepare("DELETE FROM tokens WHERE hash = ?").bind(me.tokenHash).run();
    return reply({ ok: true });
  }
  if (path === "/profile" && request.method === "GET") {
    const r = await env.DB.prepare("SELECT data, updated FROM profiles WHERE username = ?").bind(me.username).first();
    return reply(r ? { data: r.data, updated: r.updated } : { data: null, updated: 0 });
  }
  if (path === "/profile" && request.method === "PUT") {
    let body;
    try { body = await request.json(); } catch { return reply({ error: "Bad request" }, 400); }
    if (typeof body.data !== "string" || body.data.length > MAX_PROFILE || !Number.isFinite(body.updated)) return reply({ error: "Bad profile" }, 400);
    await env.DB.prepare("INSERT INTO profiles (username, data, updated) VALUES (?, ?, ?) ON CONFLICT(username) DO UPDATE SET data = excluded.data, updated = excluded.updated")
      .bind(me.username, body.data, Math.trunc(body.updated)).run();
    return reply({ ok: true });
  }
  if (path === "/account" && request.method === "DELETE") {
    await env.DB.batch([
      env.DB.prepare("DELETE FROM profiles WHERE username = ?").bind(me.username),
      env.DB.prepare("DELETE FROM tokens WHERE username = ?").bind(me.username),
      env.DB.prepare("DELETE FROM users WHERE username = ?").bind(me.username),
    ]);
    return reply({ ok: true });
  }
  return reply({ error: "Not found" }, 404);
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (url.pathname.startsWith("/auth/") || url.pathname === "/profile" || url.pathname === "/account") {
      try { return await handleAccounts(request, env, url); } catch (e) { return reply({ error: "Server error" }, 500); }
    }
    if (request.method !== "GET" || !ALLOWED.test(url.pathname)) {
      return new Response("Not found", { status: 404 });
    }

    // Rebuild the query with only the allowed parameters, sorted, so equal requests share one cache entry.
    const params = new URLSearchParams();
    for (const k of ALLOWED_PARAMS) if (url.searchParams.has(k)) params.set(k, url.searchParams.get(k));
    const upstreamUrl = `${UPSTREAM}${url.pathname}?${params}`;

    const cache = caches.default;
    const cacheKey = new Request(upstreamUrl);
    const hit = await cache.match(cacheKey);
    if (hit) return hit;

    const upstream = await fetch(upstreamUrl, { headers: { "X-Auth-Token": env.FOOTBALL_DATA_KEY } });
    const ttl = url.pathname.endsWith("/standings") ? TTL_STANDINGS : TTL_MATCHES;
    const response = new Response(upstream.body, {
      status: upstream.status,
      headers: {
        "Content-Type": upstream.headers.get("Content-Type") || "application/json",
        // errors (429, 403) are cached briefly so a rate-limited upstream isn't hammered
        "Cache-Control": `public, max-age=${upstream.ok ? ttl : 5}`,
        "Access-Control-Allow-Origin": "*",
      },
    });
    ctx.waitUntil(cache.put(cacheKey, response.clone()));
    return response;
  },
};
