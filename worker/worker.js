// Cloudflare Worker: football-data.org proxy for the Football Tracker apps.
// Holds the API key (secret FOOTBALL_DATA_KEY) and caches responses at Cloudflare's edge, so every
// user shares one cached copy and stays far below football-data.org's 10 requests/min free limit.

const UPSTREAM = "https://api.football-data.org";
const ALLOWED = /^\/v4\/(matches|competitions\/[A-Z0-9]{2,5}\/standings)$/; // nothing else is proxied
const ALLOWED_PARAMS = ["dateFrom", "dateTo", "competitions"];
const TTL_MATCHES = 10;   // seconds: live scores are at most 10s old
const TTL_STANDINGS = 300;
// Team badges. football-data's own image host is unreliable, so badges come from ESPN's logo CDN, matched by team name.
const ESPN = "https://site.api.espn.com/apis/site/v2/sports/soccer";
const ESPN_LEAGUE = { PL: "eng.1", ELC: "eng.2", PD: "esp.1", BL1: "ger.1", SA: "ita.1", FL1: "fra.1", CL: "uefa.champions", DED: "ned.1", PPL: "por.1", BSA: "bra.1" };
const ALIAS = { koln: "cologne", praha: "prague", hsv: "hamburg", hamburger: "hamburg" }; // football-data spellings ESPN writes differently
const STOP = new Set(["pae", "fc", "afc", "cf", "ac", "sc", "fk", "cd", "ud", "sv", "rc", "us", "as", "ssc", "de", "the", "club", "football", "calcio", "1899", "1900", "04", "05", "96", "1"]);

function tokens(s) {
  return (s || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9 ]/g, " ").split(/\s+/).filter((t) => t && !STOP.has(t)).map((t) => ALIAS[t] || t);
}
// Exact word = 2 points, shared word start ("man" ~ "manchester") = 1; as a share of the shorter name. Ties go to the name with more of its words matched.
function score(a, b) {
  if (!a.length || !b.length) return 0;
  const [small, big] = a.length <= b.length ? [a, b] : [b, a];
  let points = 0, matched = 0;
  for (const t of small) {
    if (big.includes(t)) { points += 2; matched++; }
    else if (big.some((u) => Math.min(t.length, u.length) >= 3 && (t.startsWith(u) || u.startsWith(t)))) { points += 1; matched++; }
  }
  return points / (2 * small.length) + 0.01 * (matched / big.length);
}

async function espnTeams(code, ctx) {
  const url = `${ESPN}/${ESPN_LEAGUE[code]}/teams?limit=200`;
  const key = new Request(url);
  let res = await caches.default.match(key);
  if (!res) {
    const up = await fetch(url, { headers: { "User-Agent": "Mozilla/5.0" } });
    if (!up.ok) return [];
    res = new Response(up.body, { headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=86400" } });
    ctx.waitUntil(caches.default.put(key, res.clone()));
  }
  const data = await res.json();
  return ((data.sports?.[0]?.leagues?.[0]?.teams) || []).map((t) => t.team);
}

async function findLogo(name, short, comp, ctx) {
  const wanted = [tokens(name), tokens(short)];
  const order = [comp, ...Object.keys(ESPN_LEAGUE).filter((c) => c !== comp)].filter((c) => ESPN_LEAGUE[c]);
  for (const code of order) {
    let best = null, bestScore = 0;
    for (const t of await espnTeams(code, ctx)) {
      const names = [tokens(t.displayName), tokens(t.shortDisplayName)];
      const sc = Math.max(...wanted.flatMap((w) => names.map((n) => score(w, n))));
      if (sc > bestScore) { bestScore = sc; best = t; }
    }
    if (best && bestScore >= 0.75 && best.logos?.length) return { href: best.logos[0].href, team: best.displayName };
  }
  return null;
}


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
    if (request.method === "GET" && url.pathname === "/logo") {
      const name = url.searchParams.get("name") || "", short = url.searchParams.get("short") || "", comp = url.searchParams.get("comp") || "";
      if (!name && !short) return new Response("Missing team", { status: 400 });
      const cacheKey = new Request(`${url.origin}/logo2?name=${encodeURIComponent(name)}&short=${encodeURIComponent(short)}&comp=${encodeURIComponent(comp)}`);
      const hit = await caches.default.match(cacheKey);
      if (hit) return hit;
      const found = await findLogo(name, short, comp, ctx);
      if (!found) return new Response("No logo", { status: 404, headers: { "Cache-Control": "public, max-age=3600" } });
      const img = await fetch(`https://a.espncdn.com/combiner/i?img=${encodeURIComponent(new URL(found.href).pathname)}&w=96&h=96`);
      if (!img.ok) return new Response("No logo", { status: 404 });
      const response = new Response(img.body, { headers: { "Content-Type": img.headers.get("Content-Type") || "image/png", "Cache-Control": "public, max-age=2592000", "Access-Control-Allow-Origin": "*", "X-Logo-Team": found.team } });
      ctx.waitUntil(caches.default.put(cacheKey, response.clone()));
      return response;
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
