package edu.cit.lobitana.channel;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import edu.cit.lobitana.supply.SupplierPort;

/**
 * Decides what to answer when the shop cannot fill an order right now (Task 4 and Task 6).
 *
 * BACKORDERED is only honest if stock is genuinely coming, and Tiangge checks exactly that: it wants an
 * open LegacySupply purchase order for every short item. So this asks the supplier adapter to make sure
 * stock is on its way for each short SKU - reusing an open purchase order if there is one - and only then
 * says BACKORDERED. If the supplier cannot be reached or refuses, the honest answer is REJECTED.
 */
@Component
class ShortageDecider {

    private static final Logger log = LoggerFactory.getLogger(ShortageDecider.class);

    private final SupplierPort supplier;

    ShortageDecider(SupplierPort supplier) {
        this.supplier = supplier;
    }

    String decide(String tianggeOrderId, String shortSkusCsv) {
        List<String> shortSkus = split(shortSkusCsv);
        if (shortSkus.isEmpty()) {
            return "REJECTED";
        }
        for (String sku : shortSkus) {
            if (supplier.ensureStockOnTheWay(sku, "Tiangge order " + tianggeOrderId + " is waiting").isEmpty()) {
                log.info("no stock on the way for {}, order {} must be rejected", sku, tianggeOrderId);
                return "REJECTED";
            }
        }
        log.info("order {} backordered, purchase orders are open for {}", tianggeOrderId, shortSkus);
        return "BACKORDERED";
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
    }
}
