package com.smartfirehub.graphingest.service;

import com.smartfirehub.graphingest.dto.GraphIngestResponse;
import com.smartfirehub.graphingest.dto.RecordGraphIngestRequest;
import com.smartfirehub.graphingest.dto.StaleDatasetResponse;
import com.smartfirehub.graphingest.repository.GraphIngestRepository;
import com.smartfirehub.ontology.repository.OntologyRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** GraphRAG 적재 이력 서비스 — 기록/조회/stale 판정. */
@Service
@RequiredArgsConstructor
public class GraphIngestService {

  private final GraphIngestRepository repo;
  private final OntologyRepository ontologyRepository; // currentSchemaVersion()

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

  /** 현재 온톨로지 버전보다 낡게 적재된 데이터셋. */
  public List<StaleDatasetResponse> stale() {
    int current = ontologyRepository.currentSchemaVersion();
    return repo.findStale(current).stream()
        .map(
            s ->
                new StaleDatasetResponse(
                    s.datasetId(), s.latestIngestedAt().toString(), s.schemaVersionAtIngest(), current))
        .toList();
  }
}
