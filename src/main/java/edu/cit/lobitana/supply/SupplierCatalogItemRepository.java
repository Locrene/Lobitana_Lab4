package edu.cit.lobitana.supply;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierCatalogItemRepository extends JpaRepository<SupplierCatalogItem, String> {

    List<SupplierCatalogItem> findAllByOrderBySupplierSkuAsc();
}
