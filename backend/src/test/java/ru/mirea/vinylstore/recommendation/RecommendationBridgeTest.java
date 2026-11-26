package ru.mirea.vinylstore.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import ru.mirea.vinylstore.domain.VinylRecord;
import ru.mirea.vinylstore.repository.StoreOrderRepository;
import ru.mirea.vinylstore.repository.VinylRecordRepository;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecommendationBridgeTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void readsPythonRankingButKeepsActualCatalogPricesAndAvailability() throws Exception {
        var rock = record(1, 20, 5);
        var soldOut = record(2, 30, 0);
        var noDiscount = record(3, 0, 5);
        var repository = mock(VinylRecordRepository.class);
        when(repository.findByActiveTrueOrderByCodeAsc()).thenReturn(List.of(rock, soldOut, noDiscount));
        AtomicReference<String> received = new AtomicReference<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/recommend", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var picks = List.of(pick(soldOut), pick(rock), pick(rock), pick(noDiscount));
            byte[] response = json.writeValueAsBytes(Map.of("items", picks, "generatedAt", "2026-11-23T19:00:00+03:00",
                "promotion", Map.of("title", "Вечер", "items", picks)));
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        server.start();
        try {
            var service = bridge(repository, json, "http://127.0.0.1:" + server.getAddress().getPort(),
                List.<Object[]>of(new Object[]{rock.getId(), "Rock", 2, java.time.Instant.parse("2026-11-01T18:00:00Z"), new UUID(0, 99)}));
            var result = service.recommend(Set.of("rock"), 3);
            assertThat(result.items()).extracting(p -> p.record().id()).containsExactly(rock.getId(), noDiscount.getId());
            assertThat(result.items().getFirst().record().salePrice()).isEqualByComparingTo("800");
            assertThat(result.promotion().items()).hasSize(1);
            assertThat(rock.getStock()).isEqualTo(5);
            assertThat(rock.getPrice()).isEqualByComparingTo("1000");
            var request = json.readTree(received.get());
            assertThat(request.path("genres").get(0).asText()).isEqualTo("rock");
            assertThat(request.path("records").get(0).properties()).hasSize(5);
            assertThat(request.has("userId")).isFalse();
            assertThat(request.path("history").size()).isEqualTo(1);
            assertThat(request.path("history").get(0).path("quantity").asInt()).isEqualTo(2);
            verify(repository).findByActiveTrueOrderByCodeAsc();
            verifyNoMoreInteractions(repository);
        } finally { server.stop(0); }
    }

    @Test void emptyCatalogWorksWithoutPythonAndInvalidResponseIsUnavailable() throws Exception {
        var repository = mock(VinylRecordRepository.class);
        when(repository.findByActiveTrueOrderByCodeAsc()).thenReturn(List.of());
        var service = bridge(repository, json, "http://127.0.0.1:1", List.of());
        assertThat(service.recommend(Set.of(), 3).items()).isEmpty();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/recommend", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        server.start();
        try {
            when(repository.findByActiveTrueOrderByCodeAsc()).thenReturn(List.of(record(1, 0, 10)));
            var broken = bridge(repository, json, "http://127.0.0.1:" + server.getAddress().getPort(), List.of());
            assertThatThrownBy(() -> broken.recommend(Set.of(), 3)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
        } finally { server.stop(0); }
    }

    private static RecommendationService bridge(VinylRecordRepository repository, ObjectMapper json, String url,
                                                List<Object[]> events) {
        var orders = mock(StoreOrderRepository.class);
        when(orders.trainingEvents()).thenReturn(events);
        return new RecommendationService(repository, orders, json, url);
    }

    private static Map<String, Object> pick(VinylRecord record) {
        return Map.of("recordId", record.getId().toString(), "score", 0.8, "reason", "По вкусу", "salePrice", 1);
    }

    private static VinylRecord record(int id, int discount, int stock) {
        var record = new VinylRecord(new UUID(0, id), "TEST-" + id, "Album " + id, 2020);
        record.setPrice(new BigDecimal("1000")); record.setDiscountPercent(discount); record.setStock(stock);
        return record;
    }
}
