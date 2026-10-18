// Cloudflare Worker: football-data.org proxy for the Football Tracker apps.
// Holds the API key (secret FOOTBALL_DATA_KEY) and caches responses at Cloudflare's edge, so every
// user shares one cached copy and stays far below football-data.org's 10 requests/min free limit.

const UPSTREAM = "https://api.football-data.org";
const ALLOWED = /^\/v4\/(matches|competitions\/[A-Z0-9]{2,5}\/standings)$/; // nothing else is proxied
const ALLOWED_PARAMS = ["dateFrom", "dateTo", "competitions"];
const TTL_MATCHES = 10;   // seconds: live scores are at most 10s old
const TTL_STANDINGS = 300;

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
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
