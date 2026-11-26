# Cloudflare backend

Worker URL: https://dawn-violet-34da.the-green-grocery-shop.workers.dev

D1 binding: DB -> green-grocery-shop-db.

## API

- `GET /health` checks the deployed Worker and D1 connection.
- `POST /api/pair` consumes a one-time, expiring pairing code and returns a device token once.
- `POST /api/sync/push` accepts authenticated, idempotency-keyed entity operations with optimistic version checks.
- `GET /api/sync/pull` returns the shop's versioned records to an authenticated device.
- Pairing codes are stored as SHA-256 hashes. Device tokens are stored as SHA-256 hashes. Android encrypts its token using an Android Keystore AES-GCM key.
- Owner passwords and cashier PINs are never sent to Cloudflare.
- Unauthenticated sync requests are rejected.

## Offline-first behavior

Android saves changes locally first. The local SQLite outbox queues product, sale, stock-movement, and exchange-rate changes. It retries when the app has connectivity and pulls remote changes after pushing local operations. Record versions detect concurrent edits; conflicts remain local and are shown in the sync status rather than silently overwriting newer server data.

The current UI still uses its local JSON state as the presentation model. The sync outbox maps the supported state collections to versioned Cloudflare records; normalized local tables for every business domain are not yet the sync source of truth.

## Production setup

- Database: `green-grocery-shop-db`
- Worker: `dawn-violet-34da`
- D1 schema includes shops, devices, one-time pairing codes, sync records, and operation receipts.
- A shop row and one-time pairing code must be provisioned by an authorized owner action before the first Android device can pair.
- Never commit a raw pairing code or device token.
