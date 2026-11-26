package ru.mirea.vinylstore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mirea.vinylstore.domain.OrderNote;

import java.util.List;
import java.util.UUID;

/** Persistence gateway for order notes. */
public interface OrderNoteRepository extends JpaRepository<OrderNote, UUID> {
    List<OrderNote> findByOrderIdOrderByPinnedDescCreatedAtDesc(UUID orderId);
}
