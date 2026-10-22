package com.smartfirehub.ontology.binding;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.ontology.dto.BindOntologyResponse;
import com.smartfirehub.ontology.dto.DatasetOntologyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// 데이터셋↔온톨로지 바인딩 엔드포인트 — 데이터셋 하위 리소스로 노출한다.
@RestController
@RequestMapping("/api/v1/datasets/{datasetId}/ontology")
@RequiredArgsConstructor
public class DatasetOntologyController {

  private final DatasetOntologyService bindingService;

  // 바인딩 조회.
  @GetMapping
  @RequirePermission("dataset:read")
  public DatasetOntologyResponse get(@PathVariable Long datasetId) {
    return bindingService.get(datasetId);
  }

  // 바인딩 설정/변경(UPSERT). 같은 모델 재연결은 멱등이지만 다른 모델로 바꾸면 옛 모델 기준 매핑이 지워진다.
  // WD-184: 204 대신 200 + 본문 — 호출자(웹·AI 도구)가 「이전 모델(○○) 기준 매핑을 삭제했습니다」를 알릴 수 있게
  // 삭제 여부와 이전 모델을 싣는다(매핑 저장 PUT 이 200 + 본문을 돌려주는 것과 같은 관례).
  @PutMapping
  @RequirePermission("dataset:write")
  public BindOntologyResponse bind(
      @PathVariable Long datasetId,
      @RequestBody BindOntologyRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return bindingService.bind(datasetId, request.ontologyId(), userId);
  }
}
