package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.exception.CryptoException;
import com.smartfirehub.global.security.MemberEligibility;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.exception.CyclicTriggerDependencyException;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
import com.smartfirehub.pipeline.exception.TriggerNotFoundException;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import com.smartfirehub.pipeline.repository.TriggerTenantResolver;
import com.smartfirehub.proactive.util.ProactiveCron;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.DatasetAction;
import com.smartfirehub.tenant.repository.TenantRepository;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
public class TriggerService {

  /**
   * 연쇄(PIPELINE_CHAIN) 한 줄기에 허용하는 최대 단계 수(간선 수). 저장 시에는 새 연쇄가 들어가는 가장 긴 줄기가 이 값을 넘으면 거부하고, 실행 시에는 이
   * 값을 넘는 단계의 발화를 건너뛴다 — 두 곳이 같은 값을 써야 "저장은 됐는데 실행에서 잘리는" 연쇄가 생기지 않는다(WD-106).
   */
  static final int MAX_CHAIN_DEPTH = 10;

  /**
   * 연쇄 발화 params 에 싣는 깊이 키(WD-106). {@link TriggerEventService} 가 넣고 {@link #fireTrigger} 가 상한 판정에
   * 쓰며, FIRED 이벤트 detail 에 남아 다음 단계가 이어 받는다.
   */
  public static final String CHAIN_DEPTH_PARAM = "chainDepth";

  /**
   * 파이프라인이 비활성이라 건너뛴 사유(WD-152). 이벤트 로그 detail.reason 과 외부 트리거 409 응답 본문이 같은 문구를 쓴다 — 화면과 호출자가 다른
   * 말을 하지 않게 한 곳에 둔다. 예전에는 영어 원문("Pipeline is not active")이라 화면에 그대로 보이지 못했다.
   */
  public static final String REASON_PIPELINE_INACTIVE = "파이프라인이 비활성 상태라 실행하지 않았습니다";

  /** 동시 실행 정책(건너뛰기)으로 건너뛴 사유(WD-152). 예전 영어 원문은 "Concurrent execution (SKIP policy)". */
  public static final String REASON_CONCURRENT_SKIP =
      "이미 실행 중인 실행이 있어 동시 실행 정책(건너뛰기)에 따라 실행하지 않았습니다";

  /** 트리거가 꺼져 있어 실행하지 않은 사유(WD-152). 외부 트리거 409 응답 본문에만 쓰인다 — 꺼진 트리거 발화는 이벤트를 남기지 않는다. */
  public static final String REASON_TRIGGER_DISABLED = "트리거가 꺼져 있어 실행하지 않았습니다";

  /**
   * 실행 시작 자체가 실패한 사유(WD-152). 오류 이벤트 detail.reason 과 외부 트리거 500 응답 본문에 쓰인다. 예외 원문은 내부 구조(SQL·경로)를
   * 드러낼 수 있어 인증 없는 외부 호출자에게 보내지 않고, 원문은 기존대로 detail.error 에만 남긴다.
   */
  public static final String REASON_FIRE_ERROR = "파이프라인 실행을 시작하지 못했습니다";

  /**
   * 실행 주체(트리거를 만든 사용자)에게 「파이프라인 실행」 권한이 없어 건너뛴 사유(WD-167). 트리거는 만든 사람 이름으로 실행되므로, 그 사람이 직접 실행할 수 없는
   * 파이프라인을 트리거로 돌리면 실행 권한을 우회하게 된다 — 권한을 회수해도 예약 트리거가 계속 돌던 결함.
   */
  public static final String REASON_EXECUTOR_NO_EXECUTE_PERMISSION =
      "트리거를 만든 사용자에게 「파이프라인 실행」 권한이 없어 실행하지 않았습니다. 그 사용자에게 권한을 주거나, 실행 권한이 있는 사용자가 트리거를"
          + " 다시 만들어 주세요";

  /**
   * 실행 주체(트리거를 만든 사람)의 계정이 비활성이거나, 이 워크스페이스에서 정지(SUSPENDED)·제거(멤버십 없음)돼 건너뛴 사유(WD-169). 예전에는 이 경우도
   * 권한 조회가 비어 「권한이 없어」 사유로 남아, 관리자가 권한을 줘야 하는지 사람을 복귀시켜야 하는지 구분할 수 없었다.
   */
  public static final String REASON_EXECUTOR_INACTIVE = "트리거를 만든 사람이 이 워크스페이스에서 정지·제거되어 실행하지 않았습니다";

  /**
   * 워크스페이스(테넌트)가 정지돼 실행하지 않은 사유(WD-174). 외부 트리거 409 응답 본문에만 쓰인다 — 정지 중 발화는 이벤트를 남기지 않는다. 외부 호출자는
   * 토큰·웹훅 주소를 가진 연동 주체라 정지 사실을 알려도 된다(연동 장애 원인을 알아야 한다).
   */
  public static final String REASON_TENANT_SUSPENDED = "워크스페이스가 정지되어 실행하지 않았습니다";

  /** 실행 주체에게 요구하는 권한 — 직접 실행 API 와 같다(WD-167). */
  private static final String EXECUTE_PERMISSION = "pipeline:execute";

  private final TriggerRepository triggerRepository;
  private final TriggerEventRepository triggerEventRepository;
  // 비인증 경로의 테넌트 해석 전용. 해싱은 이 서비스에 남기고 조회만 위임한다.
  private final TriggerTenantResolver triggerTenantResolver;
  // 순환 안내 문구에 파이프라인 이름을 싣기 위한 조회 전용(WD-106).
  private final PipelineRepository pipelineRepository;
  private final PipelineService pipelineService;
  private final TriggerSchedulerService schedulerService;
  // DATASET_CHANGE 감시 대상 VIEW 판정(보안 등급) — 저장하는 사용자가 볼 수 없는 데이터셋을 감시 대상으로 넣지 못하게.
  private final DatasetAccessGuard datasetAccessGuard;
  private final ClearanceResolver clearanceResolver;
  // 발화 시점에 실행 주체의 pipeline:execute 를 다시 확인한다(WD-167) — 스텝의 python_execute·ai_execute 재확인과 같은 방식
  // 실행 주체 자격 판정(계정 활성 → ACTIVE 멤버 → pipeline:execute). 사유 구분을 위해 권한만 보던 WD-167 확인을 대체한다(WD-169)
  private final MemberEligibility memberEligibility;
  // 자격 문제로 건너뛸 때 파이프라인 주인·관리자에게 처음 한 번만 알린다(WD-169)
  private final TriggerCreatorSkipNotifier creatorSkipNotifier;
  // 발화 시점 워크스페이스(테넌트) 상태 확인 — 정지된 워크스페이스의 트리거는 아예 실행하지 않는다(WD-174)
  private final TenantRepository tenantRepository;
  // 웹훅 시크릿 암호화·복호화(WD-220) — 공개 기본 키 대신 WEBHOOK_SECRET_KEY 또는 마스터키 파생 키를 쓰고, 옛 암호문은 레거시 키로 푼다
  private final WebhookSecretCipher webhookSecretCipher;

  public TriggerService(
      TriggerRepository triggerRepository,
      TriggerEventRepository triggerEventRepository,
      TriggerTenantResolver triggerTenantResolver,
      PipelineRepository pipelineRepository,
      @Lazy PipelineService pipelineService,
      @Lazy TriggerSchedulerService schedulerService,
      DatasetAccessGuard datasetAccessGuard,
      ClearanceResolver clearanceResolver,
      MemberEligibility memberEligibility,
      TriggerCreatorSkipNotifier creatorSkipNotifier,
      TenantRepository tenantRepository,
      @Value("${app.encryption.master-key}") String encryptionMasterKey) {
    this.triggerRepository = triggerRepository;
    this.triggerEventRepository = triggerEventRepository;
    this.triggerTenantResolver = triggerTenantResolver;
    this.pipelineRepository = pipelineRepository;
    this.pipelineService = pipelineService;
    this.schedulerService = schedulerService;
    this.datasetAccessGuard = datasetAccessGuard;
    this.clearanceResolver = clearanceResolver;
    this.memberEligibility = memberEligibility;
    this.creatorSkipNotifier = creatorSkipNotifier;
    this.tenantRepository = tenantRepository;
    // WEBHOOK_SECRET_KEY 는 예전부터 환경변수로만 읽었다 — 같은 이름을 그대로 존중한다(설정한 배포의 옛 암호문 호환).
    this.webhookSecretCipher =
        new WebhookSecretCipher(System.getenv("WEBHOOK_SECRET_KEY"), encryptionMasterKey);
  }

  /**
   * DATASET_CHANGE 감시 대상 중 <b>새로 추가되는</b> id 를 저장자 자격으로 판정한다(보안 등급). 판정이 없으면 숨김 데이터셋의 변경 시점이 트리거
   * 발화로 드러나고, 숨김 id 는 201·없는 id 도 201 이라도 이후 발화 여부로 존재가 갈린다. 숨김·없는·형식이 잘못된 id 는 같은 403
   * (DATASET_SQL_ACCESS_DENIED — 파이프라인 저장 거부와 같은 구분 불가 응답).
   *
   * <p>이미 저장돼 있던 id(저장자가 자격을 잃은 뒤 웹이 그대로 되돌려 보내는 값)는 판정하지 않는다 — 막으면 다른 설정만 고쳐도 저장할 수 없다(왕복 보존). 실행
   * 시점에는 트리거 생성자(실행 주체) 기준으로 파이프라인 관문이 다시 판정한다.
   *
   * @param requested 요청 config 의 datasetIds 원값(List 가 아니면 호출부의 기존 검증이 처리한다)
   * @param existing 이미 저장된 config 의 datasetIds(신규 생성이면 null)
   */
  private void requireNewWatchedDatasetsVisible(Object requested, Object existing, Long userId) {
    if (!(requested instanceof List<?> requestedList)) {
      return;
    }
    Set<Long> already = new HashSet<>();
    if (existing instanceof List<?> existingList) {
      for (Object o : existingList) {
        if (o instanceof Number n) {
          already.add(n.longValue());
        }
      }
    }
    List<Long> added = new ArrayList<>();
    for (Object o : requestedList) {
      // 호출부가 먼저 원소를 정수로 정규화하고 형식 오류는 400 으로 거부한다(WD-225). 그래도 숫자가 아닌 원소가 오면 null 로 넘겨 가드가
      // 같은 거부 응답을 내게 한다(형식 오류는 존재 여부를 드러내지 않는다).
      Long id = o instanceof Number n ? n.longValue() : null;
      if (id == null || !already.contains(id)) {
        added.add(id);
      }
    }
    if (!added.isEmpty()) {
      datasetAccessGuard.requireDatasetReads(clearanceResolver.resolve(userId), added);
    }
  }

  @Transactional
  public TriggerResponse createTrigger(Long pipelineId, CreateTriggerRequest request, Long userId) {
    // 없는 파이프라인에 트리거를 만들려 하면 FK 위반(409 「연결할 항목을 찾을 수 없습니다」)이 아니라 404 로 답한다(WD-228).
    // RLS 가 걸린 일반 조회라 다른 테넌트의 파이프라인도 "없음"과 같은 답이 된다. 설정 검증(400)보다 먼저 확인한다.
    if (!pipelineRepository.existsById(pipelineId)) {
      throw new PipelineNotFoundException();
    }
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
        Long upstreamPipelineId = chainUpstreamOf(processedConfig);
        if (upstreamPipelineId == null) {
          throw new IllegalArgumentException(
              "연쇄 트리거에는 먼저 끝나야 할 파이프라인(upstreamPipelineId)을 지정해야 합니다.");
        }
        if (upstreamPipelineId.equals(pipelineId)) {
          throw new CyclicTriggerDependencyException(SELF_CHAIN_MESSAGE);
        }
        // 없는(삭제된) 선행을 가리키는 연쇄는 영원히 발화하지 않는다 — 활성으로 만들어지기 전에 거부한다(WD-218).
        requireUpstreamExists(upstreamPipelineId);
        // 새 트리거는 활성 상태로 만들어지므로 곧바로 연쇄 그래프의 간선이 된다 — 순환·길이를 검사한다.
        validatePipelineChain(pipelineId, upstreamPipelineId, null);
        validateChainCondition(processedConfig.getOrDefault("condition", "SUCCESS"));
      }
      case WEBHOOK -> {
        // 서버 소유 키는 클라이언트가 넣지 못한다(WD-220) — 암호문을 직접 넣어 시크릿을 심거나 응답 표시값이 저장되는 것을 막는다.
        processedConfig.remove(WEBHOOK_SECRET_ENCRYPTED);
        processedConfig.remove(WEBHOOK_HAS_SECRET);
        String webhookId = UUID.randomUUID().toString();
        processedConfig.put("webhookId", webhookId);
        // Encrypt secret if provided
        Object secret = processedConfig.get("secret");
        if (secret != null && !secret.toString().isEmpty()) {
          String encrypted = webhookSecretCipher.encrypt(secret.toString());
          processedConfig.put(WEBHOOK_SECRET_ENCRYPTED, encrypted);
        }
        // 빈 문자열이어도 평문 키는 저장하지 않는다.
        processedConfig.remove(WEBHOOK_SECRET_PLAIN);
      }
      case DATASET_CHANGE -> {
        // 감시 목록은 비어 있지 않은 목록이어야 한다 — 키 없음·목록 아님·빈 목록은 같은 안내로 거부한다.
        if (!(processedConfig.get(DatasetChangeTriggerConfig.DATASET_IDS_KEY)
                instanceof List<?> ids)
            || ids.isEmpty()) {
          throw new IllegalArgumentException(
              DatasetChangeTriggerConfig.DATASET_IDS_REQUIRED_MESSAGE);
        }
        // 폴링 주기(30~3600)·디바운스(0~3600)·감시 목록 원소의 형식 오류는 400 한국어로 거부하고, 저장 config 에는 정규화한 정수를
        // 넣는다(WD-225) — "60" 같은 문자열이 그대로 저장되면
        // 조회·폴러가 저장 검증과 다른 값을 보게 된다.
        processedConfig = DatasetChangeTriggerConfig.normalizedForSave(processedConfig);
        Object datasetIds = processedConfig.get(DatasetChangeTriggerConfig.DATASET_IDS_KEY);
        requireNewWatchedDatasetsVisible(datasetIds, null, userId);
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
              // 커밋 후 등록 실패는 응답을 깨지 않고 "스케줄 없음"으로 정리한다(WD-107) — 본 방어선은 위 커밋 전 검증이다.
              schedulerService.registerScheduleAfterCommit(triggerId, finalConfig);
            }
          });
    }

    TriggerResponse response =
        triggerRepository.findById(triggerId).orElseThrow(TriggerNotFoundException::new);
    // 생성 응답도 조회 응답과 같은 마스킹을 거친다(WD-220) — 웹훅 암호문이 생성 직후 응답으로 새지 않게.
    response = toPublic(response);

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
              response.nextFireTime(),
              response.createdBy(),
              response.createdAt());
    }

    return response;
  }

  /**
   * 경로의 파이프라인에 속한 트리거만 수정한다(WD-228) — HTTP 단건 API 용. 트리거가 없거나 다른 파이프라인의 트리거면 똑같이 404 로 답해 다른 파이프라인
   * 트리거의 존재를 드러내지 않는다.
   */
  @Transactional
  public void updateTrigger(
      Long pipelineId, Long triggerId, UpdateTriggerRequest request, Long userId) {
    requireTriggerInPipeline(pipelineId, triggerId);
    updateTrigger(triggerId, request, userId);
  }

  /** 트리거 id 만으로 수정한다 — 소속 파이프라인을 확인하지 않으므로 경로에 pipelineId 가 있는 API 는 위 오버로드를 쓴다. */
  @Transactional
  public void updateTrigger(Long triggerId, UpdateTriggerRequest request, Long userId) {
    TriggerResponse existing =
        triggerRepository.findById(triggerId).orElseThrow(TriggerNotFoundException::new);

    // API 트리거 업데이트 시 allowedIps 형식 검증
    if ("API".equals(existing.triggerType()) && request.config() != null) {
      validateAllowedIps(request.config());
    }

    // PIPELINE_CHAIN: 수정 후의 상태(상류·활성 여부)로 연쇄 간선이 새로 생기거나 바뀌면 검사한다(WD-106).
    if ("PIPELINE_CHAIN".equals(existing.triggerType())) {
      validateChainOnUpdate(existing, request);
    }

    // DATASET_CHANGE 트리거 업데이트 시 폴링 주기·디바운스·감시 목록 형식 검증(WD-225). 요청에 있는 키만 검증하고(키 없음·null 은 기본값),
    // 정규화한 정수로 바꾼 config 를 저장한다 — config 는 통째로 바뀌므로 정규화한 사본으로 요청을 바꿔 끼운다.
    if ("DATASET_CHANGE".equals(existing.triggerType()) && request.config() != null) {
      Map<String, Object> updatedConfig =
          DatasetChangeTriggerConfig.normalizedForSave(request.config());
      request =
          new UpdateTriggerRequest(
              request.name(), request.isEnabled(), request.description(), updatedConfig);
      requireNewWatchedDatasetsVisible(
          updatedConfig.get("datasetIds"), existing.config().get("datasetIds"), userId);
      // config 수정은 저장된 config 를 통째로 바꾼다 — 감시 목록이 비면 영원히 발화하지 않는 트리거가 되므로 거부한다(WD-221).
      requireWatchedDatasets(updatedConfig);
    }
    // config 없이 꺼진 데이터 변경 트리거를 켜는 수정은 토글 켜기와 같다 — 감시 대상이 모두 삭제돼 꺼진 트리거는 켤 수 없다(WD-221).
    boolean enablesDatasetChange =
        "DATASET_CHANGE".equals(existing.triggerType())
            && !existing.isEnabled()
            && Boolean.TRUE.equals(request.isEnabled());
    if (enablesDatasetChange && request.config() == null) {
      requireWatchedDatasets(existing.config());
    }

    // SCHEDULE: 생성과 같은 검증·정규화를 저장 전에 적용한다(WD-107). 예전에는 수정 경로가 이를 건너뛰어 5필드 cron(웹 프리셋
    // "매일 09:00" 등)이나 잘못된 cron 이 그대로 저장됐고, 커밋 후 등록만 실패해 204 "수정되었습니다" 뒤로 스케줄이 사라졌다.
    // 기존 config 위에 요청 config 를 덮어 병합한 뒤 검증한다 — 수정 요청이 cron 만 보내도 기존 timezone·concurrencyPolicy
    // 가 지워지지 않게 하기 위함이다(SCHEDULE config 키는 이 셋뿐이라 병합으로 남는 낡은 키가 없다). config 를 보내지 않은 수정(이름만
    // 바꾸기·끄기)은 검증하지 않는다 — 이미 잘못 저장된 값 때문에 이름 변경·비활성화까지 막히지 않게 한다.
    if ("SCHEDULE".equals(existing.triggerType()) && request.config() != null) {
      Map<String, Object> merged = new HashMap<>(existing.config());
      merged.putAll(request.config());
      validateScheduleConfig(merged);
      // 저장(repository.update)과 커밋 후 재등록이 모두 정규화된 config 를 쓰도록 요청을 바꿔 끼운다.
      request =
          new UpdateTriggerRequest(
              request.name(), request.isEnabled(), request.description(), merged);
    } else if ("SCHEDULE".equals(existing.triggerType())
        && !existing.isEnabled()
        && Boolean.TRUE.equals(request.isEnabled())) {
      // config 없이 꺼진 트리거를 켜는 수정은 토글로 켜기와 같다 — 저장된 config 로 실제로 스케줄을 걸 수 있는지 커밋 전에 확인한다.
      // 사본으로 검증해 저장값은 건드리지 않는다(등록 시 5필드는 어차피 정규화된다).
      validateScheduleConfig(new HashMap<>(existing.config()));
    }

    // WEBHOOK: config 는 통째로 바뀌므로(repository.update) 서버 소유 키를 DB 값으로 되살린다(WD-220). 응답에는 암호문이 없어서,
    // 웹 편집 창이 받은 config 를 그대로 되돌려 보내면 시크릿이 지워지고 서명 없는 호출을 모두 받아들이게 된다. 클라이언트가 보낸
    // 암호문·웹훅 id 는 무시한다(다른 시크릿 심기·다른 웹훅 주소 가로채기 방지). 수정 경로에는 시크릿 재설정 기능이 없으므로(웹도 수정 모드에서
    // 입력을 막는다) 평문 secret 도 버린다 — 예전에는 평문이 그대로 저장됐다.
    if ("WEBHOOK".equals(existing.triggerType()) && request.config() != null) {
      request =
          new UpdateTriggerRequest(
              request.name(),
              request.isEnabled(),
              request.description(),
              withServerOwnedWebhookKeys(request.config(), existing.config()));
    }

    triggerRepository.update(triggerId, request, userId);
    if (enablesDatasetChange) {
      // 꺼진 동안의 변경으로 켜자마자 발화하지 않게 기준선을 새로 잡는다(WD-221).
      triggerRepository.clearPollSnapshot(triggerId);
    }

    // If schedule trigger, re-register after commit
    if ("SCHEDULE".equals(existing.triggerType())) {
      boolean configChanged = request.config() != null;
      Map<String, Object> config = configChanged ? request.config() : existing.config();
      boolean enabled = request.isEnabled() != null ? request.isEnabled() : existing.isEnabled();
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              if (enabled) {
                // config 를 바꾸지 않은 수정(이름·설명)이고 이미 돌고 있는 스케줄이 있으면 다시 등록하지 않는다(WD-107) — 바뀐 게 없고,
                // 저장값이 검증 이전의 잘못된 옛 행이면 재등록이 실패할 수도 있다.
                if (!configChanged && schedulerService.isScheduled(triggerId)) {
                  return;
                }
                schedulerService.registerScheduleAfterCommit(triggerId, config);
              } else {
                schedulerService.unregisterSchedule(triggerId);
              }
            }
          });
    }
  }

  /** 경로의 파이프라인에 속한 트리거만 지운다(WD-228). 없거나 다른 파이프라인의 트리거면 404. */
  @Transactional
  public void deleteTrigger(Long pipelineId, Long triggerId) {
    requireTriggerInPipeline(pipelineId, triggerId);
    deleteTrigger(triggerId);
  }

  /** 트리거 id 만으로 지운다 — 소속 파이프라인을 확인하지 않는다. */
  @Transactional
  public void deleteTrigger(Long triggerId) {
    TriggerResponse existing =
        triggerRepository.findById(triggerId).orElseThrow(TriggerNotFoundException::new);

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

  /** 경로의 파이프라인에 속한 트리거만 켜고 끈다(WD-228). 없거나 다른 파이프라인의 트리거면 404. */
  @Transactional
  public void toggleTrigger(Long pipelineId, Long triggerId, boolean enabled) {
    requireTriggerInPipeline(pipelineId, triggerId);
    toggleTrigger(triggerId, enabled);
  }

  /** 트리거 id 만으로 켜고 끈다 — 소속 파이프라인을 확인하지 않는다. */
  @Transactional
  public void toggleTrigger(Long triggerId, boolean enabled) {
    TriggerResponse existing =
        triggerRepository.findById(triggerId).orElseThrow(TriggerNotFoundException::new);

    // 꺼져 있던 연쇄 트리거를 켜면 그 순간 연쇄 간선이 생긴다 — 꺼진 동안 반대 방향 연쇄가 추가됐을 수 있으므로 여기서도
    // 순환·길이를 검사한다(WD-106). 꺼진 트리거는 순환 계산에서 빠지므로 생성·수정 검사만으로는 이 경로가 비어 있었다.
    if (enabled && "PIPELINE_CHAIN".equals(existing.triggerType())) {
      // 켤 때만 상류를 해석한다 — 끄는 것은 잘못 저장된 값이 있어도 막지 않는다.
      Long upstreamPipelineId = chainUpstreamOf(existing.config());
      // 선행이 삭제돼 자동으로 꺼진 트리거를 다시 켜면 발화 대상이 없어 영원히 무동작이다 — 켜기를 거부한다(WD-218).
      if (upstreamPipelineId != null) {
        requireUpstreamExists(upstreamPipelineId);
      }
      if (addsNewActiveChainEdge(existing, upstreamPipelineId, enabled)) {
        validatePipelineChain(existing.pipelineId(), upstreamPipelineId, existing.id());
      }
    }

    // 스케줄 트리거를 켤 때는 저장된 config 로 실제로 스케줄을 걸 수 있는지 커밋 전에 확인해 400 으로 막는다(WD-107). 예전엔 검증 없이
    // 켜져 커밋 후 등록만 조용히 실패했다. 사본으로 검증해 저장값은 건드리지 않는다. 끄는 것은 잘못 저장된 값이 있어도 막지 않는다.
    if (enabled && "SCHEDULE".equals(existing.triggerType())) {
      validateScheduleConfig(new HashMap<>(existing.config()));
    }

    // 데이터 변경 트리거: 감시 대상이 모두 삭제돼 꺼진 트리거는 켜도 감시할 것이 없다 — 켜기를 거부한다(WD-221). 끄기는 늘 허용한다.
    boolean enablesDatasetChange =
        enabled && !existing.isEnabled() && "DATASET_CHANGE".equals(existing.triggerType());
    if (enablesDatasetChange) {
      requireWatchedDatasets(existing.config());
    }

    triggerRepository.updateEnabled(triggerId, enabled);
    if (enablesDatasetChange) {
      // 다시 켠 트리거는 직전 기준선이 없는 것으로 본다 — 꺼진 동안의 변경으로 켜자마자 발화하지 않게(WD-221).
      triggerRepository.clearPollSnapshot(triggerId);
    }

    // Update scheduler after commit
    if ("SCHEDULE".equals(existing.triggerType())) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              if (enabled) {
                schedulerService.registerScheduleAfterCommit(triggerId, existing.config());
              } else {
                schedulerService.unregisterSchedule(triggerId);
              }
            }
          });
    }
  }

  @Transactional(readOnly = true)
  public List<TriggerResponse> getTriggers(Long pipelineId) {
    // 스냅숏 제거가 레코드를 다시 만들므로 선행 없음 표시는 그 뒤에 붙인다(WD-218).
    return withUpstreamMissing(
        triggerRepository.findByPipelineId(pipelineId).stream()
            .map(TriggerService::toPublic)
            .map(TriggerService::withWatchedDatasetsMissing)
            .toList());
  }

  /** 경로의 파이프라인에 속한 트리거만 조회한다(WD-228). 없거나 다른 파이프라인의 트리거면 404. */
  @Transactional(readOnly = true)
  public TriggerResponse getTriggerById(Long pipelineId, Long triggerId) {
    requireTriggerInPipeline(pipelineId, triggerId);
    return getTriggerById(triggerId);
  }

  /**
   * 트리거가 경로의 파이프라인 소속인지 확인한다(WD-228). 예전엔 단건 API 가 경로의 pipelineId 를 무시해 {@code
   * /pipelines/99999/triggers/1} 로 파이프라인 1 의 트리거를 읽고 고칠 수 있었다. 없는 트리거와 다른 파이프라인의 트리거를 같은 404 로 답한다.
   */
  private void requireTriggerInPipeline(Long pipelineId, Long triggerId) {
    triggerRepository
        .findById(triggerId)
        .filter(t -> Objects.equals(t.pipelineId(), pipelineId))
        .orElseThrow(TriggerNotFoundException::new);
  }

  /** 트리거 id 만으로 조회한다 — 소속 파이프라인을 확인하지 않으므로 경로에 pipelineId 가 있는 API 는 위 오버로드를 쓴다. */
  @Transactional(readOnly = true)
  public TriggerResponse getTriggerById(Long triggerId) {
    TriggerResponse trigger =
        triggerRepository
            .findById(triggerId)
            .map(TriggerService::toPublic)
            .map(TriggerService::withWatchedDatasetsMissing)
            .orElseThrow(TriggerNotFoundException::new);
    return withUpstreamMissing(List.of(trigger)).get(0);
  }

  /**
   * 트리거 이벤트 목록. 데이터 변경 트리거 이벤트의 {@code detail.changedDatasets} 에서 조회자가 볼 수 없는 데이터셋은 뺀다(WD-161) —
   * 그대로 두면 숨김(상위 등급) 데이터셋이 <b>언제 바뀌었는지</b>가 드러난다. 판정은 데이터셋 상세와 같은 VIEW(없는 데이터셋도 거부)이고, 이벤트 여러 개에 같은
   * id 가 나와도 한 번만 판정한다.
   */
  @Transactional(readOnly = true)
  public List<TriggerEventResponse> getTriggerEvents(Long pipelineId, int limit) {
    List<TriggerEventResponse> events = triggerEventRepository.findByPipelineId(pipelineId, limit);
    Clearance viewer = null;
    Map<Long, Boolean> visible = new HashMap<>();
    List<TriggerEventResponse> out = new ArrayList<>(events.size());
    for (TriggerEventResponse e : events) {
      if (e.detail() == null || !(e.detail().get(CHANGED_DATASETS) instanceof List<?> ids)) {
        out.add(e);
        continue;
      }
      if (viewer == null) {
        viewer = clearanceResolver.current();
      }
      final Clearance c = viewer;
      List<Object> kept = new ArrayList<>();
      for (Object id : ids) {
        Long datasetId = id instanceof Number n ? n.longValue() : null;
        if (datasetId != null
            && visible.computeIfAbsent(
                datasetId,
                k -> datasetAccessGuard.check(c, k, DatasetAction.VIEW, null).allowed())) {
          kept.add(id);
        }
      }
      Map<String, Object> detail = new HashMap<>(e.detail());
      detail.put(CHANGED_DATASETS, kept);
      out.add(
          new TriggerEventResponse(
              e.id(),
              e.triggerId(),
              e.triggerName(),
              e.eventType(),
              e.executionId(),
              detail,
              e.createdAt()));
    }
    return out;
  }

  /** 데이터 변경 트리거 이벤트 detail 의 변경 데이터셋 id 목록 키({@code TriggerEventService} 가 쓴다). */
  private static final String CHANGED_DATASETS = "changedDatasets";

  /** 데이터 변경 트리거 폴러의 내부 상태 키 — 감시 데이터셋별 행 수({@code TriggerEventService}). */
  private static final String POLL_SNAPSHOT = "lastSnapshot";

  /** 웹훅 config 의 저장 암호문 키 — 응답에서 빼고, 수정 시 DB 값을 유지한다(WD-220). */
  private static final String WEBHOOK_SECRET_ENCRYPTED = "secretEncrypted";

  /** 웹훅 config 의 응답 전용 표시 — 시크릿 설정 여부. 저장하지 않는다(WD-220). */
  static final String WEBHOOK_HAS_SECRET = "hasSecret";

  /** 생성 요청의 평문 시크릿 키 — 암호화한 뒤 저장하지 않는다. */
  private static final String WEBHOOK_SECRET_PLAIN = "secret";

  /** 웹훅 주소를 이루는 서버 발급 id. */
  private static final String WEBHOOK_ID = "webhookId";

  /**
   * 트리거 응답 공통 마스킹: 폴러 스냅숏 제거(WD-161) + 웹훅 시크릿 암호문 제거(WD-220).
   *
   * <p>웹훅 암호문은 예전 기본 키(공개 소스)로 풀 수 있어 조회 권한만 있는 사용자가 서명을 위조할 수 있었다. 화면이 「시크릿 설정됨」을 보일 수 있게 대신
   * {@code hasSecret} 만 싣는다. 웹훅 id 는 주소 표시에 필요해 남긴다. 저장값은 그대로라 서명 검증은 바뀌지 않는다.
   */
  static TriggerResponse toPublic(TriggerResponse r) {
    TriggerResponse masked = withoutPollSnapshot(r);
    if (!"WEBHOOK".equals(masked.triggerType())) {
      return masked;
    }
    Map<String, Object> config =
        new HashMap<>(masked.config() != null ? masked.config() : Map.of());
    Object enc = config.remove(WEBHOOK_SECRET_ENCRYPTED);
    // 옛 수정 경로가 평문 secret 을 저장했을 수 있다 — 응답에 싣지 않는다.
    config.remove(WEBHOOK_SECRET_PLAIN);
    config.put(WEBHOOK_HAS_SECRET, enc instanceof String s && !s.isEmpty());
    return new TriggerResponse(
        masked.id(),
        masked.pipelineId(),
        masked.triggerType(),
        masked.name(),
        masked.description(),
        masked.isEnabled(),
        config,
        masked.triggerState(),
        masked.nextFireTime(),
        masked.createdBy(),
        masked.createdAt(),
        masked.upstreamMissing(),
        masked.watchedDatasetsMissing());
  }

  /**
   * 웹훅 수정 요청 config 에서 서버 소유 키(암호문·웹훅 id·표시값·평문 시크릿)를 버리고 저장된 값으로 되살린 사본(WD-220). 저장된 값이 없으면 없는 채로
   * 둔다 — 시크릿 없던 웹훅에 수정으로 시크릿을 심을 수 없다.
   */
  static Map<String, Object> withServerOwnedWebhookKeys(
      Map<String, Object> requested, Map<String, Object> stored) {
    Map<String, Object> merged = new HashMap<>(requested);
    merged.remove(WEBHOOK_SECRET_ENCRYPTED);
    merged.remove(WEBHOOK_HAS_SECRET);
    merged.remove(WEBHOOK_SECRET_PLAIN);
    merged.remove(WEBHOOK_ID);
    if (stored != null) {
      if (stored.get(WEBHOOK_SECRET_ENCRYPTED) != null) {
        merged.put(WEBHOOK_SECRET_ENCRYPTED, stored.get(WEBHOOK_SECRET_ENCRYPTED));
      }
      if (stored.get(WEBHOOK_ID) != null) {
        merged.put(WEBHOOK_ID, stored.get(WEBHOOK_ID));
      }
    }
    return merged;
  }

  /**
   * 조회 응답에서 데이터 변경 폴러의 행 수 스냅숏을 뺀다(WD-161).
   *
   * <p>{@code triggerState.lastSnapshot} 은 {@code {데이터셋id: 행 수}} 로, 폴러가 다음 확인 때 비교하려고 남기는 내부 상태다.
   * 조회자의 등급과 상관없이 실제 행 수를 담고 있어, 볼 수 없는(상위 등급) 데이터셋의 행 수와 그 변화를 트리거 조회로 지켜볼 수 있었다 — 데이터셋 행 수 API 는
   * 같은 사용자에게 404 다. 화면·AI 어디서도 이 값을 쓰지 않으므로 조회자와 상관없이 응답에서 뺀다(저장된 상태는 그대로라 폴링 동작은 바뀌지 않는다).
   */
  private static TriggerResponse withoutPollSnapshot(TriggerResponse r) {
    if (r.triggerState() == null || !r.triggerState().containsKey(POLL_SNAPSHOT)) {
      return r;
    }
    Map<String, Object> state = new HashMap<>(r.triggerState());
    state.remove(POLL_SNAPSHOT);
    return new TriggerResponse(
        r.id(),
        r.pipelineId(),
        r.triggerType(),
        r.name(),
        r.description(),
        r.isEnabled(),
        r.config(),
        state,
        r.nextFireTime(),
        r.createdBy(),
        r.createdAt());
  }

  /**
   * 트리거를 발화한다: 조건을 확인하고 파이프라인을 실행한 뒤 이벤트를 남긴다.
   *
   * <p>결과를 {@link TriggerFireResult} 로 돌려준다(WD-152). 외부 트리거(API·웹훅) 컨트롤러는 이 값으로 "실제로 실행됐는지"를 응답에
   * 반영한다 — 예전에는 {@code void} 라 실행되지 않은 호출에도 200 「triggered」가 나갔다. 스케줄·연쇄·데이터 변경 경로는 결과를 쓰지 않고, 사유는
   * 이벤트 로그(한국어)로만 남는다.
   */
  @Transactional
  public TriggerFireResult fireTrigger(Long triggerId, Map<String, Object> params) {
    // 워크스페이스 정지 관문(WD-174) — 다른 어떤 판정보다 먼저 본다. 예약(cron 콜백·놓친 발화)·연쇄·데이터 변경·외부 API·웹훅이 모두 이
    // 메서드를 지나므로 여기 한 곳이 모든 트리거 경로의 차단 지점이다. 예전에는 정지 후에도 등록된 cron 이 남아 매 주기 실행이 COMPLETED 됐고,
    // WD-169 이후에는 만든 사람 자격 판정(hasActiveMembership 이 테넌트 ACTIVE 도 본다)에 걸려 「만든 사람이 정지·제거」라는 틀린 사유의
    // SKIPPED 이벤트와 관리자 알림이 쌓였다. 정지는 운영자가 의도한 상태이므로 이벤트·알림·상태 갱신 없이 log.info 한 줄로 끝낸다.
    // 외부 호출 경로에서는 토큰·IP·서명 검사를 이미 통과한 뒤라 정지 사실을 돌려줘도 인증 전 정보 노출이 아니다.
    long firingTenantId = TenantContext.require("트리거 발화 워크스페이스 상태 확인");
    if (!tenantRepository.isActive(firingTenantId)) {
      log.info(
          "Trigger {} not fired: tenant {} is not active (suspended)", triggerId, firingTenantId);
      return TriggerFireResult.notFired(
          TriggerFireResult.Outcome.TENANT_SUSPENDED, REASON_TENANT_SUSPENDED);
    }

    TriggerResponse trigger;
    try {
      trigger = triggerRepository.findById(triggerId).orElse(null);
    } catch (Exception e) {
      log.warn("Failed to find trigger {} during fire: {}", triggerId, e.getMessage());
      return TriggerFireResult.notFired(TriggerFireResult.Outcome.ERROR, REASON_FIRE_ERROR);
    }

    if (trigger == null) {
      log.warn("Trigger {} not found during fire (possibly deleted)", triggerId);
      return TriggerFireResult.notFired(TriggerFireResult.Outcome.NOT_FOUND, null);
    }

    // 꺼진 트리거는 이벤트를 남기지 않는다(기존 동작 유지) — 외부 호출자가 꺼진 트리거를 반복 호출해도 이벤트 로그가 넘치지 않게 한다.
    // 결과만 돌려줘 외부 트리거 컨트롤러가 "꺼져 있음"을 응답할 수 있게 한다(WD-152).
    if (!trigger.isEnabled()) {
      log.info("Trigger {} is disabled, skipping", triggerId);
      return TriggerFireResult.notFired(
          TriggerFireResult.Outcome.DISABLED, REASON_TRIGGER_DISABLED);
    }

    // 실행 시 연쇄 깊이 방어(WD-106): 저장 시 검사를 빠져나간 순환(검사 결함 이전에 저장된 것 포함)이 있어도 연쇄가 끝없이
    // 서로를 부르지 않게, 상한을 넘는 단계는 발화하지 않고 건너뜀 이벤트로 남긴다. 깊이는 연쇄 트리거에만 의미가 있다 —
    // 다른 유형의 params 는 외부 호출자가 채우므로 판정에 쓰지 않는다.
    if ("PIPELINE_CHAIN".equals(trigger.triggerType())
        && params.get(CHAIN_DEPTH_PARAM) instanceof Number depth
        && depth.intValue() > MAX_CHAIN_DEPTH) {
      log.warn(
          "PIPELINE_CHAIN 트리거 {} 발화를 건너뛴다: 연쇄 깊이 {} 가 상한 {} 를 넘는다 (pipeline={}). "
              + "연쇄가 순환하고 있을 수 있다 — 트리거 구성을 확인할 것.",
          triggerId,
          depth.intValue(),
          MAX_CHAIN_DEPTH,
          trigger.pipelineId());
      Map<String, Object> detail = new HashMap<>(params);
      String reason =
          "연쇄 실행이 최대 " + MAX_CHAIN_DEPTH + "단계를 넘어 더 실행하지 않았습니다. 파이프라인 연쇄가 서로를 부르는 순환인지 확인해 주세요.";
      detail.put("reason", reason);
      triggerEventRepository.create(triggerId, trigger.pipelineId(), null, "SKIPPED", detail);
      return TriggerFireResult.notFired(TriggerFireResult.Outcome.SKIPPED, reason);
    }

    // Check if pipeline is active
    if (!triggerRepository.isPipelineActive(trigger.pipelineId())) {
      log.info("Pipeline {} is not active, skipping trigger {}", trigger.pipelineId(), triggerId);
      triggerEventRepository.create(
          triggerId,
          trigger.pipelineId(),
          null,
          "SKIPPED",
          Map.of("reason", REASON_PIPELINE_INACTIVE));
      return TriggerFireResult.notFired(
          TriggerFireResult.Outcome.SKIPPED, REASON_PIPELINE_INACTIVE);
    }

    // 실행 주체(트리거를 만든 사용자)가 지금 이 파이프라인을 직접 실행할 수 있어야 한다(WD-167). 직접 실행 API 는 pipeline:execute 를 요구하는데
    // 트리거 경로는 확인하지 않아, 트리거 권한만으로 실행 권한을 우회하거나 권한을 회수한 뒤에도 예약 실행이 계속 돌았다.
    // 판정 순서는 계정 활성 → 이 워크스페이스 ACTIVE 멤버 → 권한이다(WD-169) — 정지·제거된 사람과 권한만 잃은 사람을 다른 사유로 남긴다.
    long tenantId = TenantContext.require("트리거 실행 주체 자격 확인");
    MemberEligibility.Status eligibility =
        memberEligibility.check(trigger.createdBy(), tenantId, EXECUTE_PERMISSION);
    if (eligibility != MemberEligibility.Status.ELIGIBLE) {
      String reason =
          eligibility == MemberEligibility.Status.INACTIVE
              ? REASON_EXECUTOR_INACTIVE
              : REASON_EXECUTOR_NO_EXECUTE_PERMISSION;
      log.info(
          "Trigger {} skipped: creator {} not eligible ({}) (pipeline={})",
          triggerId,
          trigger.createdBy(),
          eligibility,
          trigger.pipelineId());
      // 이벤트를 남기기 전에 불러야 한다 — 직전 이벤트(같은 사유의 건너뜀인지)를 보고 처음 한 번만 알린다(WD-169)
      creatorSkipNotifier.notifyIfFirstSkip(trigger, tenantId, eligibility, reason);
      triggerEventRepository.create(
          triggerId, trigger.pipelineId(), null, "SKIPPED", Map.of("reason", reason));
      return TriggerFireResult.notFired(TriggerFireResult.Outcome.SKIPPED, reason);
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
            Map.of("reason", REASON_CONCURRENT_SKIP));
        return TriggerFireResult.notFired(
            TriggerFireResult.Outcome.SKIPPED, REASON_CONCURRENT_SKIP);
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
      return TriggerFireResult.fired(execution.id());
    } catch (Exception e) {
      log.error("Failed to fire trigger {}: {}", triggerId, e.getMessage(), e);
      // 화면에는 한국어 사유(reason)만 보이고, 진단용 예외 원문은 기존대로 error 에 남긴다(WD-152).
      // 원문이 null 인 예외도 있어 Map.of 대신 HashMap 을 쓴다 — Map.of 는 null 값에서 NPE 를 던진다.
      Map<String, Object> detail = new HashMap<>();
      detail.put("reason", REASON_FIRE_ERROR);
      detail.put("error", e.getMessage());
      triggerEventRepository.create(triggerId, trigger.pipelineId(), null, "ERROR", detail);
      return TriggerFireResult.notFired(TriggerFireResult.Outcome.ERROR, REASON_FIRE_ERROR);
    }
  }

  /** 자기 자신을 상류로 지정한 연쇄 거부 문구. */
  private static final String SELF_CHAIN_MESSAGE = "파이프라인은 자기 자신이 끝났을 때 다시 실행되도록 연결할 수 없습니다.";

  /** 선행 파이프라인이 없을 때의 거부 문구(WD-218). 삭제됐든 처음부터 없든(다른 테넌트 id 포함) 같은 문구다. */
  static final String UPSTREAM_MISSING_MESSAGE = "선행 파이프라인이 삭제되었거나 없습니다. 다른 파이프라인을 고르세요.";

  /**
   * 연쇄 트리거의 선행 파이프라인이 지금 테넌트에 있는지 확인하고, 없으면 400(IllegalArgumentException)으로 거부한다(WD-218).
   *
   * <p>RLS 가 걸린 일반 조회라 다른 테넌트의 파이프라인 id 도 "없음"과 같은 문구가 된다 — 다른 테넌트 파이프라인의 존재를 떠보는 오라클이 되지 않는다. 같은
   * 테넌트 안에서는 파이프라인 목록이 사용자별로 걸러지지 않으므로(목록 조회로 이미 모두 보인다) 감출 정보가 없다.
   */
  private void requireUpstreamExists(Long upstreamPipelineId) {
    if (!pipelineRepository.existsById(upstreamPipelineId)) {
      throw new IllegalArgumentException(UPSTREAM_MISSING_MESSAGE);
    }
  }

  /**
   * 조회 응답에 연쇄 트리거의 선행 없음 표시를 붙인다(WD-218) — 선행 id 들을 한 번에 조회한다. 숫자로 읽을 수 없는 선행도 실행 시 간선이 될 수 없으므로
   * 없음으로 본다. 켜짐 여부와 무관하게 계산한다(결함 이전에 없는 선행으로 켜진 채 남은 트리거도 드러내기 위함).
   */
  private List<TriggerResponse> withUpstreamMissing(List<TriggerResponse> triggers) {
    Set<Long> upstreamIds = new HashSet<>();
    for (TriggerResponse t : triggers) {
      if ("PIPELINE_CHAIN".equals(t.triggerType())) {
        Long id = ChainGraph.parseUpstreamIdOrNull(upstreamValueOf(t));
        if (id != null) {
          upstreamIds.add(id);
        }
      }
    }
    Set<Long> existing = pipelineRepository.findExistingIds(upstreamIds);
    List<TriggerResponse> out = new ArrayList<>(triggers.size());
    for (TriggerResponse t : triggers) {
      if (!"PIPELINE_CHAIN".equals(t.triggerType())) {
        out.add(t);
        continue;
      }
      Long id = ChainGraph.parseUpstreamIdOrNull(upstreamValueOf(t));
      out.add(t.withUpstreamMissing(id == null || !existing.contains(id)));
    }
    return out;
  }

  /** 감시 목록이 비어 켤 수 없을 때의 거부 문구(WD-221). */
  static final String WATCHED_DATASETS_MISSING_MESSAGE =
      "감시할 데이터셋이 없습니다(삭제되었을 수 있습니다). 편집에서 감시할 데이터셋을 하나 이상 고르세요.";

  /** 데이터 변경 트리거 config 의 감시 목록이 비어 있는가 — 목록이 아니거나 없으면 빈 것으로 본다(WD-221). */
  private static boolean watchedDatasetsEmpty(Map<String, Object> config) {
    Object ids = config == null ? null : config.get("datasetIds");
    return !(ids instanceof List<?> list) || list.isEmpty();
  }

  /**
   * 감시 목록이 비었으면 400(IllegalArgumentException)으로 거부한다(WD-221).
   *
   * <p>개별 id 의 존재를 확인하지 않고 목록이 비었는지만 본다 — 삭제된 id 는 데이터셋 삭제·폴러가 이미 목록에서 빼므로, 존재 확인을 더하면 볼 수 없는(상위
   * 등급) 데이터셋이 있는지를 켜기 성공·실패로 떠볼 수 있게 될 뿐이다(WD-161).
   */
  private static void requireWatchedDatasets(Map<String, Object> config) {
    if (watchedDatasetsEmpty(config)) {
      throw new IllegalArgumentException(WATCHED_DATASETS_MISSING_MESSAGE);
    }
  }

  /** 조회 응답에 데이터 변경 트리거의 감시 목록 비었음 표시를 붙인다(WD-221) — 감시 대상이 모두 삭제돼 꺼진 이유를 화면에 보이기 위함이다. */
  private static TriggerResponse withWatchedDatasetsMissing(TriggerResponse t) {
    if (!"DATASET_CHANGE".equals(t.triggerType())) {
      return t;
    }
    return t.withWatchedDatasetsMissing(watchedDatasetsEmpty(t.config()));
  }

  private static Object upstreamValueOf(TriggerResponse t) {
    return t.config() == null ? null : t.config().get("upstreamPipelineId");
  }

  /**
   * 연쇄 트리거 config 에서 상류 파이프라인 id 를 꺼낸다. 없으면 null, 숫자로 읽을 수 없으면 400.
   *
   * <p>해석 규칙은 그래프 적재와 같은 {@link ChainGraph#parseUpstreamId} 다 — 실행 시 조회가 {@code ::bigint} 로 읽으므로 숫자
   * 문자열도 살아 있는 간선이고, 저장 검사가 그것을 숫자가 아니라고 건너뛰면 안 된다.
   */
  private static Long chainUpstreamOf(Map<String, Object> config) {
    return ChainGraph.parseUpstreamId(config == null ? null : config.get("upstreamPipelineId"));
  }

  /** 연쇄 실행 조건 값 검증 — SUCCESS·FAILURE·ANY 중 하나여야 한다. */
  private static void validateChainCondition(Object condition) {
    try {
      TriggerCondition.valueOf((String) condition);
    } catch (IllegalArgumentException | ClassCastException | NullPointerException e) {
      throw new IllegalArgumentException("연쇄 실행 조건(condition)은 SUCCESS·FAILURE·ANY 중 하나여야 합니다.");
    }
  }

  /**
   * 수정·토글 <b>뒤</b>의 간선이 <b>이전에 없던 활성 간선</b>인가 — 저장 시 순환·길이 검사를 할지 정하는 단일 술어(WD-106).
   *
   * <p>참: 뒤 상태가 활성이고 상류가 있으며, (이전에 꺼져 있었거나 상류가 바뀌었다). 꺼진 채 상류만 바꾸는 수정은 간선이 아니라 검사하지 않고 켤 때 검사한다.
   * 이름·설명만 바꾸는 수정도 검사하지 않는다 — 결함 이전에 저장된 순환 트리거라도 끄거나 고칠 수 있어야 하고, 그런 순환은 실행 시 깊이 방어가 막는다.
   */
  private static boolean addsNewActiveChainEdge(
      TriggerResponse existing, Long upstreamAfter, boolean enabledAfter) {
    if (!enabledAfter || upstreamAfter == null) {
      return false;
    }
    // 이전 상류는 관대하게 읽는다 — 결함 이전에 저장된 잘못된 값 때문에 수정 자체가 막히면 안 된다.
    Long upstreamBefore =
        ChainGraph.parseUpstreamIdOrNull(existing.config().get("upstreamPipelineId"));
    return !existing.isEnabled() || !upstreamAfter.equals(upstreamBefore);
  }

  /**
   * 연쇄 트리거 수정 시 검사(WD-106). 자기 자신 참조는 활성 여부와 무관하게 잘못된 구성이므로 늘 거부하고(기존 동작), 순환·길이는 {@link
   * #addsNewActiveChainEdge} 가 참일 때만 본다.
   */
  private void validateChainOnUpdate(TriggerResponse existing, UpdateTriggerRequest request) {
    // config 를 보내면 저장소가 config 전체를 바꾼다 — 그 안의 상류가 수정 후의 상류다(새 값은 엄격히 해석). config 를 보내지 않으면
    // 저장된 상류가 그대로다 — 결함 이전의 잘못된 값 때문에 이름만 바꾸는 수정이 막히지 않게 관대하게 읽는다.
    Long upstreamAfter =
        request.config() != null
            ? chainUpstreamOf(request.config())
            : ChainGraph.parseUpstreamIdOrNull(existing.config().get("upstreamPipelineId"));
    if (upstreamAfter == null) {
      return;
    }
    if (upstreamAfter.equals(existing.pipelineId())) {
      throw new CyclicTriggerDependencyException(SELF_CHAIN_MESSAGE);
    }
    boolean enabledAfter = request.isEnabled() != null ? request.isEnabled() : existing.isEnabled();
    // 선행 존재 확인(WD-218): config 를 보낸 수정은 그 선행을 저장하는 것이므로 늘 확인한다(웹 편집 창은 늘 config 를 보낸다 — 지워진 id
    // 그대로 「수정되었습니다」가 되던 경로). config 없이 꺼진 트리거를 켜는 수정은 토글 켜기와 같아 확인한다. 이름만 바꾸거나 끄는 수정은
    // 확인하지 않는다 — 선행이 사라진 트리거라도 끄거나 이름을 고칠 수는 있어야 한다.
    if (request.config() != null || (enabledAfter && !existing.isEnabled())) {
      requireUpstreamExists(upstreamAfter);
    }
    if (addsNewActiveChainEdge(existing, upstreamAfter, enabledAfter)) {
      validatePipelineChain(existing.pipelineId(), upstreamAfter, existing.id());
    }
  }

  /**
   * 새 연쇄 간선 「upstream 이 끝나면 target 을 실행」을 더해도 되는지 검사한다(WD-106).
   *
   * <p>테넌트의 활성 연쇄 간선을 쿼리 한 번으로 읽어 {@link ChainGraph} 로 판정한다(노드마다 조회하던 N+1 제거). 판정 규칙:
   *
   * <ul>
   *   <li><b>순환</b>: target 에서 하류를 따라 upstream 에 닿으면 순환이다(upstream → target → … → upstream). 예전 구현은
   *       upstream 의 하류에서 target 을 찾아(=중복 경로 검사) A↔B 는 통과시키고 다이아몬드는 거부했다.
   *   <li><b>이미 있는 순환</b>: 새 간선이 이어지는 줄기에 결함 이전에 저장된 순환이 있으면, 실행이 끝없이 이어지므로 그 순환을 먼저 끊으라고 안내한다.
   *   <li><b>길이</b>: (upstream 위쪽 최장) + 1 + (target 아래쪽 최장) 이 {@link #MAX_CHAIN_DEPTH} 를 넘으면 거부한다.
   *       실행 시 방어와 같은 상한이라, 저장이 허용한 연쇄는 실행에서 잘리지 않는다.
   * </ul>
   *
   * <p>꺼진 트리거는 그래프에 넣지 않는다 — 실행 시에도 발화하지 않기 때문이다.
   *
   * @param excludeTriggerId 수정·토글 중인 트리거 id(그 트리거의 지금 간선은 바뀔 것이므로 그래프에서 뺀다). 생성이면 null
   */
  private void validatePipelineChain(
      Long targetPipelineId, Long upstreamPipelineId, Long excludeTriggerId) {
    List<ChainGraph.Edge> edges = new ArrayList<>();
    for (TriggerRepository.ChainEdgeRow row : triggerRepository.findEnabledChainEdges()) {
      // 숫자로 읽을 수 없는 상류는 실행 시 조회에서도 간선이 될 수 없다 — 건너뛴다.
      Long upstream = ChainGraph.parseUpstreamIdOrNull(row.upstreamPipelineId());
      if (upstream != null) {
        edges.add(new ChainGraph.Edge(row.triggerId(), upstream, row.pipelineId()));
      }
    }
    ChainGraph.Verdict verdict =
        ChainGraph.of(edges, excludeTriggerId)
            .check(targetPipelineId, upstreamPipelineId, MAX_CHAIN_DEPTH);
    switch (verdict) {
      case ChainGraph.Ok ok -> {}
      case ChainGraph.NewCycle c ->
          throw new CyclicTriggerDependencyException(
              "이 연쇄를 추가하면 파이프라인 실행이 서로를 끝없이 부르는 순환이 생깁니다: " + describePipelines(c.path()));
      case ChainGraph.ExistingCycle c ->
          throw new CyclicTriggerDependencyException(
              "이 연쇄가 이어지는 파이프라인들 사이에 이미 서로를 끝없이 부르는 순환이 있습니다: "
                  + describePipelines(c.path())
                  + ". 그 순환을 먼저 끊어 주세요.");
      case ChainGraph.TooLong t ->
          throw new CyclicTriggerDependencyException(
              "이 연쇄를 추가하면 이어지는 연쇄 실행이 "
                  + t.length()
                  + "단계가 되어 최대 "
                  + MAX_CHAIN_DEPTH
                  + "단계를 넘습니다. 연쇄를 나누거나 줄여 주세요.");
    }
  }

  /**
   * 파이프라인 id 목록을 "이름 → 이름" 으로 옮긴다. 이름은 같은 테넌트의 파이프라인 목록(연쇄 상류 선택 목록)에서 이미 보이는 값이라 새로 드러나는 정보가 아니다.
   * 이름을 찾지 못하면(삭제 경합 등) id 로 대신한다.
   */
  private String describePipelines(List<Long> pipelineIds) {
    List<String> labels = new ArrayList<>();
    for (Long id : pipelineIds) {
      labels.add(pipelineRepository.findNameById(id).map(n -> "「" + n + "」").orElse("#" + id));
    }
    return String.join(" → ", labels);
  }

  /**
   * 비인증 API 트리거 경로의 테넌트 해석. raw 토큰을 여기서 sha256 한 뒤 RLS 우회 해석기(V95)에 넘긴다.
   *
   * <p>해싱을 컨트롤러나 해석기로 옮기지 않는다 — 토큰 해시 형식은 {@code createTrigger} 와 이 클래스가 함께 정의하는 것이라, 형식이 바뀌면 한 곳만
   * 고쳐야 한다.
   *
   * <p>{@code @Transactional} 이 없다. 이 호출은 테넌트 컨텍스트가 아직 없는 시점에 일어나고, definer 함수는 RLS 를 우회하므로 GUC 가
   * 필요 없다.
   */
  public Optional<TriggerTenantResolver.TriggerRef> resolveTenantByApiToken(String rawToken) {
    return triggerTenantResolver.resolveByApiToken(sha256Hash(rawToken));
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

    String secretEncrypted = (String) trigger.config().get(WEBHOOK_SECRET_ENCRYPTED);
    if (secretEncrypted == null || secretEncrypted.isEmpty()) {
      // No secret configured, accept all
      return true;
    }

    try {
      // 새 형식(v2 GCM)과 옛 ECB 암호문을 모두 푼다(WD-220).
      String secret = webhookSecretCipher.decrypt(secretEncrypted);
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

  private void validateScheduleConfig(Map<String, Object> config) {
    if (!config.containsKey("cron") || config.get("cron") == null) {
      throw new IllegalArgumentException("cron expression is required for SCHEDULE trigger");
    }
    // cron 형식 검증 및 정규화 (#55)
    // Spring CronExpression은 6필드(초 분 시 일 월 요일) 형식만 수용.
    // 5필드(Unix 표준: 분 시 일 월 요일) 입력은 앞에 "0 "을 붙여 정규화.
    // 정규화 규칙은 등록(TriggerSchedulerService.registerSchedule)과 같은 ProactiveCron.normalize 를
    // 쓴다(WD-107).
    String cron = config.get("cron").toString().trim();
    String normalizedCron = ProactiveCron.normalize(cron);
    if (!CronExpression.isValidExpression(normalizedCron)) {
      throw new IllegalArgumentException("유효하지 않은 cron 표현식입니다: " + cron);
    }
    config.put("cron", normalizedCron);
    // 키가 없거나 null 이면 기본값을 채운다 — null 이 그대로 저장되면 등록 시 "null" 시간대로 실패한다.
    if (config.get("timezone") == null) {
      config.put("timezone", "Asia/Seoul");
    }
    if (config.get("concurrencyPolicy") == null) {
      config.put("concurrencyPolicy", "SKIP");
    }
    // 시간대도 커밋 전에 검증한다(WD-107). 여기서 거르지 않으면 저장은 성공하고 커밋 후 스케줄 등록(ZoneId.of)만 실패해,
    // 화면엔 "수정되었습니다"가 뜨는데 스케줄은 돌지 않는 상태가 된다.
    String timezone = config.get("timezone").toString();
    try {
      ZoneId.of(timezone);
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("유효하지 않은 시간대입니다: " + timezone);
    }
    // 문법은 맞아도 다음 실행 시각이 없는 cron(예: 2월 30일 "0 0 0 30 2 *")은 거부한다(WD-107). 저장되면 스케줄러가 걸 수 있는 스케줄이
    // 없어 아무 때도 실행되지 않는데, 화면엔 "수정되었습니다"가 떠 성공처럼 보인다.
    if (CronExpression.parse(normalizedCron).next(ZonedDateTime.now(ZoneId.of(timezone))) == null) {
      throw new IllegalArgumentException("다음 실행 시각이 없는 cron 표현식입니다: " + cron);
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
