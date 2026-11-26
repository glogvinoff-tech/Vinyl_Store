package ru.mirea.vinylstore.recommendation;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class SalesForecastApiTest {
    static HttpServer python;
    static volatile int responseStatus = 200;
    static {
        try {
            python = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            python.createContext("/forecast", exchange -> {
                byte[] bytes = "{\"dataSource\":\"SYNTHETIC\",\"forecast\":{\"months\":[]}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(responseStatus, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            python.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("recommendations.url", () -> "http://127.0.0.1:" + python.getAddress().getPort());
    }
    @AfterAll static void stop() { python.stop(0); }
    @BeforeEach void reset() { responseStatus = 200; }
    @Autowired MockMvc mvc;

    @Test void guestCannotAccess() throws Exception {
        mvc.perform(get("/api/analytics/forecast")).andExpect(status().isUnauthorized());
    }
    @Test @WithMockUser(roles="VIEWER") void viewerCannotAccess() throws Exception {
        mvc.perform(get("/api/analytics/forecast")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="MANAGER") void managerCannotAccess() throws Exception {
        mvc.perform(get("/api/analytics/forecast")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="ADMIN") void adminReceivesSyntheticForecast() throws Exception {
        mvc.perform(get("/api/analytics/forecast")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.dataSource").value("SYNTHETIC"));
    }
    @Test @WithMockUser(roles="ADMIN") void failureIsIsolated() throws Exception {
        responseStatus = 503;
        mvc.perform(get("/api/analytics/forecast")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/catalog")).andExpect(status().isOk());
    }
}
