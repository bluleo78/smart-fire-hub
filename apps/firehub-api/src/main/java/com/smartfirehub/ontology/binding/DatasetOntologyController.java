package com.smartfirehub.ontology.binding;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.ontology.dto.DatasetOntologyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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

  // 바인딩 설정/변경(멱등 UPSERT).
  @PutMapping
  @RequirePermission("dataset:write")
  public ResponseEntity<Void> bind(
      @PathVariable Long datasetId,
      @RequestBody BindOntologyRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    bindingService.bind(datasetId, request.ontologyId(), userId);
    return ResponseEntity.noContent().build();
  }
}
