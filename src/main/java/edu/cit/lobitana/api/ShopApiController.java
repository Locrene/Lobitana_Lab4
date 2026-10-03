package edu.cit.lobitana.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.cit.lobitana.channel.ChannelStatus;
import edu.cit.lobitana.channel.MarketplaceChannel;
import edu.cit.lobitana.inventory.InventoryItem;
import edu.cit.lobitana.inventory.InventoryService;
import edu.cit.lobitana.order.CustomerOrder;
import edu.cit.lobitana.order.OrderLineRequest;
import edu.cit.lobitana.order.OrderService;
import edu.cit.lobitana.order.PlacementResult;

/**
 * The shop's own API - what the React UI from the earlier labs talks to.
 *
 * It matters for Rule 2: an order posted here goes through exactly the same {@link OrderService} call that
 * a Tiangge order goes through, and the stock it moves is published to the marketplace by the same domain
 * event. The only thing this controller knows about the marketplace is the read-only
 * {@link MarketplaceChannel} interface, used to show whether the shop is online.
 */
@RestController
@RequestMapping("/api")
class ShopApiController {

    private final OrderService orders;
    private final InventoryService inventory;
    private final MarketplaceChannel channel;

    ShopApiController(OrderService orders, InventoryService inventory, MarketplaceChannel channel) {
        this.orders = orders;
        this.inventory = inventory;
        this.channel = channel;
    }

    @GetMapping("/channel")
    ChannelStatus channel() {
        return channel.status();
    }

    @GetMapping("/inventory")
    List<Map<String, Object>> inventory() {
        return inventory.all().stream().map(ShopApiController::describe).toList();
    }

    @GetMapping("/orders")
    List<Map<String, Object>> orders() {
        return orders.recent().stream().map(ShopApiController::describe).toList();
    }

    /** Place an order from the shop's own UI. */
    @PostMapping("/orders")
    ResponseEntity<Map<String, Object>> place(@RequestBody PlaceOrderRequest request) {
        if (request == null || request.lines() == null || request.lines().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "at least one line is required"));
        }
        List<OrderLineRequest> lines = request.lines().stream()
                .map(line -> new OrderLineRequest(line.sku(), line.qty()))
                .toList();
        PlacementResult result = orders.place(lines, "shop UI");
        if (!result.reserved()) {
            orders.markRejected(result.orderId());
        }
        return ResponseEntity.ok(Map.of(
                "orderId", result.orderId(),
                "accepted", result.reserved(),
                "shortages", result.shortages()));
    }

    @PostMapping("/orders/{orderId}/cancel")
    ResponseEntity<Map<String, Object>> cancel(@PathVariable long orderId) {
        boolean restocked = orders.cancel(orderId, "cancelled in the shop UI");
        return ResponseEntity.ok(Map.of("orderId", orderId, "restocked", restocked));
    }

    private static Map<String, Object> describe(InventoryItem item) {
        return Map.of(
                "sku", item.getSku(),
                "onHand", item.getOnHand(),
                "reserved", item.getReserved(),
                "available", item.available());
    }

    private static Map<String, Object> describe(CustomerOrder order) {
        return Map.of(
                "orderId", order.getId(),
                "status", order.getStatus().name(),
                "createdAt", order.getCreatedAt().toString(),
                "lines", order.quantitiesBySku());
    }

    record PlaceOrderRequest(List<Line> lines) {

        record Line(String sku, int qty) {
        }
    }
}
