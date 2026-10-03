package edu.cit.lobitana.supply;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierMappingRepository extends JpaRepository<SupplierMapping, String> {

    Optional<SupplierMapping> findBySupplierSku(String supplierSku);
}
