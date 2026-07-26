package com.smartfirehub.mapping.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.mapping.dto.MappingResponse;
import com.smartfirehub.mapping.dto.MappingSpec;
import com.smartfirehub.mapping.service.MappingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// 데이터셋 매핑 엔드포인트 — 데이터셋 하위 리소스. 표 투영(ai-agent)이 GET으로 소비한다.
@RestController
@RequestMapping("/api/v1/datasets/{datasetId}/mapping")
@RequiredArgsConstructor
public class MappingController {

  private final MappingService mappingService;

  // 매핑 조회 — 없으면 404.
  @GetMapping
  @RequirePermission("dataset:read")
  public ResponseEntity<MappingResponse> get(@PathVariable Long datasetId) {
    return mappingService.get(datasetId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  // 매핑 저장(draft). conformance 위반 시 400.
  @PutMapping
  @RequirePermission("dataset:write")
  public MappingResponse save(
      @PathVariable Long datasetId,
      @RequestBody MappingSpec spec,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return mappingService.save(datasetId, spec, userId);
  }

  // 매핑 활성화(draft→active). 재검증 후 전환.
  @PostMapping("/activate")
  @RequirePermission("dataset:write")
  public MappingResponse activate(@PathVariable Long datasetId, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return mappingService.activate(datasetId, userId);
  }
}
