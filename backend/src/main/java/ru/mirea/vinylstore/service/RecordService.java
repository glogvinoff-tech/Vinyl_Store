package ru.mirea.vinylstore.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mirea.vinylstore.domain.VinylRecord;
import ru.mirea.vinylstore.dto.RecordDtos.RecordRequest;
import ru.mirea.vinylstore.dto.RecordDtos.RecordResponse;
import ru.mirea.vinylstore.error.BadRequestException;
import ru.mirea.vinylstore.error.ConflictException;
import ru.mirea.vinylstore.error.NotFoundException;
import ru.mirea.vinylstore.repository.VinylRecordRepository;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Прикладные операции над учебными пластинками. */
@Service
public class RecordService {
    private final VinylRecordRepository records;
    private final AuditService audit;

    public RecordService(VinylRecordRepository records, AuditService audit) {
        this.records = records;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<RecordResponse> list(boolean activeOnly) {
        return (activeOnly ? records.findByActiveTrueOrderByCodeAsc() : records.findAllByOrderByCodeAsc())
                .stream().map(RecordService::toResponse).toList();
    }

    @Transactional
    public RecordResponse create(RecordRequest request) {
        String code = normalizeCode(request.code());
        if (records.existsByCodeIgnoreCase(code)) throw new ConflictException("Пластинка с таким кодом уже существует");
        VinylRecord record = new VinylRecord(UUID.randomUUID(), code, normalizeNullable(request.name()), request.releaseYear());
        record.setArtist(request.artist());record.setGenre(request.genre());record.setPrice(request.price());record.setStock(request.stock());
        record.setCover(request.cover());
        if (request.tags()!=null) record.setCategories(String.join(",", request.tags()));
        if (request.images()!=null) record.setImages(request.images());
        if (request.discountPercent()!=null) record.setDiscountPercent(request.discountPercent());
        record.setDescription(request.description());
        if (request.active() != null) record.setActive(request.active());
        records.saveAndFlush(record);
        audit.record("RECORD_CREATED", "RECORD", record.getId(), "Создана пластинка " + code, Map.of());
        records.flush();
        return toResponse(record);
    }

    @Transactional
    public RecordResponse update(UUID id, RecordRequest request) {
        VinylRecord record = require(id);
        if (request.version() == null || request.version() != record.getVersion())
            throw new ConflictException("Пластинка уже изменена. Обновите список");
        String code = normalizeCode(request.code());
        records.findByCodeIgnoreCase(code).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
            throw new ConflictException("Пластинка с таким кодом уже существует");
        });
        record.setCode(code);
        record.setName(normalizeNullable(request.name()));
        record.setReleaseYear(request.releaseYear());
        record.setArtist(request.artist());record.setGenre(request.genre());record.setPrice(request.price());record.setStock(request.stock());
        record.setCover(request.cover());
        if (request.tags()!=null) record.setCategories(String.join(",", request.tags()));
        if (request.images()!=null) record.setImages(request.images());
        if (request.discountPercent()!=null) record.setDiscountPercent(request.discountPercent());
        record.setDescription(request.description());
        if (request.active() != null) record.setActive(request.active());
        audit.record("RECORD_UPDATED", "RECORD", id, "Изменена пластинка " + code, Map.of());
        records.flush();
        return toResponse(record);
    }

    @Transactional
    public RecordResponse setActive(UUID id, boolean active, long version) {
        VinylRecord record = require(id);
        if (version != record.getVersion()) throw new ConflictException("Пластинка уже изменена. Обновите список");
        record.setActive(active);
        audit.record(active ? "RECORD_ACTIVATED" : "RECORD_DEACTIVATED", "RECORD", id,
                (active ? "Активирована" : "Деактивирована") + " пластинка " + record.getCode(), Map.of());
        records.flush();
        return toResponse(record);
    }

    private VinylRecord require(UUID id) {
        return records.findById(id).orElseThrow(() -> new NotFoundException("Пластинка не найдена"));
    }

    public static RecordResponse toResponse(VinylRecord record) {
        return new RecordResponse(record.getId(), record.getCode(), record.getName(), record.getReleaseYear(),
                record.getArtist(), record.getGenre(), record.getPrice(), record.getStock(), record.getCover(), record.getDescription(),
                record.isActive(), record.getCreatedAt(), record.getUpdatedAt(), record.getVersion(),
                record.getCategories().isBlank() ? List.of() : List.of(record.getCategories().split(",")),
                record.getImages(), record.getDiscountPercent(), record.getSalePrice());
    }

    private static String normalizeCode(String value) {
        if (value == null || value.isBlank()) throw new BadRequestException("Код пластинки обязателен");
        return value.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim().replaceAll("\\s+", " ");
    }
}
