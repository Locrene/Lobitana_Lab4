package edu.cit.lobitana.supply;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierPurchaseOrderRepository extends JpaRepository<SupplierPurchaseOrder, String> {

    List<SupplierPurchaseOrder> findByReceivedAtIsNull();

    List<SupplierPurchaseOrder> findBySkuAndReceivedAtIsNull(String sku);

    Optional<SupplierPurchaseOrder> findFirstByBuyerRef(String buyerRef);
}
