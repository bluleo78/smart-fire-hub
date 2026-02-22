package com.smartfirehub.apiconnection.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.dto.CreateApiConnectionRequest;
import com.smartfirehub.apiconnection.dto.UpdateApiConnectionRequest;
import com.smartfirehub.apiconnection.exception.ApiConnectionException;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.jooq.impl.DSL.*;

@Service
public class ApiConnectionService {

    private static final Set<String> SENSITIVE_KEY_PARTS = Set.of("key", "token", "secret", "password");

    private final ApiConnectionRepository repository;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    public ApiConnectionService(ApiConnectionRepository repository,
                                EncryptionService encryptionService,
                                ObjectMapper objectMapper) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ApiConnectionResponse create(CreateApiConnectionRequest request, Long userId) {
        validateAuthType(request.authType());

        String encryptedConfig = serializeAndEncrypt(request.authConfig());
        Long id = repository.save(request.name(), request.description(), request.authType(), encryptedConfig, userId);

        return getById(id);
    }

    @Transactional(readOnly = true)
    public List<ApiConnectionResponse> getAll() {
        return repository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ApiConnectionResponse getById(Long id) {
        Record record = repository.findById(id)
                .orElseThrow(() -> new ApiConnectionException("ApiConnection not found: " + id));
        return toResponse(record);
    }

    @Transactional(readOnly = true)
    public Map<String, String> getDecryptedAuthConfig(Long id) {
        Record record = repository.findById(id)
                .orElseThrow(() -> new ApiConnectionException("ApiConnection not found: " + id));
        String encryptedConfig = record.get(field(name("api_connection", "auth_config"), String.class));
        return decryptToMap(encryptedConfig);
    }

    @Transactional
    public ApiConnectionResponse update(Long id, UpdateApiConnectionRequest request) {
        repository.findById(id)
                .orElseThrow(() -> new ApiConnectionException("ApiConnection not found: " + id));

        String encryptedConfig = null;
        if (request.authConfig() != null) {
            if (request.authType() != null) {
                validateAuthType(request.authType());
            }
            encryptedConfig = serializeAndEncrypt(request.authConfig());
        }

        String authType = request.authType() != null ? request.authType() : fetchAuthType(id);
        repository.update(id, request.name(), request.description(), authType, encryptedConfig);

        return getById(id);
    }

    @Transactional
    public void delete(Long id) {
        repository.findById(id)
                .orElseThrow(() -> new ApiConnectionException("ApiConnection not found: " + id));
        repository.deleteById(id);
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private void validateAuthType(String authType) {
        if (authType == null || (!authType.equals("API_KEY") && !authType.equals("BEARER"))) {
            throw new ApiConnectionException("Unsupported authType: " + authType + ". Supported: API_KEY, BEARER");
        }
    }

    private String serializeAndEncrypt(Map<String, String> authConfig) {
        try {
            String json = objectMapper.writeValueAsString(authConfig);
            return encryptionService.encrypt(json);
        } catch (JsonProcessingException e) {
            throw new ApiConnectionException("Failed to serialize authConfig: " + e.getMessage());
        }
    }

    private Map<String, String> decryptToMap(String encryptedConfig) {
        try {
            String json = encryptionService.decrypt(encryptedConfig);
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new ApiConnectionException("Failed to deserialize authConfig: " + e.getMessage());
        }
    }

    private Map<String, String> maskAuthConfig(Map<String, String> authConfig) {
        Map<String, String> masked = new HashMap<>();
        for (Map.Entry<String, String> entry : authConfig.entrySet()) {
            String key = entry.getKey().toLowerCase();
            boolean isSensitive = SENSITIVE_KEY_PARTS.stream().anyMatch(key::contains);
            masked.put(entry.getKey(), isSensitive ? encryptionService.maskValue(entry.getValue()) : entry.getValue());
        }
        return masked;
    }

    private String fetchAuthType(Long id) {
        Record record = repository.findById(id)
                .orElseThrow(() -> new ApiConnectionException("ApiConnection not found: " + id));
        return record.get(field(name("api_connection", "auth_type"), String.class));
    }

    private ApiConnectionResponse toResponse(Record r) {
        String encryptedConfig = r.get(field(name("api_connection", "auth_config"), String.class));
        Map<String, String> plainConfig = decryptToMap(encryptedConfig);
        Map<String, String> masked = maskAuthConfig(plainConfig);

        return new ApiConnectionResponse(
                r.get(field(name("api_connection", "id"), Long.class)),
                r.get(field(name("api_connection", "name"), String.class)),
                r.get(field(name("api_connection", "description"), String.class)),
                r.get(field(name("api_connection", "auth_type"), String.class)),
                masked,
                r.get(field(name("api_connection", "created_by"), Long.class)),
                r.get(field(name("api_connection", "created_at"), LocalDateTime.class)),
                r.get(field(name("api_connection", "updated_at"), LocalDateTime.class))
        );
    }
}
