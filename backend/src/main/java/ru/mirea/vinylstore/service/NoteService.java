package ru.mirea.vinylstore.service;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mirea.vinylstore.domain.Role;
import ru.mirea.vinylstore.domain.StoreOrder;
import ru.mirea.vinylstore.domain.OrderNote;
import ru.mirea.vinylstore.domain.UserAccount;
import ru.mirea.vinylstore.dto.NoteDtos.NoteRequest;
import ru.mirea.vinylstore.dto.NoteDtos.NoteResponse;
import ru.mirea.vinylstore.error.ConflictException;
import ru.mirea.vinylstore.error.NotFoundException;
import ru.mirea.vinylstore.repository.OrderNoteRepository;
import ru.mirea.vinylstore.repository.StoreOrderRepository;
import ru.mirea.vinylstore.security.CurrentUserService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Управляет заметками и авторскими правами на их изменение. */
@Service
public class NoteService {
    private final OrderNoteRepository notes;
    private final StoreOrderRepository orders;
    private final CurrentUserService currentUser;
    private final AuditService audit;

    public NoteService(OrderNoteRepository notes, StoreOrderRepository orders,
                       CurrentUserService currentUser, AuditService audit) {
        this.notes = notes;
        this.orders = orders;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<NoteResponse> list(UUID orderId) {
        requireStoreOrder(orderId);
        return notes.findByOrderIdOrderByPinnedDescCreatedAtDesc(orderId).stream().map(NoteService::toResponse).toList();
    }

    @Transactional
    public NoteResponse create(UUID orderId, NoteRequest request) {
        StoreOrder order = requireStoreOrder(orderId);
        UserAccount actor = currentUser.requireEntity();
        OrderNote note = new OrderNote(UUID.randomUUID(), order, actor, request.body().trim(), request.pinned());
        notes.saveAndFlush(note);
        audit.record("NOTE_CREATED", "ORDER_NOTE", note.getId(), "Добавлена заметка к заказу", Map.of("orderId", orderId));
        notes.flush();
        return toResponse(note);
    }

    @Transactional
    public NoteResponse update(UUID orderId, UUID noteId, NoteRequest request) {
        OrderNote note = requireNote(orderId, noteId);
        UserAccount actor = currentUser.requireEntity();
        assertOwnerOrAdmin(note, actor);
        if (request.version() == null || request.version() != note.getVersion())
            throw new ConflictException("Заметка уже изменена. Обновите данные");
        note.setBody(request.body().trim());
        note.setPinned(request.pinned());
        audit.record("NOTE_UPDATED", "ORDER_NOTE", noteId, "Изменена заметка", Map.of("orderId", orderId));
        notes.flush();
        return toResponse(note);
    }

    @Transactional
    public void delete(UUID orderId, UUID noteId) {
        OrderNote note = requireNote(orderId, noteId);
        UserAccount actor = currentUser.requireEntity();
        assertOwnerOrAdmin(note, actor);
        notes.delete(note);
        audit.record("NOTE_DELETED", "ORDER_NOTE", noteId, "Удалена заметка", Map.of("orderId", orderId));
    }

    private StoreOrder requireStoreOrder(UUID id) {
        return orders.findById(id).orElseThrow(() -> new NotFoundException("Заказ не найден"));
    }

    private OrderNote requireNote(UUID orderId, UUID noteId) {
        OrderNote note = notes.findById(noteId).orElseThrow(() -> new NotFoundException("Заметка не найдена"));
        if (!note.getStoreOrder().getId().equals(orderId)) throw new NotFoundException("Заметка не найдена");
        return note;
    }

    private static void assertOwnerOrAdmin(OrderNote note, UserAccount actor) {
        if (!note.getAuthor().getId().equals(actor.getId()) && !actor.getRoles().contains(Role.ADMIN)) {
            throw new AccessDeniedException("Only note author or administrator can modify it");
        }
    }

    private static NoteResponse toResponse(OrderNote note) {
        return new NoteResponse(note.getId(), note.getStoreOrder().getId(), note.getAuthor().getId(),
                note.getAuthor().getDisplayName(), note.getBody(), note.isPinned(),
                note.getCreatedAt(), note.getUpdatedAt(), note.getVersion());
    }
}
