package ru.petstore.order.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import ru.petstore.order.domain.OutboxMessage;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    @Query("select m from OutboxMessage m where m.publishedAt is null and m.attempts < :maxAttempts "
            + "order by m.createdAt asc")
    List<OutboxMessage> findUnpublished(int maxAttempts, Limit limit);

    List<OutboxMessage> findByAggregateIdOrderByCreatedAtAsc(UUID aggregateId);

    @Modifying
    @Transactional
    @Query("update OutboxMessage m set m.publishedAt = :now, m.attempts = m.attempts + 1 "
            + "where m.id = :id and m.publishedAt is null")
    int markPublished(UUID id, Instant now);

    @Modifying
    @Transactional
    @Query("update OutboxMessage m set m.attempts = m.attempts + 1 where m.id = :id")
    int markAttempted(UUID id);
}
