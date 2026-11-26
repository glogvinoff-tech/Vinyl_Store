package ru.mirea.vinylstore.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ru.mirea.vinylstore.domain.PriorityLevel;
import ru.mirea.vinylstore.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** DTO-контракты заказов и массовых операций. */
public final class StoreOrderDtos {
    private StoreOrderDtos() {
    }

    /** Входная модель создания и изменения карточки. */
    public record StoreOrderRequest(
            @NotBlank @Size(max = 100) String externalId,
            @NotBlank @Size(max = 200) String albumTitle,
            @NotBlank @Size(max = 120) String artist,
            @NotBlank @Size(max = 100) String customerName,
            @Email @Size(max = 254) String email,
            @Pattern(regexp = "^\\+7\\d{10}$", message = "некорректный телефон") String phone,
            @jakarta.validation.constraints.PastOrPresent LocalDate orderedOn,
            @NotNull UUID recordId,
            @NotNull OrderStatus status,
            @NotNull @DecimalMin("0.01") @DecimalMax("9999999999.99") BigDecimal totalPrice,
            boolean paid,
            @Size(max = 20) Set<@NotBlank @Size(max = 50) String> tags,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(1000) Integer quantity,
            Long version) {
    }

    /** Выходная карточка заказа. */
    public record StoreOrderResponse(
            UUID id,
            String externalId,
            String albumTitle,
            String artist,
            String customerName,
            String displayTitle,
            String email,
            String phone,
            LocalDate orderedOn,
            UUID recordId,
            String recordCode,
            OrderStatus status,
            BigDecimal totalPrice,
            boolean paid,
            PriorityLevel priorityLevel,
            boolean archived,
            Instant archivedAt,
            Set<String> tags,
            Instant createdAt,
            Instant updatedAt,
            int quantity,
            long version) {
    }

    /** Поддерживаемые массовые действия. */
    public enum BulkAction { ARCHIVE, RESTORE, SET_STATUS, ADD_TAG, REMOVE_TAG }

    /** Запрос массового изменения. */
    public record BulkRequest(
            @NotNull @Size(min = 1, max = 500) Set<UUID> ids,
            @NotNull BulkAction action,
            OrderStatus status,
            @Size(max = 50) String tag) {
    }

    /** Результат массовой операции с явным списком ошибок. */
    public record BulkResult(int requested, int succeeded, List<BulkFailure> failures) {
    }

    /** Ошибка отдельной записи в массовой операции. */
    public record BulkFailure(UUID id, String message) {
    }
}
