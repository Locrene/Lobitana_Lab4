package edu.cit.lobitana.channel;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface ChannelOrderRepository extends JpaRepository<ChannelOrder, String> {

    Optional<ChannelOrder> findByShopOrderId(Long shopOrderId);

    /** Decided locally but the marketplace has not been told yet. */
    List<ChannelOrder> findByDecisionIsNotNullAndDecisionSentFalse();

    /** Claimed and placed in the shop, but the decision was never finished (a crash mid-way). */
    List<ChannelOrder> findByDecisionIsNull();

    List<ChannelOrder> findByCancellationRequestedTrueAndCancellationConfirmedFalse();

    List<ChannelOrder> findByResolutionIsNotNullAndResolutionSentFalse();

    boolean existsByDecisionIsNotNullAndDecisionSentFalse();

    boolean existsByCancellationRequestedTrueAndCancellationConfirmedFalse();

    boolean existsByResolutionIsNotNullAndResolutionSentFalse();

    long countByDecisionSentFalse();
}
