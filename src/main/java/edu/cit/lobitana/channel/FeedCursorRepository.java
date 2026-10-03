package edu.cit.lobitana.channel;

import org.springframework.data.jpa.repository.JpaRepository;

interface FeedCursorRepository extends JpaRepository<FeedCursorState, Long> {
}
