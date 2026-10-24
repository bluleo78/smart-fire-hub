package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.ApiImportRequest;
import com.smartfirehub.dataset.dto.ApiImportResponse;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.global.security.PermissionChecker;
import com.smartfirehub.global.security.PermissionInterceptor;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.CreateTriggerRequest;
import com.smartfirehub.pipeline.dto.PipelineDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineExecutionResponse;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.dto.TriggerType;
import com.smartfirehub.pipeline.service.PipelineService;
import com.smartfirehub.pipeline.service.TriggerService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApiImportService {

  /** 예약 트리거 생성에 필요한 권한 — 직접 경로 POST /pipelines/{id}/triggers(TriggerController)와 같다. */
  static final String TRIGGER_WRITE_PERMISSION = "trigger:write";

  /** 즉시 실행에 필요한 권한 — 직접 경로 POST /pipelines/{id}/execute(PipelineController)와 같다. */
  static final String PIPELINE_EXECUTE_PERMISSION = "pipeline:execute";

  private final DatasetRepository datasetRepository;
  private final PipelineService pipelineService;
  private final TriggerService triggerService;
  private final PermissionChecker permissionChecker;

  @Transactional
  public ApiImportResponse createApiImport(Long datasetId, ApiImportRequest request, Long userId) {
    // 0. 선택 옵션 권한 확인(WD-251) — 엔드포인트는 pipeline:write 만 요구하는데, 예약(트리거 생성)·즉시 실행은 직접 경로에서 각각
    // trigger:write·pipeline:execute 가 필요한 일이다. 여기서 다시 보지 않으면 파이프라인 작성 권한만으로 실행·예약을 우회할 수 있었다.
    // 무엇이든 만들기 전에(데이터셋 확인보다도 먼저 — 인터셉터가 핸들러보다 먼저 403 을 주는 것과 같은 순서) 확인해 부분 생성이 없게 한다.
    // 권한 조회는 인터셉터가 쓰는 authority 와 같은 원천(PermissionRepository.findPermissionCodesByUserId)이라 JWT·내부
    // 대행
    // 호출(X-On-Behalf-Of) 모두 같은 판정이 된다. 확인 순서는 생성 순서(트리거 → 실행)를 따른다.
    boolean wantsSchedule = wantsSchedule(request);
    requirePermission(wantsSchedule, userId, TRIGGER_WRITE_PERMISSION);
    requirePermission(request.executeImmediately(), userId, PIPELINE_EXECUTE_PERMISSION);

    // 1. Verify dataset exists
    datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());

    // 2. 파이프라인 이름 — 기본값에 데이터셋 이름을 넣지 않는다(WD-31④): 파이프라인·트리거 목록은 데이터셋 등급 판정 대상이
    // 아니라서, 이름에 넣으면 그 데이터셋을 볼 수 없는 사용자에게 이름이 새어 나간다. 사용자가 입력한 이름은 그대로 쓴다.
    // 기본 트리거 이름(아래 5.)은 이 이름 + " Schedule" 이라 함께 데이터셋 이름이 빠진다.
    String pipelineName =
        (request.pipelineName() != null && !request.pipelineName().isBlank())
            ? request.pipelineName()
            : "API Import #" + datasetId;

    // 3. Build the single API_CALL step
    String loadStrategy =
        (request.loadStrategy() != null && !request.loadStrategy().isBlank())
            ? request.loadStrategy()
            : "REPLACE";

    PipelineStepRequest stepRequest =
        new PipelineStepRequest(
            "API Data Collection",
            request.pipelineDescription(),
            "API_CALL",
            null,
            datasetId,
            List.of(),
            List.of(),
            loadStrategy,
            request.apiConfig(),
            null,
            null,
            request.apiConnectionId());

    // 4. Create pipeline via PipelineService
    CreatePipelineRequest createReq =
        new CreatePipelineRequest(
            pipelineName, request.pipelineDescription(), List.of(stepRequest));

    PipelineDetailResponse pipeline = pipelineService.createPipeline(createReq, userId);

    // 5. Create schedule trigger if requested
    Long triggerId = null;
    if (wantsSchedule) {
      String triggerName =
          (request.schedule().name() != null && !request.schedule().name().isBlank())
              ? request.schedule().name()
              : pipelineName + " Schedule";

      CreateTriggerRequest triggerReq =
          new CreateTriggerRequest(
              triggerName,
              TriggerType.SCHEDULE,
              request.schedule().description(),
              Map.of("cron", request.schedule().cronExpression()));

      TriggerResponse triggerResponse =
          triggerService.createTrigger(pipeline.id(), triggerReq, userId);
      triggerId = triggerResponse.id();
    }

    // 6. Execute immediately if requested
    Long executionId = null;
    if (request.executeImmediately()) {
      PipelineExecutionResponse execution = pipelineService.executePipeline(pipeline.id(), userId);
      executionId = execution.id();
    }

    return new ApiImportResponse(pipeline.id(), executionId, triggerId);
  }

  /**
   * 예약 트리거를 만들 요청인가 — cron 이 비어 있으면 예전처럼 조용히 건너뛴다. 권한 확인과 트리거 생성이 같은 판정을 써야, 빈 cron 요청이 권한 없는 사용자에게
   * 새로 403 이 되지 않는다.
   */
  private static boolean wantsSchedule(ApiImportRequest request) {
    return request.schedule() != null
        && request.schedule().cronExpression() != null
        && !request.schedule().cronExpression().isBlank();
  }

  /** 옵션을 요청했는데 권한이 없으면 인터셉터와 같은 형식의 403 을 던진다. */
  private void requirePermission(boolean requested, Long userId, String permissionCode) {
    if (requested && !permissionChecker.hasPermission(userId, permissionCode)) {
      throw new AccessDeniedException(
          PermissionInterceptor.missingPermissionMessage(permissionCode));
    }
  }
}
