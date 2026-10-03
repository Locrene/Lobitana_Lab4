package edu.cit.lobitana.inventory;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface InventoryRepository extends JpaRepository<InventoryItem, String> {

    List<InventoryItem> findAllByOrderBySkuAsc();

    /**
     * Read one SKU and hold its row until the transaction ends. Every stock movement goes through this, so
     * two movements of the same SKU queue up instead of one of them failing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryItem i where i.sku = :sku")
    Optional<InventoryItem> findForUpdate(String sku);
}
