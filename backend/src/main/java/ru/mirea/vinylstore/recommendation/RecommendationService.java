package ru.mirea.vinylstore.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.mirea.vinylstore.domain.VinylRecord;
import ru.mirea.vinylstore.dto.RecordDtos.RecordResponse;
import ru.mirea.vinylstore.repository.StoreOrderRepository;
import ru.mirea.vinylstore.repository.VinylRecordRepository;
import ru.mirea.vinylstore.service.RecordService;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Read-only bridge to Python; checkout and catalog prices stay authoritative. */
@Service
public class RecommendationService {
    private final VinylRecordRepository records;
    private final StoreOrderRepository orders;
    private final ObjectMapper json;
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public RecommendationService(VinylRecordRepository records, StoreOrderRepository orders, ObjectMapper json,
        @Value("${recommendations.url:http://127.0.0.1:8091}") String url) {
        this.records = records;
        this.orders = orders;
        this.json = json;
        this.endpoint = URI.create(url.replaceAll("/+$", "") + "/recommend");
    }

    public record Pick(RecordResponse record, double score, String reason) {}
    public record Promotion(String title, String explanation, List<Pick> items) {}
    public record Result(String mode, String dataSource, String generatedAt, String timeZone,
                         List<Pick> items, Promotion promotion, JsonNode training) {}

    public Result recommend(Set<String> tastes, int limit) {
        List<VinylRecord> catalog = records.findByActiveTrueOrderByCodeAsc();
        Map<String, VinylRecord> available = new HashMap<>();
        List<Map<String, Object>> input = new ArrayList<>();
        for (VinylRecord record : catalog) {
            if (!record.isActive()) continue;
            if (record.getStock() > 0) available.put(record.getId().toString(), record);
            input.add(Map.of("id", record.getId().toString(), "genre", Objects.toString(record.getGenre(), ""),
                "price", record.getPrice(), "discountPercent", record.getDiscountPercent(), "stock", record.getStock()));
        }
        if (input.isEmpty()) return new Result(tastes.isEmpty() ? "DISCOVERY" : "PERSONALIZED", "SYNTHETIC", null,
            "Europe/Moscow", List.of(), null, null);
        // Анонимная история покупок из базы: только UUID пластинки/покупателя, жанр, количество и момент времени.
        List<Map<String, Object>> events = new ArrayList<>();
        for (Object[] row : orders.trainingEvents()) {
            events.add(Map.of("recordId", row[0].toString(), "genre", Objects.toString(row[1], ""),
                "quantity", ((Number) row[2]).intValue(), "occurredAt", row[3].toString(), "userId", row[4].toString()));
        }
        String dataSource = events.isEmpty() ? "SYNTHETIC" : "DATABASE";
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(
                    Map.of("records", input, "genres", tastes, "limit", limit, "history", events))))
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("Recommendation service unavailable");
            JsonNode body = json.readTree(response.body());
            if (body == null) throw new IOException("Empty recommendation response");
            List<Pick> picks = resolve(body.path("items"), available, limit, false);
            JsonNode offer = body.path("promotion");
            List<Pick> deals = resolve(offer.path("items"), available, 3, true);
            Promotion promotion = deals.isEmpty() ? null : new Promotion(offer.path("title").asText(),
                "Нейросеть выбрала действующие скидки каталога с учётом вашего вкуса, даты и времени. Цена сохранится в корзине.", deals);
            return new Result(tastes.isEmpty() ? "DISCOVERY" : "PERSONALIZED", dataSource, body.path("generatedAt").asText(),
                "Europe/Moscow", picks, promotion, body.get("training"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | IllegalArgumentException e) {
            throw unavailable();
        }
    }

    private static List<Pick> resolve(JsonNode items, Map<String, VinylRecord> catalog, int limit, boolean discountedOnly) throws IOException {
        if (!items.isArray()) throw new IOException("Invalid recommendation response");
        List<Pick> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : items) {
            String id = item.path("recordId").asText();
            VinylRecord record = catalog.get(id);
            double score = item.path("score").asDouble(Double.NaN);
            if (record == null || !seen.add(id) || !Double.isFinite(score) || score < 0 || score > 1
                || (discountedOnly && record.getDiscountPercent() <= 0)) continue;
            result.add(new Pick(RecordService.toResponse(record), score, item.path("reason").asText()));
            if (result.size() >= limit) break;
        }
        return List.copyOf(result);
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Музыкальный помощник временно недоступен");
    }
}
