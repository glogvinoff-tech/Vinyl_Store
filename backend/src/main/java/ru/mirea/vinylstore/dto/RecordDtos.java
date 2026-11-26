package ru.mirea.vinylstore.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** DTO-контракты пластинок. */
public final class RecordDtos {
    private RecordDtos() {
    }

    public record RecordRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 200) String name,
            @Min(1900) @Max(2100) Integer releaseYear,
            @NotBlank @Size(max=120) String artist,
            @Size(max=50) String genre,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.DecimalMin("0.01") @jakarta.validation.constraints.DecimalMax("99999999.99") java.math.BigDecimal price,
            @Min(0) int stock,
            @Size(max=200) @jakarta.validation.constraints.Pattern(regexp="^/(?:assets/[A-Za-z0-9._-]+|api/images/[a-f0-9-]{36})$",message="Выберите локальное изображение") String cover,
            @Size(max=2000) String description,
            Boolean active,
            Long version,
            @Size(max=20) java.util.List<@NotBlank @Size(max=50) @jakarta.validation.constraints.Pattern(regexp="[^,]+") String> tags,
            @Size(max=8) java.util.List<@NotBlank @Size(max=200) @jakarta.validation.constraints.Pattern(regexp="^/(?:assets/[A-Za-z0-9._-]+|api/images/[a-f0-9-]{36})$") String> images,
            @Min(0) @Max(90) Integer discountPercent) {
    }

    public record RecordResponse(
            UUID id,
            String code,
            String name,
            Integer releaseYear,
            String artist, String genre, java.math.BigDecimal price, int stock, String cover, String description,
            boolean active,
            Instant createdAt,
            Instant updatedAt,
            long version, java.util.List<String> tags, java.util.List<String> images,
            int discountPercent, java.math.BigDecimal salePrice) {
    }
}
