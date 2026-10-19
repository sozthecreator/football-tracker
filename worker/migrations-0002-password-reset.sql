-- Run once on the existing database: npx wrangler d1 execute football-tracker --remote --file=migrations-0002-password-reset.sql
ALTER TABLE users ADD COLUMN email TEXT;
CREATE TABLE IF NOT EXISTS reset_requests (id TEXT PRIMARY KEY, username TEXT NOT NULL, created INTEGER NOT NULL, status TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS resets (hash TEXT PRIMARY KEY, username TEXT NOT NULL, created INTEGER NOT NULL, used INTEGER NOT NULL);
