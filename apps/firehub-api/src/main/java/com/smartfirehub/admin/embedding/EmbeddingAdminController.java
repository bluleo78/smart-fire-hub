package com.smartfirehub.admin.embedding;

import com.smartfirehub.dataset.search.DatasetEmbeddingBackfillService;
import com.smartfirehub.document.service.DocumentChunkReembedService;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.global.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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
  private final DatasetEmbeddingBackfillService datasetBackfillService;
  private final DocumentChunkReembedService documentReembedService;
  private final EmbeddingProviderFactory embeddingProviderFactory;

  /** 현재 모델 기준 임베딩 진행 상태. 조회만 하므로 dataset:read 권한을 요구한다. */
  @GetMapping("/status")
  @RequirePermission("dataset:read")
  public EmbeddingStatusResponse status() {
    return statusService.status();
  }

  /**
   * 데이터셋 카탈로그 + 문서 청크를 현재 모델로 전체 재임베딩(비동기 잡으로 분산). 인덱스를 변경하므로 dataset:write 권한을 요구한다.
   *
   * @return 202 Accepted + 예약된 데이터셋 수({@code datasets}: 카탈로그, {@code documentDatasets}: 문서 청크 보유 데이터셋)
   */
  @PostMapping("/reindex-all")
  @RequirePermission("dataset:write")
  public ResponseEntity<Map<String, Integer>> reindexAll() {
    int datasets = datasetBackfillService.backfillAll();
    int documentDatasets = documentReembedService.reembedAll();
    return ResponseEntity.accepted()
        .body(Map.of("datasets", datasets, "documentDatasets", documentDatasets));
  }

  /**
   * 임의 텍스트 배치를 현재 활성 provider 로 임베딩해 벡터를 반환한다.
   *
   * <p>ai-agent(GraphRAG 엔티티 해소)가 provider/모델/API 키를 직접 알 필요 없이 위임 호출하는 내부 진입점이다. provider
   * 로직과 시크릿을 api 한쪽에 모아 설정을 단일화하기 위한 것으로, 벡터를 계산만 할 뿐 인덱스를 변경하지 않으므로 dataset:read 로 충분하다.
   */
  @PostMapping("/embed")
  @RequirePermission("dataset:read")
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
