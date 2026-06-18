package com.smartfirehub.admin.embedding;

import com.smartfirehub.dataset.search.DatasetEmbeddingBackfillService;
import com.smartfirehub.document.service.DocumentChunkReembedService;
import com.smartfirehub.global.security.RequirePermission;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 임베딩 전체 재구축 트리거 + 진행 상태 조회 (관리자). */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin/embedding")
public class EmbeddingAdminController {

  private final EmbeddingStatusService statusService;
  private final DatasetEmbeddingBackfillService datasetBackfillService;
  private final DocumentChunkReembedService documentReembedService;

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
}
