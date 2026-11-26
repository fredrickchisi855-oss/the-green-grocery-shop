# THE GREEN GROCERY SHOP

**FX DEVELOPMENT** — Android-first, offline-first point-of-sale and stock-management application.

## Project status
Phase 1 foundation is in progress. The current POS HTML is preserved at `The_Green_Grocery_Shop_POS__2_.html`. The initial local database schema is at `database/schema.sql`.

## Main requirements
- Android first; Windows host/client integration later.
- Local operation without internet.
- Owner username/password and cashier username/PIN.
- POS checkout, products, stock movements, spoilage, purchases/restocking, expenses, sales reports and receipts.
- USD and ZiG stored as separate currencies with an explicit exchange-rate record.
- Local Wi-Fi synchronisation planned for a later phase; no compulsory cloud subscription.

## Repository layout
- `The_Green_Grocery_Shop_POS__2_.html` — existing POS interface (preserved).
- `database/schema.sql` — initial SQLite schema.
- `docs/PHASE1_ANDROID_FOUNDATION.md` — architecture and implementation sequence.

## Important
GitLab is used for source control only. It is not the live shop database. The application is intended to run offline on the shop's devices.
