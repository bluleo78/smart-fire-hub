package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.exception.CryptoException;
import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.exception.CyclicTriggerDependencyException;
import com.smartfirehub.pipeline.exception.TriggerNotFoundException;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
public class TriggerService {

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
    Map<String, Object> processedConfig =
        new HashMap<>(request.config() != null ? request.config() : Map.of());
    String rawApiToken = null;

    switch (request.triggerType()) {
      case SCHEDULE -> {
        validateScheduleConfig(processedConfig);
      }
      case API -> {
        // allowedIps 형식 검증 (IP 또는 CIDR만 허용)
        validateAllowedIps(processedConfig);
        // Generate API token
        rawApiToken = generateSecureToken();
        String tokenHash = sha256Hash(rawApiToken);
        processedConfig.put("tokenHash", tokenHash);
      }
      case PIPELINE_CHAIN -> {
        Object upstreamIdObj = processedConfig.get("upstreamPipelineId");
        if (upstreamIdObj == null) {
          throw new IllegalArgumentException(
              "upstreamPipelineId is required for PIPELINE_CHAIN trigger");
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
        if (datasetIds == null
            || !(datasetIds instanceof List)
            || ((List<?>) datasetIds).isEmpty()) {
          throw new IllegalArgumentException(
              "datasetIds is required and must not be empty for DATASET_CHANGE trigger");
        }
        // 폴링 주기 범위 검증: 30초 이상 3600초 이하
        int pollingInterval =
            ((Number) processedConfig.getOrDefault("pollingIntervalSeconds", 60)).intValue();
        if (pollingInterval < 30 || pollingInterval > 3600) {
          throw new IllegalArgumentException(
              "pollingIntervalSeconds must be between 30 and 3600");
        }
        // 디바운스 시간 범위 검증: 0초 이상 3600초 이하
        int debounceSeconds =
            ((Number) processedConfig.getOrDefault("debounceSeconds", 0)).intValue();
        if (debounceSeconds < 0 || debounceSeconds > 3600) {
          throw new IllegalArgumentException("debounceSeconds must be between 0 and 3600");
        }
      }
    }

    Long triggerId = triggerRepository.create(pipelineId, request, processedConfig, userId);

    // Register schedule after transaction commits
    if (request.triggerType() == TriggerType.SCHEDULE) {
      final Map<String, Object> finalConfig = processedConfig;
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              schedulerService.registerSchedule(triggerId, finalConfig);
            }
          });
    }

    TriggerResponse response =
        triggerRepository
            .findById(triggerId)
            .orElseThrow(
                () ->
                    new TriggerNotFoundException("Trigger not found after creation: " + triggerId));

    // If API trigger, return the raw token in a modified response (one-time only)
    if (rawApiToken != null) {
      Map<String, Object> configWithToken = new HashMap<>(response.config());
      configWithToken.put("rawToken", rawApiToken);
      response =
          new TriggerResponse(
              response.id(),
              response.pipelineId(),
              response.triggerType(),
              response.name(),
              response.description(),
              response.isEnabled(),
              configWithToken,
              response.triggerState(),
              response.createdBy(),
              response.createdAt());
    }

    return response;
  }

  @Transactional
  public void updateTrigger(Long triggerId, UpdateTriggerRequest request, Long userId) {
    TriggerResponse existing =
        triggerRepository
            .findById(triggerId)
            .orElseThrow(() -> new TriggerNotFoundException("Trigger not found: " + triggerId));

    // API 트리거 업데이트 시 allowedIps 형식 검증
    if ("API".equals(existing.triggerType()) && request.config() != null) {
      validateAllowedIps(request.config());
    }

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

    // DATASET_CHANGE 트리거 업데이트 시 폴링 주기·디바운스 범위 검증
    if ("DATASET_CHANGE".equals(existing.triggerType()) && request.config() != null) {
      Map<String, Object> updatedConfig = request.config();
      if (updatedConfig.containsKey("pollingIntervalSeconds")) {
        int pollingInterval = ((Number) updatedConfig.get("pollingIntervalSeconds")).intValue();
        if (pollingInterval < 30 || pollingInterval > 3600) {
          throw new IllegalArgumentException(
              "pollingIntervalSeconds must be between 30 and 3600");
        }
      }
      if (updatedConfig.containsKey("debounceSeconds")) {
        int debounceSeconds = ((Number) updatedConfig.get("debounceSeconds")).intValue();
        if (debounceSeconds < 0 || debounceSeconds > 3600) {
          throw new IllegalArgumentException("debounceSeconds must be between 0 and 3600");
        }
      }
    }

    triggerRepository.update(triggerId, request, userId);

    // If schedule trigger, re-register after commit
    if ("SCHEDULE".equals(existing.triggerType())) {
      Map<String, Object> config = request.config() != null ? request.config() : existing.config();
      boolean enabled = request.isEnabled() != null ? request.isEnabled() : existing.isEnabled();
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
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
    TriggerResponse existing =
        triggerRepository
            .findById(triggerId)
            .orElseThrow(() -> new TriggerNotFoundException("Trigger not found: " + triggerId));

    triggerRepository.delete(triggerId);

    // Unregister schedule after commit
    if ("SCHEDULE".equals(existing.triggerType())) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              schedulerService.unregisterSchedule(triggerId);
            }
          });
    }
  }

  @Transactional
  public void toggleTrigger(Long triggerId, boolean enabled) {
    TriggerResponse existing =
        triggerRepository
            .findById(triggerId)
            .orElseThrow(() -> new TriggerNotFoundException("Trigger not found: " + triggerId));

    triggerRepository.updateEnabled(triggerId, enabled);

    // Update scheduler after commit
    if ("SCHEDULE".equals(existing.triggerType())) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
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

  @Transactional(readOnly = true)
  public List<TriggerResponse> getTriggers(Long pipelineId) {
    return triggerRepository.findByPipelineId(pipelineId);
  }

  @Transactional(readOnly = true)
  public TriggerResponse getTriggerById(Long triggerId) {
    return triggerRepository
        .findById(triggerId)
        .orElseThrow(() -> new TriggerNotFoundException("Trigger not found: " + triggerId));
  }

  @Transactional(readOnly = true)
  public List<TriggerEventResponse> getTriggerEvents(Long pipelineId, int limit) {
    return triggerEventRepository.findByPipelineId(pipelineId, limit);
  }

  /** Fire a trigger: validate conditions, execute pipeline, record event. */
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
      triggerEventRepository.create(
          triggerId,
          trigger.pipelineId(),
          null,
          "SKIPPED",
          Map.of("reason", "Pipeline is not active"));
      return;
    }

    // Check concurrency policy for SCHEDULE triggers
    if ("SCHEDULE".equals(trigger.triggerType())) {
      String policy = (String) trigger.config().getOrDefault("concurrencyPolicy", "SKIP");
      if ("SKIP".equals(policy) && triggerRepository.hasRunningExecution(trigger.pipelineId())) {
        log.info(
            "Skipping trigger {} due to SKIP concurrency policy (running execution exists)",
            triggerId);
        triggerEventRepository.create(
            triggerId,
            trigger.pipelineId(),
            null,
            "SKIPPED",
            Map.of("reason", "Concurrent execution (SKIP policy)"));
        return;
      }
    }

    try {
      // Execute pipeline using trigger creator as executedBy
      PipelineExecutionResponse execution =
          pipelineService.executePipeline(
              trigger.pipelineId(), trigger.createdBy(), trigger.triggerType(), triggerId);

      // Record FIRED event
      triggerEventRepository.create(
          triggerId,
          trigger.pipelineId(),
          execution.id(),
          "FIRED",
          params.isEmpty() ? null : params);

      // Update trigger state with lastFiredAt
      Map<String, Object> state = new HashMap<>(trigger.triggerState());
      state.put("lastFiredAt", LocalDateTime.now().toString());
      triggerRepository.updateTriggerState(triggerId, state);

      log.info("Trigger {} fired successfully, execution {}", triggerId, execution.id());
    } catch (Exception e) {
      log.error("Failed to fire trigger {}: {}", triggerId, e.getMessage(), e);
      triggerEventRepository.create(
          triggerId, trigger.pipelineId(), null, "ERROR", Map.of("error", e.getMessage()));
    }
  }

  /** DFS cycle detection for pipeline chain triggers. */
  public void validatePipelineChain(Long targetPipelineId, Long upstreamPipelineId) {
    Set<Long> visited = new HashSet<>();
    visited.add(targetPipelineId);
    dfsCheckCycle(upstreamPipelineId, targetPipelineId, visited, 0);
  }

  private void dfsCheckCycle(
      Long currentPipelineId, Long targetPipelineId, Set<Long> visited, int depth) {
    if (depth > MAX_CHAIN_DEPTH) {
      throw new CyclicTriggerDependencyException(
          "Pipeline chain depth exceeds maximum of " + MAX_CHAIN_DEPTH);
    }

    // Find all chain triggers where currentPipelineId is the downstream pipeline
    List<TriggerResponse> chainTriggers =
        triggerRepository.findEnabledChainTriggersByUpstreamId(currentPipelineId);
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

  /** Resolve API token: SHA-256 hash and lookup. */
  @Transactional(readOnly = true)
  public TriggerResponse resolveApiToken(String rawToken) {
    String tokenHash = sha256Hash(rawToken);
    return triggerRepository.findByTokenHash(tokenHash).orElse(null);
  }

  /** Verify webhook HMAC-SHA256 signature. */
  @Transactional(readOnly = true)
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
          signature.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      log.error("Failed to verify webhook signature: {}", e.getMessage());
      return false;
    }
  }

  @Transactional(readOnly = true)
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
      throw new CryptoException("SHA-256 hashing failed", e);
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
      throw new CryptoException("AES encryption failed", e);
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
      throw new CryptoException("AES decryption failed", e);
    }
  }

  private String hmacSha256(String secret, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      SecretKeySpec keySpec =
          new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
      mac.init(keySpec);
      byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return "sha256=" + hex;
    } catch (Exception e) {
      throw new CryptoException("HMAC-SHA256 failed", e);
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
    // cron 형식 검증 및 정규화 (#55)
    // Spring CronExpression은 6필드(초 분 시 일 월 요일) 형식만 수용.
    // 5필드(Unix 표준: 분 시 일 월 요일) 입력은 앞에 "0 "을 붙여 정규화.
    String cron = config.get("cron").toString().trim();
    String normalizedCron = cron.split("\\s+").length == 5 ? "0 " + cron : cron;
    if (!CronExpression.isValidExpression(normalizedCron)) {
      throw new IllegalArgumentException("유효하지 않은 cron 표현식입니다: " + cron);
    }
    config.put("cron", normalizedCron);
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

  /**
   * API 트리거의 allowedIps 목록에 포함된 각 항목이 유효한 IPv4 주소 또는 CIDR 표기인지 검증한다. 잘못된 형식이 있으면
   * IllegalArgumentException(→ 400)을 던진다.
   */
  private void validateAllowedIps(Map<String, Object> config) {
    Object allowedIpsObj = config.get("allowedIps");
    if (allowedIpsObj == null) return;
    if (!(allowedIpsObj instanceof List<?> ipList)) return;

    for (Object item : ipList) {
      String entry = item == null ? "" : item.toString().trim();
      if (entry.isEmpty()) continue;

      String ipPart = entry.contains("/") ? entry.substring(0, entry.indexOf('/')) : entry;
      String prefixPart = entry.contains("/") ? entry.substring(entry.indexOf('/') + 1) : null;

      // 옥텟 범위 검증을 포함한 IPv4 주소 형식 확인
      if (!isValidIpv4(ipPart)) {
        throw new IllegalArgumentException("잘못된 IP 주소 형식입니다: " + entry + " (IPv4 또는 CIDR 표기법만 허용)");
      }

      // CIDR 프리픽스 범위 검증 (0~32)
      if (prefixPart != null) {
        try {
          int prefix = Integer.parseInt(prefixPart);
          if (prefix < 0 || prefix > 32) {
            throw new IllegalArgumentException("CIDR 프리픽스는 0~32 범위여야 합니다: " + entry);
          }
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("잘못된 CIDR 형식입니다: " + entry);
        }
      }
    }
  }

  /** 문자열이 유효한 IPv4 주소인지 확인한다. 각 옥텟이 0~255 범위인지 검사. */
  private boolean isValidIpv4(String ip) {
    String[] parts = ip.split("\\.", -1);
    if (parts.length != 4) return false;
    for (String part : parts) {
      try {
        int val = Integer.parseInt(part);
        if (val < 0 || val > 255) return false;
      } catch (NumberFormatException e) {
        return false;
      }
    }
    return true;
  }

  /**
   * 주어진 sourceIp가 allowedIps 목록의 CIDR 범위에 포함되는지 확인한다. allowedIps가 비어 있으면 모든 IP를 허용한다. IPv4-mapped
   * IPv6 주소(::ffff:x.x.x.x)는 IPv4로 변환하여 비교한다.
   *
   * @param sourceIp 요청 IP (IPv4 또는 ::ffff:x.x.x.x 형태의 IPv6)
   * @param allowedIps 허용 IP/CIDR 목록
   * @return 허용 여부
   */
  public boolean isIpAllowed(String sourceIp, List<String> allowedIps) {
    if (allowedIps == null || allowedIps.isEmpty()) return true;

    // IPv4-mapped IPv6 주소를 IPv4로 정규화 (예: ::1 → 127.0.0.1, ::ffff:192.168.1.1 → 192.168.1.1)
    String normalizedIp = normalizeToIpv4(sourceIp);

    for (String cidrEntry : allowedIps) {
      if (cidrEntry == null || cidrEntry.trim().isEmpty()) continue;
      try {
        if (isIpInCidr(normalizedIp, cidrEntry.trim())) {
          return true;
        }
      } catch (Exception e) {
        // 저장된 항목에 잘못된 형식이 있으면 skip (로그만 기록)
        log.warn("allowedIps 항목 파싱 실패 (skip): {}", cidrEntry);
      }
    }
    return false;
  }

  /** IPv4-mapped IPv6 주소(::ffff:A.B.C.D 또는 ::1)를 IPv4 문자열로 변환한다. 이미 IPv4이면 그대로 반환. */
  private String normalizeToIpv4(String ip) {
    if (ip == null) return "";
    // ::1 (IPv6 루프백) → 127.0.0.1
    if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) return "127.0.0.1";
    // ::ffff:x.x.x.x 형태 처리
    if (ip.startsWith("::ffff:") || ip.startsWith("::FFFF:")) {
      return ip.substring(7);
    }
    return ip;
  }

  /**
   * sourceIp가 cidrEntry(예: "192.168.1.0/24" 또는 단순 IP "192.168.1.5")에 속하는지 판단한다. 바이트 레벨 비트 마스크 연산으로
   * 정확하게 비교한다.
   */
  private boolean isIpInCidr(String sourceIp, String cidrEntry) throws UnknownHostException {
    if (!cidrEntry.contains("/")) {
      // 단순 IP 비교
      return sourceIp.equals(cidrEntry);
    }

    int slashIdx = cidrEntry.indexOf('/');
    String networkIpStr = cidrEntry.substring(0, slashIdx);
    int prefix = Integer.parseInt(cidrEntry.substring(slashIdx + 1));

    byte[] sourceBytes = InetAddress.getByName(sourceIp).getAddress();
    byte[] networkBytes = InetAddress.getByName(networkIpStr).getAddress();

    if (sourceBytes.length != networkBytes.length) return false;

    // 비트 마스크로 네트워크 주소 범위 비교
    int fullBytes = prefix / 8;
    int remainBits = prefix % 8;

    for (int i = 0; i < fullBytes; i++) {
      if (sourceBytes[i] != networkBytes[i]) return false;
    }

    if (remainBits > 0 && fullBytes < sourceBytes.length) {
      int mask = 0xFF & (0xFF << (8 - remainBits));
      if ((sourceBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) return false;
    }

    return true;
  }
}
