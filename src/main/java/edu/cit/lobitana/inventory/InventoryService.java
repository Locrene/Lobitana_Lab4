package edu.cit.lobitana.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single place where stock moves (Lab 2).
 *
 * Every mutation publishes {@link StockLevelChangedEvent}, so any listener - a sales channel, the
 * auto-reorder - sees the change without polling. The service has no idea who caused the movement and no
 * idea who is listening.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryRepository items;
    private final ApplicationEventPublisher events;

    InventoryService(InventoryRepository items, ApplicationEventPublisher events) {
        this.items = items;
        this.events = events;
    }

    @Transactional
    public void ensureItem(String sku, int initialOnHand) {
        if (items.existsById(sku)) {
            return;
        }
        InventoryItem item = items.save(new InventoryItem(sku, initialOnHand));
        publish(item, "initial stock");
    }

    @Transactional(readOnly = true)
    public int available(String sku) {
        return items.findById(sku).map(InventoryItem::available).orElse(0);
    }

    @Transactional(readOnly = true)
    public List<InventoryItem> all() {
        return items.findAllByOrderBySkuAsc();
    }

    /**
     * All-or-nothing reservation. Either every line is reserved, or nothing is touched and the caller is
     * told exactly how many units of each SKU were missing.
     *
     * The SKU rows are locked in sorted order so two concurrent multi-line orders cannot deadlock, and a
     * flash sale simply queues on the row instead of failing.
     */
    @Transactional
    public ReservationResult reserveAll(Map<String, Integer> lines, String reason) {
        Map<String, Integer> wanted = new TreeMap<>(lines);
        Map<String, InventoryItem> loaded = new LinkedHashMap<>();
        Map<String, Integer> shortages = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> line : wanted.entrySet()) {
            Optional<InventoryItem> found = items.findForUpdate(line.getKey());
            if (found.isEmpty()) {
                shortages.put(line.getKey(), line.getValue());
                continue;
            }
            InventoryItem item = found.get();
            loaded.put(line.getKey(), item);
            int missing = line.getValue() - item.available();
            if (missing > 0) {
                shortages.put(line.getKey(), missing);
            }
        }

        if (!shortages.isEmpty()) {
            log.debug("reservation refused ({}): short of {}", reason, shortages);
            return ReservationResult.shortOf(shortages);
        }

        List<InventoryItem> changed = new ArrayList<>();
        for (Map.Entry<String, Integer> line : wanted.entrySet()) {
            InventoryItem item = loaded.get(line.getKey());
            item.reserve(line.getValue());
            changed.add(item);
        }
        items.saveAll(changed);
        changed.forEach(item -> publish(item, reason));
        return ReservationResult.success();
    }

    /** Give reserved units back, e.g. a cancellation. */
    @Transactional
    public void releaseAll(Map<String, Integer> lines, String reason) {
        List<InventoryItem> changed = new ArrayList<>();
        // Same sorted locking order as reserveAll, so a cancellation and an order cannot deadlock.
        new TreeMap<>(lines).forEach((sku, qty) -> items.findForUpdate(sku).ifPresent(item -> {
            item.release(qty);
            changed.add(item);
        }));
        items.saveAll(changed);
        changed.forEach(item -> publish(item, reason));
    }

    /** A supplier delivery arrived. */
    @Transactional
    public void receiveStock(String sku, int qty, String reason) {
        InventoryItem item = items.findForUpdate(sku).orElseGet(() -> new InventoryItem(sku, 0));
        item.receive(qty);
        items.save(item);
        publish(item, reason);
        log.info("received {} units of {} ({}); available now {}", qty, sku, reason, item.available());
    }

    private void publish(InventoryItem item, String reason) {
        events.publishEvent(new StockLevelChangedEvent(
                item.getSku(), item.available(), item.getOnHand(), item.getReserved(), reason));
    }
}
