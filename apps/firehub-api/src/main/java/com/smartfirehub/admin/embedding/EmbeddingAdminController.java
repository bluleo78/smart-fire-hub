package com.smartfirehub.admin.embedding;

import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.config.dto.EmbeddingImpact;
import com.smartfirehub.embedding.reembed.TenantReembedJob;
import com.smartfirehub.global.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 임베딩 전체 재구축 트리거 + 진행 상태 조회 + 내부 임베딩 실행 (관리자/내부 서비스). */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin/embedding")
public class EmbeddingAdminController {

  private final EmbeddingStatusService statusService;
  private final TenantReembedJob tenantReembedJob;
  private final EmbeddingProviderFactory embeddingProviderFactory;

  /**
   * 현재 모델 기준 임베딩 진행 상태.
   *
   * <p>권한 ai:settings(WD-600): 유일한 화면이 관리 설정의 임베딩 탭이고, 같은 탭의 {@code /settings/embedding} API 가 모두
   * ai:settings 다. 예전 dataset:read 는 모든 일반 사용자에게 모델·차원·전체 집계를 노출했다.
   */
  @GetMapping("/status")
  @RequirePermission("ai:settings")
  public EmbeddingStatusResponse status() {
    return statusService.status();
  }

  /**
   * 현재 설정 공간으로 전체 재임베딩(#713). source_text 를 먼저 채우고, 판정식 대상 수를 돌려준 뒤 TenantReembedJob 을 투입한다(임대 행이
   * 중복 실행을 막는다). 미설정이면 409. 인덱스를 바꾸므로 dataset:write.
   */
  @PostMapping("/reindex-all")
  @RequirePermission("dataset:write")
  public ResponseEntity<EmbeddingImpact> reindexAll() {
    return ResponseEntity.accepted().body(tenantReembedJob.requestReindexAll());
  }

  /**
   * 임의 텍스트 배치를 현재 활성 provider 로 임베딩해 벡터를 반환한다.
   *
   * <p>ai-agent(GraphRAG 엔티티 해소)가 provider/모델/API 키를 직접 알 필요 없이 위임 호출하는 내부 진입점이다. provider 로직과 시크릿을
   * api 한쪽에 모아 설정을 단일화하기 위한 것이다.
   *
   * <p>권한 dataset:write(WD-600): 호출마다 외부 provider 비용이 드는데, 예전 dataset:read 로는 읽기만 가진 일반 사용자도 임의
   * 텍스트를 임베딩시킬 수 있었다. 유일한 호출 경로(ai-agent graphrag_ingest)가 이미 dataset:write 를 요구하므로 기존 동작은 그대로다. 요청
   * 크기 상한은 {@link EmbedRequest} 가 Bean Validation 으로 건다(개수·항목 길이 초과 → 400). 권한
   * 검사(PermissionInterceptor)는 본문 검증보다 먼저 돌아 권한 없는 거대 본문은 403 이 우선한다.
   */
  @PostMapping("/embed")
  @RequirePermission("dataset:write")
  public EmbedResponse embed(@Valid @RequestBody EmbedRequest request) {
    EmbeddingProvider provider = embeddingProviderFactory.current();
    List<String> texts = request.texts();
    // 빈 입력은 provider 호출 없이 조기 반환한다(불필요한 외부 호출·과금 방지).
    if (texts == null || texts.isEmpty()) {
      return new EmbedResponse(provider.modelId(), provider.dimension(), List.of());
    }
    return new EmbedResponse(provider.modelId(), provider.dimension(), provider.embed(texts));
  }
}
