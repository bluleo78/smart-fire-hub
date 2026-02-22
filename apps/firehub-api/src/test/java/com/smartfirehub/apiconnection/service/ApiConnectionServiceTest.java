package com.smartfirehub.apiconnection.service;

import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.dto.CreateApiConnectionRequest;
import com.smartfirehub.apiconnection.dto.UpdateApiConnectionRequest;
import com.smartfirehub.apiconnection.exception.ApiConnectionException;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.*;

class ApiConnectionServiceTest extends IntegrationTestBase {

    @Autowired
    private ApiConnectionService apiConnectionService;

    @Autowired
    private ApiConnectionRepository apiConnectionRepository;

    @Autowired
    private DSLContext dsl;

    private Long testUserId;

    private static final Table<?> USER_TABLE        = table(name("user"));
    private static final Field<Long>   U_ID         = field(name("user", "id"), Long.class);
    private static final Field<String> U_USERNAME   = field(name("user", "username"), String.class);
    private static final Field<String> U_PASSWORD   = field(name("user", "password"), String.class);
    private static final Field<String> U_NAME       = field(name("user", "name"), String.class);
    private static final Field<String> U_EMAIL      = field(name("user", "email"), String.class);

    private static final Table<?> API_CONNECTION    = table(name("api_connection"));
    private static final Field<Long> AC_CREATED_BY  = field(name("api_connection", "created_by"), Long.class);

    @BeforeEach
    void setUp() {
        testUserId = dsl.insertInto(USER_TABLE)
                .set(U_USERNAME, "apiconn_testuser_" + System.nanoTime())
                .set(U_PASSWORD, "password")
                .set(U_NAME, "API Conn Test User")
                .set(U_EMAIL, "apiconn_" + System.nanoTime() + "@example.com")
                .returning(U_ID)
                .fetchOne(r -> r.get(U_ID));
    }

    @AfterEach
    void tearDown() {
        // Delete api_connections created by test user first (FK), then user
        dsl.deleteFrom(API_CONNECTION)
                .where(AC_CREATED_BY.eq(testUserId))
                .execute();
        dsl.deleteFrom(USER_TABLE)
                .where(U_ID.eq(testUserId))
                .execute();
    }

    @Test
    void createAndGet_apiKeyConnection() {
        Map<String, String> authConfig = Map.of(
                "headerName", "X-API-Key",
                "apiKey", "my-super-secret-key-1234"
        );
        CreateApiConnectionRequest req = new CreateApiConnectionRequest(
                "My API Key Conn", "Test API key connection", "API_KEY", authConfig
        );

        ApiConnectionResponse created = apiConnectionService.create(req, testUserId);

        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("My API Key Conn");
        assertThat(created.authType()).isEqualTo("API_KEY");

        // Sensitive key "apiKey" should be masked
        assertThat(created.maskedAuthConfig().get("apiKey")).startsWith("****");
        // headerName is not sensitive — returned as-is
        assertThat(created.maskedAuthConfig().get("headerName")).isEqualTo("X-API-Key");

        // getById returns same result
        ApiConnectionResponse fetched = apiConnectionService.getById(created.id());
        assertThat(fetched.id()).isEqualTo(created.id());
        assertThat(fetched.name()).isEqualTo("My API Key Conn");
        assertThat(fetched.authType()).isEqualTo("API_KEY");
    }

    @Test
    void createAndGet_bearerConnection() {
        Map<String, String> authConfig = Map.of("token", "Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig");
        CreateApiConnectionRequest req = new CreateApiConnectionRequest(
                "Bearer Conn", "A bearer token connection", "BEARER", authConfig
        );

        ApiConnectionResponse created = apiConnectionService.create(req, testUserId);

        assertThat(created.authType()).isEqualTo("BEARER");
        // "token" key is sensitive — should be masked
        assertThat(created.maskedAuthConfig().get("token")).startsWith("****");
        assertThat(created.maskedAuthConfig().get("token")).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
    }

    @Test
    void getDecryptedAuthConfig_returnsPlaintext() {
        Map<String, String> authConfig = Map.of("token", "plaintext-bearer-token-xyz");
        CreateApiConnectionRequest req = new CreateApiConnectionRequest(
                "Decrypt Test", null, "BEARER", authConfig
        );
        ApiConnectionResponse created = apiConnectionService.create(req, testUserId);

        Map<String, String> decrypted = apiConnectionService.getDecryptedAuthConfig(created.id());

        assertThat(decrypted.get("token")).isEqualTo("plaintext-bearer-token-xyz");
    }

    @Test
    void update_changesNameAndDescription() {
        Map<String, String> authConfig = Map.of("apiKey", "original-key-value-5678");
        CreateApiConnectionRequest createReq = new CreateApiConnectionRequest(
                "Original Name", "Original Desc", "API_KEY", authConfig
        );
        ApiConnectionResponse created = apiConnectionService.create(createReq, testUserId);

        // Update name/description only — no authConfig
        UpdateApiConnectionRequest updateReq = new UpdateApiConnectionRequest(
                "Updated Name", "Updated Desc", null, null
        );
        ApiConnectionResponse updated = apiConnectionService.update(created.id(), updateReq);

        assertThat(updated.name()).isEqualTo("Updated Name");
        assertThat(updated.description()).isEqualTo("Updated Desc");

        // Credentials should be unchanged — decrypted value must match original
        Map<String, String> decrypted = apiConnectionService.getDecryptedAuthConfig(created.id());
        assertThat(decrypted.get("apiKey")).isEqualTo("original-key-value-5678");
    }

    @Test
    void update_changesAuthConfig() {
        Map<String, String> originalConfig = Map.of("token", "old-token-aaaa");
        CreateApiConnectionRequest createReq = new CreateApiConnectionRequest(
                "Token Conn", null, "BEARER", originalConfig
        );
        ApiConnectionResponse created = apiConnectionService.create(createReq, testUserId);

        Map<String, String> newConfig = Map.of("token", "new-token-bbbb");
        UpdateApiConnectionRequest updateReq = new UpdateApiConnectionRequest(
                "Token Conn", null, "BEARER", newConfig
        );
        apiConnectionService.update(created.id(), updateReq);

        Map<String, String> decrypted = apiConnectionService.getDecryptedAuthConfig(created.id());
        assertThat(decrypted.get("token")).isEqualTo("new-token-bbbb");
    }

    @Test
    void delete_removesConnection() {
        Map<String, String> authConfig = Map.of("apiKey", "delete-me-key-9999");
        CreateApiConnectionRequest req = new CreateApiConnectionRequest(
                "To Delete", null, "API_KEY", authConfig
        );
        ApiConnectionResponse created = apiConnectionService.create(req, testUserId);
        Long id = created.id();

        apiConnectionService.delete(id);

        assertThatThrownBy(() -> apiConnectionService.getById(id))
                .isInstanceOf(ApiConnectionException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void getAll_returnsMultiple() {
        Map<String, String> config1 = Map.of("apiKey", "key-one-1111");
        Map<String, String> config2 = Map.of("token", "token-two-2222");

        apiConnectionService.create(
                new CreateApiConnectionRequest("Conn One", null, "API_KEY", config1), testUserId);
        apiConnectionService.create(
                new CreateApiConnectionRequest("Conn Two", null, "BEARER", config2), testUserId);

        List<ApiConnectionResponse> all = apiConnectionService.getAll();

        long ownedByTestUser = all.stream()
                .filter(r -> r.createdBy().equals(testUserId))
                .count();
        assertThat(ownedByTestUser).isGreaterThanOrEqualTo(2);
    }
}
