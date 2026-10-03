# Reflection — Lab 4

> The three questions under **Marketplace** on https://legacysupply.onrender.com/verify are generated from
> your own traffic, so they can only be copied from your own page. Replace each heading below with the exact
> question text, and replace every `<...>` with the real value from your run (your instance ids, order ids,
> PO numbers, timestamps). The answers below describe what this application actually does — check each claim
> against your own log before you hand it in.

## Question 1 — <paste the first Marketplace question here>

<Answer in 3-6 sentences, grounded in your log.>

Material to draw on: the feed is the only way this shop learns anything, and `channel/FeedPoller` reads it
every four seconds from the cursor stored in the `channel_feed_cursor` table. Each event is handled by
`FeedEventProcessor`, and the decision for a new order is written in the same transaction as the shop order
itself (`ChannelBookkeeper.placeAndRecord`), together with the `ProcessedFeedEvent` row and the new cursor
value — so the cursor can never be ahead of the work it describes. In my run, the feed returned
`<n>` events at `<timestamp>` and order `<tiangge order id>` was decided `<decision>` as shop order
`<id>` within `<seconds>` seconds of `placedAt`. Because the decision is committed before it is sent and
re-sent by `DecisionOutbox` until Tiangge accepts it, a slow marketplace cost me latency but never a lost
decision.

## Question 2 — <paste the second Marketplace question here>

<Answer in 3-6 sentences, grounded in your log.>

Material to draw on: duplicates are stopped twice. The `eventId` is the primary key of
`channel_processed_events`, so a repeat of the same event only moves the cursor; the Tiangge `orderId` is the
primary key of `channel_orders`, so even a redelivery carrying a new `eventId` finds the row and re-sends the
same decision instead of creating a second shop order — my log line
`Tiangge order <id> was delivered again, keeping the one shop order we already made` is exactly that case.
The restart test relies on the same two tables plus the file-backed H2 database: the second instance
(`<new instance id>`) started at `<timestamp>` and its first feed call was `after=<cursor>`, not `after=0`,
so it picked up only the `<n>` orders placed while I was down. Stock is never republished on a timer —
`InventoryService` publishes `StockLevelChangedEvent` on every movement and `StockSyncListener` turns it
into one `PUT /stock` after the inventory transaction commits.

## Question 3 — <paste the third Marketplace question here>

<Answer in 3-6 sentences, grounded in your log.>

Material to draw on: BACKORDERED is only honest when stock is genuinely coming, which is also what Tiangge
checks, so `ShortageDecider` asks the Lab 3 adapter to make sure a purchase order is open for every short
SKU before answering — and answers REJECTED when the supplier cannot be reached or refuses. In my run,
order `<tiangge order id>` was short of `<sku>` by `<n>` units, `<PO number>` was placed through
`LegacySupplyAdapter` (carrying `X-Client-Instance: <instance id>` and an `X-Request-Id` derived from my
buyer reference so a retry could not duplicate it), and `DeliveryTracker` saw status 40 at `<timestamp>`.
Booking the delivery raised `PurchaseOrderDeliveredEvent`, `order/BackorderResolver` retried the waiting
order, and `BackorderNotifier` resolved it to ACCEPTED on the marketplace at `<timestamp>`. The Order module
reached that answer without knowing a marketplace exists; the channel only translated the outcome.
