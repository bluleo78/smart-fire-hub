package com.smartfirehub.pipeline.service;

import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.exception.CyclicTriggerDependencyException;
import com.smartfirehub.pipeline.exception.TriggerNotFoundException;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class TriggerService {

    private static final Logger log = LoggerFactory.getLogger(TriggerService.class);
    private static final int MAX_CHAIN_DEPTH = 10;

    private final TriggerRepository triggerRepository;
    private final TriggerEventRepository triggerEventRepository;
    private final PipelineService pipelineService;
    private final TriggerSchedulerService schedulerService;

    public TriggerService(
            TriggerRepository triggerRepository,
            TriggerEventRepository triggerEventRepository,
            @Lazy PipelineService pipelineService,
            @Lazy TriggerSchedulerService schedulerService) {
        this.triggerRepository = triggerRepository;
        this.triggerEventRepository = triggerEventRepository;
        this.pipelineService = pipelineService;
        this.schedulerService = schedulerService;
    }

    @Transactional
    public TriggerResponse createTrigger(Long pipelineId, CreateTriggerRequest request, Long userId) {
        Map<String, Object> processedConfig = new HashMap<>(request.config() != null ? request.config() : Map.of());
        String rawApiToken = null;

        switch (request.triggerType()) {
            case SCHEDULE -> {
                validateScheduleConfig(processedConfig);
            }
            case API -> {
                // Generate API token
                rawApiToken = generateSecureToken();
                String tokenHash = sha256Hash(rawApiToken);
                processedConfig.put("tokenHash", tokenHash);
            }
            case PIPELINE_CHAIN -> {
                Object upstreamIdObj = processedConfig.get("upstreamPipelineId");
                if (upstreamIdObj == null) {
                    throw new IllegalArgumentException("upstreamPipelineId is required for PIPELINE_CHAIN trigger");
                }
                Long upstreamPipelineId = ((Number) upstreamIdObj).longValue();
                if (upstreamPipelineId.equals(pipelineId)) {
                    throw new CyclicTriggerDependencyException("Pipeline cannot trigger itself");
                }
                validatePipelineChain(pipelineId, upstreamPipelineId);
                String condition = (String) processedConfig.getOrDefault("condition", "SUCCESS");
                TriggerCondition.valueOf(condition); // validate
            }
            case WEBHOOK -> {
                String webhookId = UUID.randomUUID().toString();
                processedConfig.put("webhookId", webhookId);
                // Encrypt secret if provided
                Object secret = processedConfig.get("secret");
                if (secret != null && !secret.toString().isEmpty()) {
                    String encrypted = encryptSecret(secret.toString());
                    processedConfig.put("secretEncrypted", encrypted);
                    processedConfig.remove("secret");
                }
            }
            case DATASET_CHANGE -> {
                Object datasetIds = processedConfig.get("datasetIds");
                if (datasetIds == null || !(datasetIds instanceof List) || ((List<?>) datasetIds).isEmpty()) {
                    throw new IllegalArgumentException("datasetIds is required and must not be empty for DATASET_CHANGE trigger");
                }
            }
        }

        Long triggerId = triggerRepository.create(pipelineId, request, processedConfig, userId);

        // Register schedule after transaction commits
        if (request.triggerType() == TriggerType.SCHEDULE) {
            final Map<String, Object> finalConfig = processedConfig;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    schedulerService.registerSchedule(triggerId, finalConfig);
                }
            });
        }

        TriggerResponse response = triggerRepository.findById(triggerId)
                .orElseThrow(() -> new TriggerNotFoundException("Trigger not found after creation: " + triggerId));

        // If API trigger, return the raw token in a modified response (one-time only)
        if (rawApiToken != null) {
            Map<String, Object> configWithToken = new HashMap<>(response.config());
            configWithToken.put("rawToken", rawApiToken);
            response = new TriggerResponse(
                    response.id(), response.pipelineId(), response.triggerType(),
                    response.name(), response.description(), response.isEnabled(),
                    configWithToken, response.triggerState(), response.createdBy(), response.createdAt()
            );
        }

        return response;
    }

    @Transactional
    public void updateTrigger(Long triggerId, UpdateTriggerRequest request, Long userId) {
        TriggerResponse existing = triggerRepository.findById(triggerId)
                .orElseThrow(() -> new TriggerNotFoundException("Trigger not found: " + triggerId));

        // If PIPELINE_CHAIN and upstreamPipelineId is changing, validate
        if ("PIPELINE_CHAIN".equals(existing.triggerType()) && request.config() != null) {
            Object upstreamIdObj = request.config().get("upstreamPipelineId");
            if (upstreamIdObj != null) {
                Long upstreamPipelineId = ((Number) upstreamIdObj).longValue();
                if (upstreamPipelineId.equals(existing.pipelineId())) {
                    throw new CyclicTriggerDependencyException("Pipeline cannot trigger itself");
                }
                validatePipelineChain(existing.pipelineId(), upstreamPipelineId);
            }
        }

        triggerRepository.update(triggerId, request, userId);

        // If schedule trigger, re-register after commit
        if ("SCHEDULE".equals(existing.triggerType())) {
            Map<String, Object> config = request.config() != null ? request.config() : existing.config();
            boolean enabled = request.isEnabled() != null ? request.isEnabled() : existing.isEnabled();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (enabled) {
                        schedulerService.registerSchedule(triggerId, config);
                    } else {
                        schedulerService.unregisterSchedule(triggerId);
                    }
                }
            });
        }
    }

    @Transactional
    public void deleteTrigger(Long triggerId) {
        TriggerResponse existing = triggerRepository.findById(triggerId)
                .orElseThrow(() -> new TriggerNotFoundException("Trigger not found: " + triggerId));

        triggerRepository.delete(triggerId);

        // Unregister schedule after commit
        if ("SCHEDULE".equals(existing.triggerType())) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    schedulerService.unregisterSchedule(triggerId);
                }
            });
        }
    }

    @Transactional
    public void toggleTrigger(Long triggerId, boolean enabled) {
        TriggerResponse existing = triggerRepository.findById(triggerId)
                .orElseThrow(() -> new TriggerNotFoundException("Trigger not found: " + triggerId));

        triggerRepository.updateEnabled(triggerId, enabled);

        // Update scheduler after commit
        if ("SCHEDULE".equals(existing.triggerType())) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (enabled) {
                        schedulerService.registerSchedule(triggerId, existing.config());
                    } else {
                        schedulerService.unregisterSchedule(triggerId);
                    }
                }
            });
        }
    }

    public List<TriggerResponse> getTriggers(Long pipelineId) {
        return triggerRepository.findByPipelineId(pipelineId);
    }

    public TriggerResponse getTriggerById(Long triggerId) {
        return triggerRepository.findById(triggerId)
                .orElseThrow(() -> new TriggerNotFoundException("Trigger not found: " + triggerId));
    }

    public List<TriggerEventResponse> getTriggerEvents(Long pipelineId, int limit) {
        return triggerEventRepository.findByPipelineId(pipelineId, limit);
    }

    /**
     * Fire a trigger: validate conditions, execute pipeline, record event.
     */
    @Transactional
    public void fireTrigger(Long triggerId, Map<String, Object> params) {
        TriggerResponse trigger;
        try {
            trigger = triggerRepository.findById(triggerId).orElse(null);
        } catch (Exception e) {
            log.warn("Failed to find trigger {} during fire: {}", triggerId, e.getMessage());
            return;
        }

        if (trigger == null) {
            log.warn("Trigger {} not found during fire (possibly deleted)", triggerId);
            return;
        }

        if (!trigger.isEnabled()) {
            log.info("Trigger {} is disabled, skipping", triggerId);
            return;
        }

        // Check if pipeline is active
        if (!triggerRepository.isPipelineActive(trigger.pipelineId())) {
            log.info("Pipeline {} is not active, skipping trigger {}", trigger.pipelineId(), triggerId);
            triggerEventRepository.create(triggerId, trigger.pipelineId(), null, "SKIPPED",
                    Map.of("reason", "Pipeline is not active"));
            return;
        }

        // Check concurrency policy for SCHEDULE triggers
        if ("SCHEDULE".equals(trigger.triggerType())) {
            String policy = (String) trigger.config().getOrDefault("concurrencyPolicy", "SKIP");
            if ("SKIP".equals(policy) && triggerRepository.hasRunningExecution(trigger.pipelineId())) {
                log.info("Skipping trigger {} due to SKIP concurrency policy (running execution exists)", triggerId);
                triggerEventRepository.create(triggerId, trigger.pipelineId(), null, "SKIPPED",
                        Map.of("reason", "Concurrent execution (SKIP policy)"));
                return;
            }
        }

        try {
            // Execute pipeline using trigger creator as executedBy
            PipelineExecutionResponse execution = pipelineService.executePipeline(
                    trigger.pipelineId(), trigger.createdBy(), trigger.triggerType(), triggerId);

            // Record FIRED event
            triggerEventRepository.create(triggerId, trigger.pipelineId(), execution.id(), "FIRED",
                    params.isEmpty() ? null : params);

            // Update trigger state with lastFiredAt
            Map<String, Object> state = new HashMap<>(trigger.triggerState());
            state.put("lastFiredAt", LocalDateTime.now().toString());
            triggerRepository.updateTriggerState(triggerId, state);

            log.info("Trigger {} fired successfully, execution {}", triggerId, execution.id());
        } catch (Exception e) {
            log.error("Failed to fire trigger {}: {}", triggerId, e.getMessage(), e);
            triggerEventRepository.create(triggerId, trigger.pipelineId(), null, "ERROR",
                    Map.of("error", e.getMessage()));
        }
    }

    /**
     * DFS cycle detection for pipeline chain triggers.
     */
    public void validatePipelineChain(Long targetPipelineId, Long upstreamPipelineId) {
        Set<Long> visited = new HashSet<>();
        visited.add(targetPipelineId);
        dfsCheckCycle(upstreamPipelineId, targetPipelineId, visited, 0);
    }

    private void dfsCheckCycle(Long currentPipelineId, Long targetPipelineId, Set<Long> visited, int depth) {
        if (depth > MAX_CHAIN_DEPTH) {
            throw new CyclicTriggerDependencyException("Pipeline chain depth exceeds maximum of " + MAX_CHAIN_DEPTH);
        }

        // Find all chain triggers where currentPipelineId is the downstream pipeline
        List<TriggerResponse> chainTriggers = triggerRepository.findEnabledChainTriggersByUpstreamId(currentPipelineId);
        for (TriggerResponse trigger : chainTriggers) {
            Long downstreamPipelineId = trigger.pipelineId();
            if (downstreamPipelineId.equals(targetPipelineId)) {
                throw new CyclicTriggerDependencyException("Cyclic trigger dependency detected");
            }
            if (!visited.contains(downstreamPipelineId)) {
                visited.add(downstreamPipelineId);
                dfsCheckCycle(downstreamPipelineId, targetPipelineId, visited, depth + 1);
            }
        }
    }

    /**
     * Resolve API token: SHA-256 hash and lookup.
     */
    public TriggerResponse resolveApiToken(String rawToken) {
        String tokenHash = sha256Hash(rawToken);
        return triggerRepository.findByTokenHash(tokenHash).orElse(null);
    }

    /**
     * Verify webhook HMAC-SHA256 signature.
     */
    public boolean verifyWebhookSignature(String webhookId, String body, String signature) {
        TriggerResponse trigger = triggerRepository.findByWebhookId(webhookId).orElse(null);
        if (trigger == null) {
            return false;
        }

        String secretEncrypted = (String) trigger.config().get("secretEncrypted");
        if (secretEncrypted == null || secretEncrypted.isEmpty()) {
            // No secret configured, accept all
            return true;
        }

        try {
            String secret = decryptSecret(secretEncrypted);
            String expectedSignature = hmacSha256(secret, body);
            // Compare with timing-safe check
            return MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("Failed to verify webhook signature: {}", e.getMessage());
            return false;
        }
    }

    public TriggerResponse findByWebhookId(String webhookId) {
        return triggerRepository.findByWebhookId(webhookId).orElse(null);
    }

    // --- Crypto utilities ---

    private String generateSecureToken() {
        byte[] tokenBytes = new byte[32];
        new SecureRandom().nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    private String sha256Hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 hashing failed", e);
        }
    }

    private String encryptSecret(String plaintext) {
        try {
            String key = getWebhookSecretKey();
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("AES encryption failed", e);
        }
    }

    private String decryptSecret(String ciphertext) {
        try {
            String key = getWebhookSecretKey();
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES decryption failed", e);
        }
    }

    private String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "sha256=" + hex;
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

    private String getWebhookSecretKey() {
        String key = System.getenv("WEBHOOK_SECRET_KEY");
        if (key == null || key.isEmpty()) {
            // Default key for development (32 bytes for AES-256)
            key = "SmartFireHub2026DefaultSecretKey";
        }
        // Normalize to exactly 32 bytes for AES-256
        byte[] keyBytes = Arrays.copyOf(key.getBytes(StandardCharsets.UTF_8), 32);
        return new String(keyBytes, StandardCharsets.UTF_8);
    }

    private void validateScheduleConfig(Map<String, Object> config) {
        if (!config.containsKey("cron") || config.get("cron") == null) {
            throw new IllegalArgumentException("cron expression is required for SCHEDULE trigger");
        }
        if (!config.containsKey("timezone")) {
            config.put("timezone", "Asia/Seoul");
        }
        if (!config.containsKey("concurrencyPolicy")) {
            config.put("concurrencyPolicy", "SKIP");
        }
        // Validate concurrency policy
        String policy = config.get("concurrencyPolicy").toString();
        ConcurrencyPolicy.valueOf(policy); // throws if invalid
    }
}
