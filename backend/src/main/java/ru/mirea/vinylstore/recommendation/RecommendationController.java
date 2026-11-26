package ru.mirea.vinylstore.recommendation;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import ru.mirea.vinylstore.error.BadRequestException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
public class RecommendationController {
    private final RecommendationService service;

    public RecommendationController(RecommendationService service) { this.service = service; }

    // Already covered by the existing public, read-only /api/catalog/** security rule.
    @GetMapping("/api/catalog/recommendations")
    public ResponseEntity<RecommendationService.Result> recommendations(
        @RequestParam(defaultValue = "") List<String> genres,
        @RequestParam(defaultValue = "3") int limit) {
        if (limit < 1 || limit > 12 || genres.size() > 8 || genres.stream().anyMatch(g -> g.length() > 50)) {
            throw new BadRequestException("Выберите не более 8 жанров; число рекомендаций — от 1 до 12");
        }
        Set<String> tastes = genres.stream().map(g -> g.strip().toLowerCase(Locale.ROOT)).filter(g -> !g.isEmpty()).collect(Collectors.toSet());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.recommend(tastes, limit));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> unavailable(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).cacheControl(CacheControl.noStore())
            .body(Map.of("message", "Музыкальный помощник временно недоступен"));
    }
}
