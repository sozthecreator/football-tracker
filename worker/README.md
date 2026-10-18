# Football Tracker proxy (Cloudflare Worker)

Lets the apps get scores without each user needing a football-data.org key.
The key lives only on Cloudflare; responses are cached for 10s (matches) / 5 min (tables).

## Deploy (once, free plan is plenty)

1. Install Node.js, then in this folder: `npx wrangler login`
2. `npx wrangler secret put FOOTBALL_DATA_KEY` and paste your football-data.org key
3. `npx wrangler deploy`
4. It prints a URL like `https://football-tracker-proxy.<you>.workers.dev`.
   Put it in `local.properties` in the project root:

       workerUrl=https://football-tracker-proxy.<you>.workers.dev

   Then rebuild the desktop app (`./gradlew :desktop:installDist`). Downloaders need no key.

Test: `curl "https://football-tracker-proxy.<you>.workers.dev/v4/competitions/PL/standings"`
