package com.smartfirehub.graphingest.service;

import com.smartfirehub.graphingest.dto.GraphIngestResponse;
import com.smartfirehub.graphingest.dto.RecordGraphIngestRequest;
import com.smartfirehub.graphingest.dto.StaleDatasetResponse;
import com.smartfirehub.graphingest.repository.GraphIngestRepository;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** GraphRAG 적재 이력 서비스 — 기록/조회/stale 판정. */
@Service
@RequiredArgsConstructor
public class GraphIngestService {

  private final GraphIngestRepository repo;
  private final DatasetAccessGuard datasetAccessGuard;

  /** 적재 이력 기록 → 생성 id. */
  public long record(long datasetId, RecordGraphIngestRequest req) {
    return repo.save(
        datasetId,
        req.schemaVersionAtIngest(),
        req.chunkCount(),
        req.nodeCount(),
        req.edgeCount(),
        req.extractionFailures(),
        req.status());
  }

  /** 데이터셋 이력(최신순). */
  public List<GraphIngestResponse> history(long datasetId) {
    return repo.findByDataset(datasetId).stream()
        .map(
            r ->
                new GraphIngestResponse(
                    r.id(),
                    r.datasetId(),
                    r.ingestedAt().toString(),
                    r.schemaVersionAtIngest(),
                    r.chunkCount(),
                    r.nodeCount(),
                    r.edgeCount(),
                    r.extractionFailures(),
                    r.status()))
        .toList();
  }

  /** 각 데이터셋이 실제 바인딩된 온톨로지의 현재 버전보다 낡게 적재된 데이터셋. */
  public List<StaleDatasetResponse> stale() {
    // 조회자가 볼 수 없는 데이터셋 id 는 싣지 않는다(보안 등급 — 숨김 데이터셋 존재가 stale 목록으로 드러나지 않게). 요청 경로 전용.
    return repo.findStale(datasetAccessGuard.visibleCondition()).stream()
        .map(
            s ->
                new StaleDatasetResponse(
                    s.datasetId(),
                    s.latestIngestedAt().toString(),
                    s.schemaVersionAtIngest(),
                    s.currentSchemaVersion()))
        .toList();
  }
}
