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

## Accounts (saved teams across devices)

Username + password accounts live in a Cloudflare D1 database (`football-tracker`, see `schema.sql`).
One-off setup: `npx wrangler d1 create football-tracker` (put the id in `wrangler.toml`), then
`npx wrangler d1 execute football-tracker --remote --file=schema.sql`, then `npx wrangler deploy`.
Passwords are salted PBKDF2 hashes; logins are rate limited (8 failures / 15 min per username and per address).
There's no email, so a forgotten password can't be reset. Users can delete their account from Settings.
