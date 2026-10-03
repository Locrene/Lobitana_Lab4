package edu.cit.lobitana.channel;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface ListingRepository extends JpaRepository<Listing, String> {

    Optional<Listing> findBySku(String sku);

    List<Listing> findAllByOrderBySellerSkuAsc();
}
