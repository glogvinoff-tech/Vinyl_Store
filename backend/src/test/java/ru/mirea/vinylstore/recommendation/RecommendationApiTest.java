package ru.mirea.vinylstore.recommendation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendationApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean RecommendationService service;

    @BeforeEach void recommendations() {
        when(service.recommend(anySet(), anyInt())).thenAnswer(call -> new RecommendationService.Result(
            ((java.util.Set<?>) call.getArgument(0)).isEmpty() ? "DISCOVERY" : "PERSONALIZED",
            "SYNTHETIC", null, "Europe/Moscow", List.of(), null, null));
    }

    @Test void failedPythonServiceDoesNotBreakCatalog() throws Exception {
        when(service.recommend(anySet(), anyInt())).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        mvc.perform(get("/api/catalog/recommendations")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/catalog")).andExpect(status().isOk());
    }

    @Test void guestsCanUseRecommendationsWithoutChangingExistingSecurity() throws Exception {
        mvc.perform(get("/api/catalog/recommendations")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.dataSource").value("SYNTHETIC"))
            .andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/catalog/recommendations").param("genres", "Rock", "Jazz"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("PERSONALIZED"));
        mvc.perform(post("/api/shop/checkout").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test void rejectsInvalidLimitsAndOversizedTasteProfiles() throws Exception {
        for (String limit : new String[]{"0", "13", "-1", "abc"}) {
            mvc.perform(get("/api/catalog/recommendations").param("limit", limit)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/catalog/recommendations").param("genres", "x".repeat(51))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/recommendations").param("genres", "1", "2", "3", "4", "5", "6", "7", "8", "9"))
            .andExpect(status().isBadRequest());
    }
}
