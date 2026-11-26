package ru.mirea.vinylstore.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mirea.vinylstore.config.AppProperties;
import ru.mirea.vinylstore.domain.StoreOrder;
import ru.mirea.vinylstore.domain.OrderStatus;
import ru.mirea.vinylstore.domain.VinylRecord;
import ru.mirea.vinylstore.domain.UserAccount;
import ru.mirea.vinylstore.dto.StoreOrderDtos.BulkAction;
import ru.mirea.vinylstore.dto.StoreOrderDtos.BulkFailure;
import ru.mirea.vinylstore.dto.StoreOrderDtos.BulkRequest;
import ru.mirea.vinylstore.dto.StoreOrderDtos.BulkResult;
import ru.mirea.vinylstore.dto.StoreOrderDtos.StoreOrderRequest;
import ru.mirea.vinylstore.dto.StoreOrderDtos.StoreOrderResponse;
import ru.mirea.vinylstore.error.BadRequestException;
import ru.mirea.vinylstore.error.ConflictException;
import ru.mirea.vinylstore.error.NotFoundException;
import ru.mirea.vinylstore.filter.SmartFilterParser;
import ru.mirea.vinylstore.filter.StoreOrderFilter;
import ru.mirea.vinylstore.filter.StoreOrderSpecifications;
import ru.mirea.vinylstore.repository.OrderNoteRepository;
import ru.mirea.vinylstore.repository.StoreOrderRepository;
import ru.mirea.vinylstore.repository.VinylRecordRepository;
import ru.mirea.vinylstore.security.CurrentUserService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Основной прикладной сервис карточек заказов. */
@Service
public class StoreOrderService {
    private final StoreOrderRepository orders;
    private final OrderNoteRepository notes;
    private final VinylRecordRepository records;
    private final CurrentUserService currentUser;
    private final SmartFilterParser parser;
    private final StoreOrderMapper mapper;
    private final PriorityCalculator priorityCalculator;
    private final AuditService audit;
    private final AppProperties properties;

    public StoreOrderService(StoreOrderRepository orders, OrderNoteRepository notes,
                          VinylRecordRepository records, CurrentUserService currentUser,
                          SmartFilterParser parser, StoreOrderMapper mapper,
                          PriorityCalculator priorityCalculator, AuditService audit,
                          AppProperties properties) {
        this.orders = orders;
        this.notes = notes;
        this.records = records;
        this.currentUser = currentUser;
        this.parser = parser;
        this.mapper = mapper;
        this.priorityCalculator = priorityCalculator;
        this.audit = audit;
        this.properties = properties;
    }

    /** Выполняет безопасный умный поиск с пагинацией. */
    @Transactional(readOnly = true)
    public Page<StoreOrderResponse> search(String query, Pageable pageable) {
        StoreOrderFilter filter = parser.parse(query);
        var spec=StoreOrderSpecifications.from(filter);
        UserAccount actor=currentUser.requireEntity();
        if (!actor.getRoles().contains(ru.mirea.vinylstore.domain.Role.ADMIN) && !actor.getRoles().contains(ru.mirea.vinylstore.domain.Role.MANAGER)) {
            spec=spec.and((root,criteria,cb)->cb.equal(root.get("createdBy").get("id"),actor.getId()));
        }
        return orders.findAll(spec, pageable).map(mapper::toResponse);
    }

    /** Возвращает одну карточку независимо от архивного статуса. */
    @Transactional(readOnly = true)
    public StoreOrderResponse get(UUID id) {
        return mapper.toResponse(require(id));
    }

    /** Создаёт карточку и рассчитывает приоритет на сервере. */
    @Transactional
    public StoreOrderResponse create(StoreOrderRequest request) {
        UserAccount actor = currentUser.requireEntity();
        validateExternalId(request.externalId(), null);
        StoreOrder order = new StoreOrder(UUID.randomUUID(), normalizeRequired(request.albumTitle()),
                normalizeRequired(request.artist()), actor);
        apply(order, request, actor);
        orders.saveAndFlush(order);
        audit.record("ORDER_CREATED", "ORDER", order.getId(),
                "Создана карточка заказа " + displayName(order), Map.of());
        orders.flush();
        return mapper.toResponse(order);
    }

    /** Изменяет карточку только при совпадении optimistic version. */
    @Transactional
    public StoreOrderResponse update(UUID id, StoreOrderRequest request) {
        StoreOrder order = require(id);
        verifyVersion(request.version(), order.getVersion());
        validateExternalId(request.externalId(), id);
        UserAccount actor = currentUser.requireEntity();
        apply(order, request, actor);
        audit.record("ORDER_UPDATED", "ORDER", id,
                "Изменена карточка заказа " + displayName(order), Map.of("version", order.getVersion()));
        orders.flush();
        return mapper.toResponse(order);
    }

    /** Архивирует карточку вместо физического удаления. */
    @Transactional
    public StoreOrderResponse archive(UUID id) {
        StoreOrder order = require(id);
        archiveInternal(order, currentUser.requireEntity());
        audit.record("ORDER_ARCHIVED", "ORDER", id,
                "Карточка заказа перемещена в архив", Map.of());
        orders.flush();
        return mapper.toResponse(order);
    }

    /** Возвращает карточку из архива. */
    @Transactional
    public StoreOrderResponse restore(UUID id) {
        StoreOrder order = require(id);
        order.setArchived(false);
        order.setArchivedAt(null);
        order.setArchivedBy(null);
        order.setUpdatedBy(currentUser.requireEntity());
        audit.record("ORDER_RESTORED", "ORDER", id,
                "Карточка заказа восстановлена из архива", Map.of());
        orders.flush();
        return mapper.toResponse(order);
    }

    /**
     * Безвозвратно удаляет запись только при включённом feature flag и явном подтверждении.
     */
    @Transactional
    public void permanentDelete(UUID id, String confirmation) {
        if (!properties.permanentDeleteEnabled()) {
            throw new BadRequestException("Безвозвратное удаление отключено конфигурацией сервера");
        }
        if (!"PERMANENTLY_DELETE".equals(confirmation)) {
            throw new BadRequestException("Для удаления требуется заголовок X-Confirm-Delete: PERMANENTLY_DELETE");
        }
        StoreOrder order = require(id);
        String name = displayName(order);
        orders.delete(order);
        audit.record("ORDER_PERMANENTLY_DELETED", "ORDER", id,
                "Безвозвратно удалена карточка " + name, Map.of());
    }

    /** Выполняет ограниченную массовую операцию и сообщает ошибки отдельных UUID. */
    @Transactional
    public BulkResult bulk(BulkRequest request) {
        validateBulkRequest(request);
        UserAccount actor = currentUser.requireEntity();
        int succeeded = 0;
        var failures = new ArrayList<BulkFailure>();
        for (UUID id : request.ids()) {
            StoreOrder order = orders.findById(id).orElse(null);
            if (order == null) {
                failures.add(new BulkFailure(id, "Заказ не найден"));
                continue;
            }
            try {
                applyBulk(order, request, actor);
                succeeded++;
            } catch (RuntimeException ex) {
                failures.add(new BulkFailure(id, ex.getMessage()));
            }
        }
        audit.record("ORDER_BULK_" + request.action().name(), "ORDER", null,
                "Выполнена массовая операция", Map.of("requested", request.ids().size(), "succeeded", succeeded));
        return new BulkResult(request.ids().size(), succeeded, failures);
    }

    /** Используется CSV-сервисом внутри транзакции без дублирования правил. */
    StoreOrder upsertFromImport(StoreOrder existing, StoreOrderRequest request, UserAccount actor) {
        if (existing == null) {
            StoreOrder created = new StoreOrder(UUID.randomUUID(), normalizeRequired(request.albumTitle()),
                    normalizeRequired(request.artist()), actor);
            apply(created, request, actor);
            return orders.save(created);
        }
        apply(existing, request, actor);
        return existing;
    }

    private void apply(StoreOrder order, StoreOrderRequest request, UserAccount actor) {
        order.setQuantity(request.quantity() == null ? 1 : request.quantity());
        order.setExternalId(normalizeNullable(request.externalId()));
        order.setAlbumTitle(normalizeRequired(request.albumTitle()));
        order.setArtist(normalizeRequired(request.artist()));
        order.setCustomerName(normalizeNullable(request.customerName()));
        order.setEmail(normalizeEmail(request.email()));
        order.setPhone(normalizeNullable(request.phone()));
        order.setOrderedOn(request.orderedOn());
        order.setRecord(resolveRecord(request.recordId()));
        order.setStatus(request.status());
        order.setTotalPrice(request.totalPrice());
        order.setPaid(request.paid());
        order.setTags(normalizeTags(request.tags()));
        order.setPriorityLevel(priorityCalculator.calculate(request.status(), request.totalPrice(), request.paid()));
        order.setUpdatedBy(actor);
    }

    private void applyBulk(StoreOrder order, BulkRequest request, UserAccount actor) {
        switch (request.action()) {
            case ARCHIVE -> archiveInternal(order, actor);
            case RESTORE -> {
                order.setArchived(false);
                order.setArchivedAt(null);
                order.setArchivedBy(null);
                order.setUpdatedBy(actor);
            }
            case SET_STATUS -> {
                order.setStatus(request.status());
                order.setPriorityLevel(priorityCalculator.calculate(order.getStatus(), order.getTotalPrice(), order.isPaid()));
                order.setUpdatedBy(actor);
            }
            case ADD_TAG -> {
                Set<String> tags = new LinkedHashSet<>(order.getTags());
                if(tags.size()>=20 && !tags.contains(normalizeTag(request.tag()))) throw new BadRequestException("Допустимо не более 20 тегов");
                tags.add(normalizeTag(request.tag()));
                order.setTags(tags);
                order.setUpdatedBy(actor);
            }
            case REMOVE_TAG -> {
                Set<String> tags = new LinkedHashSet<>(order.getTags());
                tags.remove(normalizeTag(request.tag()));
                order.setTags(tags);
                order.setUpdatedBy(actor);
            }
        }
    }

    private static void validateBulkRequest(BulkRequest request) {
        if (request.action() == BulkAction.SET_STATUS && request.status() == null) {
            throw new BadRequestException("Для SET_STATUS требуется status");
        }
        if ((request.action() == BulkAction.ADD_TAG || request.action() == BulkAction.REMOVE_TAG)
                && (request.tag() == null || request.tag().isBlank())) {
            throw new BadRequestException("Для операции с тегом требуется tag");
        }
    }

    private static void archiveInternal(StoreOrder order, UserAccount actor) {
        order.setArchived(true);
        order.setArchivedAt(Instant.now());
        order.setArchivedBy(actor);
        order.setUpdatedBy(actor);
    }

    private StoreOrder require(UUID id) {
        StoreOrder order=orders.findById(id).orElseThrow(() -> new NotFoundException("Заказ не найден"));
        UserAccount actor=currentUser.requireEntity();
        if (!actor.getRoles().contains(ru.mirea.vinylstore.domain.Role.ADMIN) && !actor.getRoles().contains(ru.mirea.vinylstore.domain.Role.MANAGER)
            && (order.getCreatedBy()==null || !order.getCreatedBy().getId().equals(actor.getId()))) {
            throw new org.springframework.security.access.AccessDeniedException("Нет доступа к заказу");
        }
        return order;
    }

    private VinylRecord resolveRecord(UUID id) {
        if (id == null) return null;
        VinylRecord record = records.findById(id).orElseThrow(() -> new BadRequestException("Виниловая пластинка не найдена"));
        if (!record.isActive()) throw new BadRequestException("Нельзя назначить неактивную пластинку");
        return record;
    }

    private void validateExternalId(String externalId, UUID currentId) {
        String normalized = normalizeNullable(externalId);
        if (normalized == null) return;
        boolean exists = currentId == null
                ? orders.existsByExternalIdIgnoreCase(normalized)
                : orders.existsByExternalIdIgnoreCaseAndIdNot(normalized, currentId);
        if (exists) throw new ConflictException("Внешний идентификатор уже используется");
    }

    private static void verifyVersion(Long requested, long actual) {
        if (requested == null) throw new BadRequestException("Для изменения требуется поле version");
        if (requested != actual) throw new ConflictException("Карточка уже изменена. Обновите данные и повторите операцию");
    }

    private static Set<String> normalizeTags(Set<String> tags) {
        if (tags == null) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String tag : tags) result.add(normalizeTag(tag));
        if (result.size() > 20) throw new BadRequestException("Допустимо не более 20 тегов");
        return result;
    }

    private static String normalizeTag(String value) {
        String normalized = normalizeRequired(value).toLowerCase(Locale.ROOT);
        if (normalized.length() > 50) throw new BadRequestException("Тег длиннее 50 символов");
        return normalized;
    }

    private static String normalizeEmail(String value) {
        String normalized = normalizeNullable(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizeRequired(String value) {
        if (value == null || value.trim().isEmpty()) throw new BadRequestException("Обязательное текстовое поле не заполнено");
        return value.trim().replaceAll("\\s+", " ");
    }

    private static String normalizeNullable(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim().replaceAll("\\s+", " ");
    }

    private static String displayName(StoreOrder order) {
        return order.getArtist() + " " + order.getAlbumTitle();
    }
}
