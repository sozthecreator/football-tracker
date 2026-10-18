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

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
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
