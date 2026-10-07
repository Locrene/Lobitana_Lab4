# Reflection — Lab 4

The three questions below are copied from the Marketplace section of my self-check page
(record for 23-5497-731, printed 10/7/2026 10:18 PM). Log lines are quoted from `logs/lab4.log`
of that run; times are local (UTC+8).

## 1. Event evt_078e30b65e6a3e52 (order TG-M2XYTP) reached your application twice, as seq 210 and seq 213, and you processed it once. Show the code and the stored data that made the second delivery harmless, and explain what would happen if your application restarted between the two.

`FeedEventProcessor.process` first asks `ChannelBookkeeper.alreadyProcessed(eventId)`, which is a
primary-key lookup in the `channel_processed_events` table; if the row exists it only calls
`advanceCursor(seq)` and returns. The first copy (seq 210) was handled at 21:59:32 — my log shows
`Tiangge order TG-M2XYTP became shop order 2` exactly once — and `ChannelBookkeeper.finaliseDecision`
wrote the `ProcessedFeedEvent` row for `evt_078e30b65e6a3e52` in the same transaction as the decision
and the new cursor value. When the copy with seq 213 arrived in the poll at 22:00:40
(`feed returned 2 event(s) after cursor 211`), that row was found, so nothing was placed or decided
again and the cursor simply moved to 213, which is why the next line reads `after cursor 213`.
A second guard sits behind it: the Tiangge order id is the primary key of `channel_orders`, so even a
redelivery under a new event id would find shop order 2 and re-send the same decision instead of
creating another order. A restart between the two copies would change nothing, because both tables and
the cursor live in the file-backed H2 database (`./data/lab4`), not in memory; the new process would
read from the stored cursor and meet the same row.

## 2. Order TG-M2XYTP was backordered at 21:59:32 and accepted at 22:05:40, after PO-104178 was delivered at 22:05:32. Trace how the delivery reached your Inventory and what then resumed the backordered order.

The order needed 8 units of `SH-GJJ-4152` and was 2 short, so `ShortageDecider` asked
`SupplierPort.ensureStockOnTheWay`, and my LegacySupply adapter placed PO-104178 (1 case) before the
answer BACKORDERED was sent (`purchase order PO-104178 placed for SH-GJJ-4152 ... because Tiangge order
TG-M2XYTP is waiting`). LegacySupply never calls back, so `DeliveryTracker.pollOpenOrders` asked for the
status every 15 seconds; it logged status 30 at 22:03:36 and saw status 40 at 22:05:40, eight seconds
after LegacySupply delivered. `DeliveryBookkeeper.bookDelivery` then called
`InventoryService.receiveStock` for 12 units (1 case times PackSize 12) and published
`PurchaseOrderDeliveredEvent`. `BackorderResolver.onDelivery` listens to that event inside the same
transaction (`delivery of 12 units of SH-GJJ-4152 arrived, retrying 2 backorder(s)`), so
`OrderService.tryFulfilBackorder` reserved the units for shop order 2 before any new order could take
them, and published `BackorderResolvedEvent`. `BackorderNotifier` turned that into the resolution
(`shop order 2 (Tiangge order TG-M2XYTP) resolves to ACCEPTED`), `DecisionOutbox` sent it at 22:05:40.751,
and only then did `StockSyncListener` publish the new number (`LOB-SH-GJJ-4152 is now 2` at 22:05:41.141).
The Order module did all of this without knowing a marketplace exists; the channel only translated the
outcome.

## 3. During your restart test your application was down for about 110 seconds while 3 orders arrived. How did the restarted application find those orders, and how did it avoid handling earlier ones again?

I stopped instance `f20e7846-...` at 22:01:24, and instance `d58cbd11-...` started at 22:02:52 and had its
first heartbeat acknowledged at 22:03:00. The new instance did not need to be told anything: `FeedPoller`
reads the position from the single row of `channel_feed_cursor` (`ChannelBookkeeper.currentCursor`), and
its first feed call was `feed returned 2 event(s) after cursor 214`, not `after cursor 0`, followed by
`after cursor 216` and `after cursor 217`. Because the feed only returns events with a higher sequence
number than the cursor, everything up to 214 was never requested again, and the missed orders were
simply the next events in line. The cursor can be trusted because it is written in the same transaction
as the shop order, the `channel_orders` link and the processed-event row, so it can never be ahead of
work that was not committed. If an earlier event had been redelivered anyway, the two primary keys from
question 1 would have stopped it. One honest cost of being down: my log shows
`the decision for order TG-X4DY6C went out after its deadline`, because that order was placed at 22:01:58
while nothing was running and could only be decided at 22:03:03.
