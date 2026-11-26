package ru.mirea.vinylstore.service;

import org.springframework.stereotype.Component;
import ru.mirea.vinylstore.domain.StoreOrder;
import ru.mirea.vinylstore.dto.StoreOrderDtos.StoreOrderResponse;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Преобразует entity в публичный DTO внутри активной транзакции. */
@Component
public class StoreOrderMapper {
    public StoreOrderResponse toResponse(StoreOrder order) {
        String displayTitle = Stream.of(order.getArtist(), order.getAlbumTitle(), order.getCustomerName())
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(" "));
        return new StoreOrderResponse(order.getId(), order.getExternalId(), order.getAlbumTitle(),
                order.getArtist(), order.getCustomerName(), displayTitle, order.getEmail(),
                order.getPhone(), order.getOrderedOn(),
                order.getRecord() == null ? null : order.getRecord().getId(),
                order.getRecord() == null ? null : order.getRecord().getCode(),
                order.getStatus(), order.getTotalPrice(), order.isPaid(), order.getPriorityLevel(),
                order.isArchived(), order.getArchivedAt(), Set.copyOf(order.getTags()),
                order.getCreatedAt(), order.getUpdatedAt(), order.getQuantity(), order.getVersion());
    }
}
