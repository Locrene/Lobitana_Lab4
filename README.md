# Lab 4 — Tiangge marketplace channel

One Spring Boot application that runs the shop unattended: it publishes listings to Tiangge, decides on
every marketplace order within 60 seconds, keeps Tiangge's stock numbers equal to its own, restocks through
the LegacySupply adapter, and resolves backorders when deliveries arrive. Nothing outside this process ever
talks to Tiangge or LegacySupply.

## Run it

```bash
export STUDENT_ID=2023-00001           # your student id  (X-Client-Id / <ClientId>)
export API_KEY=LSK-XXXXXXXXXXXXXXXXXXXX # from https://legacysupply.onrender.com/key
mvn -DskipTests package
java -jar target/lab4-tiangge-1.0.0.jar
```

`./run.sh` (or `.\run.ps1` on Windows) does the same and reads `.env` first — copy `.env.example` to `.env`.
The key is only ever read from the environment; `.env` and `data/` are git-ignored.

Once the shop is live, two things matter. **Never delete `data/`**: it holds the feed cursor, the processed
events and the orders, and without it the next start reads the feed from the beginning and processes every
order again. And **always start from this folder** (the scripts do that themselves), because the database
path is relative. Stop the app with Ctrl+C; start it again the same way and it carries on from the cursor.

The one time `data/` should be empty is after "Reset my Tiangge record" on the self-check page. The reset
deletes the marketplace's orders, so a database that still holds them would keep waiting orders and outbox
rows for orders that no longer exist. Stop the app, wait at least 25 minutes (a delivery seen shortly before
a reset is re-checked against a stock history the reset has just cleared), press the reset, move `data/`
aside, then start.

Watch the startup line `=== instance <uuid> started at ... ===` — that UUID is what the self-check page
shows, because it is sent as `X-Client-Instance` on every call to both services.

Own endpoints, for the React UI and for looking at the shop while it runs:

| Method | Path | What |
| --- | --- | --- |
| GET | `/api/channel` | instance id, live flag, feed cursor, orders handled, anything still owed to Tiangge |
| GET | `/api/inventory` | on hand / reserved / available per SKU |
| GET | `/api/orders` | orders in the shop, whatever created them |
| POST | `/api/orders` | place an order: `{"lines":[{"sku":"SH-ABC-1234","qty":2}]}` |
| POST | `/api/orders/{id}/cancel` | cancel and restock |

## How it is put together

```
edu.cit.lobitana
├── common      instance id, credentials from the environment, retry with backoff, thread pools
├── catalog     Product
├── inventory   InventoryItem, InventoryService, StockLevelChangedEvent      (Lab 2)
├── order       CustomerOrder, OrderService, BackorderResolver               (Lab 2)
├── supply      SupplierPort + the LegacySupply XML adapter, auto-reorder,
│               delivery tracking                                           (Lab 3)
├── channel     MarketplaceChannel (the only public door) + everything Tiangge (Lab 4)
├── seed        builds the shop's products from the real LegacySupply catalog
└── api         the shop's own REST API
```

Rule 1 — the channel package has exactly two public types, `MarketplaceChannel` and `ChannelStatus`.
The HTTP client, the JSON records, the feed poller, the translator, the entities and their repositories are
all package-private, which is why they live in one flat package: a subpackage would have forced them public.

Rule 2 — `order` and `inventory` contain no marketplace identifier and no import from `channel`. The
correlation between a Tiangge order and a shop order is kept in the channel's own `ChannelOrder` table.
An order from the React UI and an order from Tiangge both end up in `OrderService.place(...)`.

```bash
# both come back empty: no marketplace vocabulary, no import of the channel package
grep -rin "tiangge\|marketplace" src/main/java/edu/cit/lobitana/order src/main/java/edu/cit/lobitana/inventory
grep -rn "import edu.cit.lobitana.channel" src/main/java/edu/cit/lobitana/order src/main/java/edu/cit/lobitana/inventory
# only MarketplaceChannel and ChannelStatus are public in the channel package
grep -rn "^public" src/main/java/edu/cit/lobitana/channel
```

## Where each task lives

| Task | Behaviour | Code |
| --- | --- | --- |
| 1 | new UUID per start, sent on every call, heartbeat first and then on the marketplace's own rhythm | `common/AppInstance`, `channel/HeartbeatPublisher`, both HTTP clients |
| 2 | publish ≥3 listings, each naming its SupplierSku | `channel/ListingPublisher`, `seed/ShopCatalogSetup`, `supply/SupplierMapping` |
| 3 | any stock change reaches Tiangge, event-driven, no timer | `inventory/StockLevelChangedEvent` → `channel/StockSyncListener` |
| 4 | poll the feed, decide within 60s, exactly one shop order per Tiangge order, durable cursor | `channel/FeedPoller`, `FeedEventProcessor`, `ChannelBookkeeper`, `FeedCursorState`, `ProcessedFeedEvent` |
| 5 | cancellations run through the Lab 2 cancellation path and are confirmed | `order/OrderService#cancel`, `ChannelBookkeeper#recordCancellation`, `DecisionOutbox` |
| 6 | auto-reorder, BACKORDERED only when stock is really coming, resolve on delivery | `supply/AutoReorderService`, `DeliveryTracker`, `channel/ShortageDecider`, `order/BackorderResolver`, `channel/BackorderNotifier` |

## The four decisions that make it survive unattended

**The cursor only moves because work was committed.** Processing one feed event writes the shop order, the
`ChannelOrder` link, the `ProcessedFeedEvent` row and the new cursor value in a single transaction. Kill the
process anywhere and the feed simply redelivers from the last fully handled event. The cursor is per event,
not per page, so a failure halfway through a page loses nothing.

**The Tiangge order id is the idempotency token.** It is the primary key of `ChannelOrder`. A redelivered
order finds the row and re-sends the same decision instead of becoming a second shop order.

**Deciding and telling are separate.** The shop commits its decision first, then delivers it; `DecisionOutbox`
delivers on a few courier threads (so one slow answer does not hold up the next order) and re-sends anything
still owed every three seconds. The marketplace documents these calls as safe to retry
with identical content, and the content is read back from the committed row, so a retry is always identical.
A marketplace outage delays messages instead of losing them, and a restart finishes what the previous
process started.

**Stock is published by events, never by a timer.** `InventoryService` publishes `StockLevelChangedEvent` for
every movement; the channel listens after commit and hands the SKU to one worker thread, and the Lab 3
auto-reorder listens to the same event. The worker sends the quantity that is available at the moment it
sends, after the decision or cancellation confirmation that caused the change, and puts a failed update back
in its queue - so numbers cannot overtake each other and an outage cannot leave a stale one behind.
Reservation is all-or-nothing with the inventory rows locked, so a flash sale cannot oversell.

**Backorders are honest and get the delivery first.** Before answering BACKORDERED the adapter checks that
the purchase order it relies on is still open at LegacySupply (and books it in on the spot if it has already
arrived). When a delivery is booked, the waiting orders are filled inside that same transaction, oldest
first, before any new order can take the units.

## Opening numbers

`ShopCatalogSetup` reads the live LegacySupply catalog (every partner's is different, and Tiangge validates
`supplierSku` against it), takes the first four items, and sells them as `SH-<SupplierSku>` (listed on Tiangge
as `LOB-SH-<SupplierSku>`) with 6 units on hand, a reorder point of 4 and one supplier pack per purchase order. Small numbers on purpose: stock runs
down, the auto-reorder fires, an order gets backordered, a delivery arrives and fills it — the whole loop
happens inside a lab session. Change them at the top of that class.

## Before submitting

```bash
git add -A && git commit -m "Lab 4: Tiangge marketplace channel"
git tag lab4-final
git push && git push --tags
```

`REFLECTION.md` needs the three questions from your own self-check page pasted in, with answers that match
your logs.
