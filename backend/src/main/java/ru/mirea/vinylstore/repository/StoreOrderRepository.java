package ru.mirea.vinylstore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import ru.mirea.vinylstore.domain.StoreOrder;
import ru.mirea.vinylstore.domain.OrderStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence gateway for orders, smart specifications and compact analytics. */
public interface StoreOrderRepository extends JpaRepository<StoreOrder, UUID>, JpaSpecificationExecutor<StoreOrder> {
    boolean existsByExternalIdIgnoreCase(String externalId);
    boolean existsByExternalIdIgnoreCaseAndIdNot(String externalId, UUID id);
    Optional<StoreOrder> findByExternalIdIgnoreCase(String externalId);
    List<StoreOrder> findByCreatedByIdOrderByCreatedAtDesc(UUID id);
    long countByArchivedFalse();
    long countByArchivedTrue();
    long countByArchivedFalseAndPaidTrue();
    long countByArchivedFalseAndStatus(OrderStatus status);

    @Query("select coalesce(avg(s.totalPrice), 0) from StoreOrder s where s.archived = false and s.totalPrice is not null")
    BigDecimal totalPriceForActive();

    @Query("select coalesce(g.code, 'Без пластинки'), count(s) from StoreOrder s left join s.record g " +
           "where s.archived = false group by g.code order by count(s) desc")
    List<Object[]> countActiveByRecord();

    @Query("select s.status, count(s) from StoreOrder s where s.archived = false group by s.status order by count(s) desc")
    List<Object[]> countActiveByStatus();

    @Query("select s.priorityLevel, count(s) from StoreOrder s where s.archived = false group by s.priorityLevel order by count(s) desc")
    List<Object[]> countActiveByPriority();
}
