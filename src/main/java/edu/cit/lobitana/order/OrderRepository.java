package edu.cit.lobitana.order;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

    List<CustomerOrder> findByStatusOrderByIdAsc(OrderStatus status);

    /** Backordered orders that are waiting for this SKU, oldest first (fair queue). */
    @Query("select distinct o from CustomerOrder o join o.lines l "
            + "where o.status = edu.cit.lobitana.order.OrderStatus.BACKORDERED and l.sku = :sku order by o.id asc")
    List<CustomerOrder> findBackorderedWaitingFor(String sku);
}
