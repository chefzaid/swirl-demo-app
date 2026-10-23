package dev.swirlit.demo.order.config;

import dev.swirlit.demo.order.service.OrderService;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.enabled=true")
class ApiDocsInfoTest {

    // Maven filters build-version.properties and application.yml alike, so both carry the project version.
    private static final String BUILD_VERSION = buildVersion();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private CacheManager cacheManager;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void namesTheApiWithTheBuildVersionAndLicense() throws Exception {
        mockMvc.perform(get("/api/orders/openapi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Swirl Demo App orders API"))
                .andExpect(jsonPath("$.info.description").value(startsWith("Example service of the Swirl Demo App service template.")))
                .andExpect(jsonPath("$.info.version").value(BUILD_VERSION))
                .andExpect(jsonPath("$.info.license.name").value("GNU General Public License v3.0"))
                .andExpect(jsonPath("$.info.license.url").value("https://www.gnu.org/licenses/gpl-3.0.html"))
                .andExpect(jsonPath("$.externalDocs.url").value(endsWith("/docs/features.md")));
    }

    @Test
    void reportsTheSameVersionOnTheInfoEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app.version").value(BUILD_VERSION));
    }

    private static String buildVersion() {
        Properties build = new Properties();
        try (InputStream properties = Objects.requireNonNull(
                ApiDocsInfoTest.class.getResourceAsStream("/build-version.properties"), "build-version.properties")) {
            build.load(properties);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        String version = build.getProperty("version");
        assertThat(version).as("filtered project version").isNotBlank().doesNotContain("@");
        return version;
    }
}
