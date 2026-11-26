package ru.mirea.vinylstore.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.io.IOException;
import java.util.Map;

/** Isolated admin-only synthetic forecast: no access to order repositories. */
@RestController
public class SalesForecastController {
    private final URI endpoint;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public SalesForecastController(ObjectMapper json, @Value("${recommendations.url:http://127.0.0.1:8091}") String url) {
        this.json = json;
        endpoint = URI.create(url.replaceAll("/+$", "") + "/forecast");
    }

    @GetMapping("/api/analytics/forecast")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> forecast() {
        try {
            var response = client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return unavailable();
            JsonNode body = json.readTree(response.body());
            if (body == null || !"SYNTHETIC".equals(body.path("dataSource").asText()) || !body.path("forecast").path("months").isArray()) return unavailable();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable();
        } catch (IOException e) { return unavailable(); }
    }

    private ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(Map.of("message", "Прогноз временно недоступен. Проверьте Python-сервис."));
    }
}
