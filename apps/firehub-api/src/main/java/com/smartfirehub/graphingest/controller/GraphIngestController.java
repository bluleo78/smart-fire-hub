package com.smartfirehub.graphingest.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.graphingest.dto.GraphIngestResponse;
import com.smartfirehub.graphingest.dto.RecordGraphIngestRequest;
import com.smartfirehub.graphingest.dto.StaleDatasetResponse;
import com.smartfirehub.graphingest.service.GraphIngestService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** GraphRAG 적재 이력 API — 기록(ai-agent 호출)/이력 조회/stale 데이터셋 조회. */
@RestController
@RequiredArgsConstructor
public class GraphIngestController {

  private final GraphIngestService service;

  // 적재 이력 기록(ai-agent가 적재 후 호출). 세션 유저 dataset:write 필요.
  @PostMapping("/api/v1/datasets/{datasetId}/graph-ingests")
  @RequirePermission("dataset:write")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Long> record(
      @PathVariable Long datasetId, @RequestBody RecordGraphIngestRequest req) {
    return Map.of("id", service.record(datasetId, req));
  }

  // 데이터셋 적재 이력.
  @GetMapping("/api/v1/datasets/{datasetId}/graph-ingests")
  @RequirePermission("dataset:read")
  public List<GraphIngestResponse> history(@PathVariable Long datasetId) {
    return service.history(datasetId);
  }

  // 재추출 필요(stale) 데이터셋.
  @GetMapping("/api/v1/graph-ingests/stale")
  @RequirePermission("dataset:read")
  public List<StaleDatasetResponse> stale() {
    return service.stale();
  }
}
