package edu.cit.lobitana.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * An order in this shop, wherever it came from. It carries no identifiers belonging to anyone else - no
 * sales channel ids, no supplier ids - only this shop's own SKUs and quantities.
 */
@Entity
@Table(name = "orders")
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private OrderStatus status = OrderStatus.NEW;

    private Instant createdAt = Instant.now();

    private Instant decidedAt;

    /** Two threads deciding the same order (a cancellation and a backorder fill, say) cannot both win. */
    @Version
    private long version;

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    @JoinColumn(name = "order_id")
    private List<OrderLine> lines = new ArrayList<>();

    protected CustomerOrder() {
        // for JPA
    }

    CustomerOrder(List<OrderLine> lines) {
        this.lines = new ArrayList<>(lines);
    }

    public Long getId() {
        return id;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public List<OrderLine> getLines() {
        return List.copyOf(lines);
    }

    /** sku -> total quantity, merging repeated lines for the same sku. */
    public Map<String, Integer> quantitiesBySku() {
        Map<String, Integer> merged = new LinkedHashMap<>();
        lines.forEach(line -> merged.merge(line.getSku(), line.getQty(), Integer::sum));
        return merged;
    }

    public boolean hasSku(String sku) {
        return lines.stream().anyMatch(line -> line.getSku().equals(sku));
    }

    void moveTo(OrderStatus next) {
        this.status = next;
        this.decidedAt = Instant.now();
    }
}
