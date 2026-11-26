# Phase 1 — Android foundation

## Product identity and operating model
**THE GREEN GROCERY SHOP** is a combined grocery shop and tuckshop management system, not a supermarket-only POS. The interface and workflows must support quick counter sales of snacks, sweets, biscuits, drinks and single items alongside normal grocery products.

## Current source of truth
The existing POS interface remains the starting point:
`/The_Green_Grocery_Shop_POS__2_.html`.
Preserve and audit its working POS, stock, pricing, sales-history, spoilage, and barcode workflows before changing them.

## Target for the Android-first release
- Android application with the existing green-and-white POS interface.
- Local SQLite database; sales and stock operations work without internet.
- Owner login uses username + password; cashier login uses username + PIN.
- Owner/cashier permissions are enforced by application logic and database-backed identities, not a visible role toggle.
- USD and ZiG amounts are stored separately with currency codes. Never add currencies without an explicit conversion.
- Receipt numbers are unique; sale lines preserve item, selling unit, conversion factor, price, cost, currency and quantity snapshots.
- Stock is maintained in a base unit. Alternative selling/purchase units convert to base quantities. Example: buy one box containing 50 biscuits, then sell one biscuit at a time; each sale deducts one from the 50-piece stock.
- Support fast counter checkout, product shortcuts, barcode lookup, manual sale entry, discounts, split payments and receipts.
- Track expiry batches, damaged/spoiled stock, returns, stock adjustments and low-stock alerts.
- Include a customer debt book: credit sales create a debit entry; repayments create credit entries; balances and statements are separated by USD and ZiG.
- Include suppliers, purchases and purchase-item records for stock receiving and purchase-cost history.
- Stock changes record reason, user, timestamp and reference.
- A durable sync outbox is reserved for a later local-Wi-Fi Windows-host phase. An item is not shown as synced until the host acknowledges it.

## Database
The SQLite foundation is in `database/schema.sql`. It includes users, products, alternative product units, stock batches, sales, split payments, customers and customer account entries, suppliers, purchases, expenses, stock movements, audit history and the future LAN sync outbox.
The schema is a foundation, not a claim that the existing HTML currently uses SQLite. The current HTML must be audited and migrated carefully; existing browser-local data must not be silently discarded.

## Tuckshop-specific rules
1. A product has a base stock unit (for example piece, bottle, kg or litre).
2. Each pack/case/box option stores a conversion factor to the base unit.
3. The POS must show the selected selling unit and deduct the corresponding base quantity.
4. Purchase and sale units may differ. Example: purchase 2 cases × 24 drinks = 48 bottles; sell 3 bottles = 45 remaining.
5. Cashiers need a fast checkout layout with common tuckshop products reachable in very few taps.
6. Expiry tracking should support FIFO/earliest-expiry-first stock selection where batch tracking is enabled.

## Offline requirements
1. Bundle runtime dependencies locally. The current HTML references Tailwind CDN and Google Fonts; bundle assets before calling the app fully offline.
2. Replace browser-only persistence with a tested SQLite repository/bridge. Never store passwords or PINs in plaintext.
3. Checkout must be transactional: sale + sale lines + payment allocations + stock movements + quantity deductions + audit record, plus any customer credit entry, commit together.
4. Failed checkout must leave sales, balances and stock unchanged.
5. Keep USD and ZiG balances separate. Do not silently convert customer debt or mixed tender.
6. Backups must be exportable and restore-tested.

## Planned sequence
1. Android shell and local asset packaging.
2. SQLite repository, migration and tested stock-unit conversion.
3. Owner/cashier authentication and permission checks.
4. Tuckshop-speed POS, barcode/camera integration, receipts, expiry/stock controls, customer credit book and supplier purchasing.
5. Backup/restore and reports.
6. LAN synchronisation with a Windows host (later phase).

## Important scope boundary
GitLab stores source code and change history. It is not the shop database and is not required at runtime. Android must continue working when GitLab and the internet are unavailable. Local-Wi-Fi synchronisation with a Windows host is a later phase; if the host is offline, devices work locally and queue changes until acknowledgement.
