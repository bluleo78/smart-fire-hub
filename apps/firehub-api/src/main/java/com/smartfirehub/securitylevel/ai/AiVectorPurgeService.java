package com.smartfirehub.securitylevel.ai;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.rowsearch.IndexRef;
import com.smartfirehub.dataset.rowsearch.RowSearchIndex;
import com.smartfirehub.dataset.rowsearch.RowSearchSyncService;
import com.smartfirehub.dataset.rowsearch.SearchIndexStateRepository;
import com.smartfirehub.dataset.search.DatasetEmbeddingRepository;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.ontology.graphread.GraphOntologySourceRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 정책 위반 외부 벡터 정리(스펙 §4.3 "외부 벡터 정리 트리거", 보충 §2.1). 현재 테넌트에서 <b>지금</b> 임베딩 공급자로 보낼 수 없는 데이터셋의 메타
 * 벡터·문서 청크 벡터·행 검색 벡터를 지운다.
 *
 * <p>전이(이전→이후)를 감지하지 않고 <b>현재 상태 기준</b>으로 판정하므로 멱등이다 — 등급 상향·정책 강화·순서 변경·임베딩 호스팅 외부 전환·배포 1회 잡이 모두
 * 이 메서드 하나를 부른다. 반대 방향(허용으로 바뀜)은 지울 것이 없고 재임베딩 판정식·행 검색 모델 비교가 색인을 되살린다.
 *
 * <p>키워드 검색용 텍스트(dataset_embedding.source_text, document_chunk.content, 행 색인 source_text)는 남긴다.
 * GraphRAG 기적재분은 회수하지 않는다(스펙 §7.5) — 대신 그래프 적재 이력이 있는 불허 데이터셋 id 를 경고 로그와 결과에 남겨 운영자의 수동 정리 대상으로
 * 표시한다(스펙 §4.3 "경고 + 수동 정리 대상 표시").
 *
 * <p>트랜잭션을 열지 않는다 — 각 저장소가 클래스 레벨 트랜잭션으로 RLS GUC 를 세우고, 데이터셋 하나의 행 검색 정리 실패가 나머지를 되돌리지 않게 하기 위해서다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiVectorPurgeService {

  private final EmbeddingAiGate gate;
  private final DatasetEmbeddingRepository datasetEmbeddingRepository;
  private final DocumentChunkRepository chunkRepository;
  private final SearchIndexStateRepository searchStates;
  private final RowSearchIndex rowSearchIndex;
  private final DatasetRepository datasetRepository;
  private final GraphOntologySourceRepository graphSources;

  /**
   * 정리 결과(로그·테스트·배포 1회 잡의 완료 판정용).
   *
   * @param datasets 불허 데이터셋 수
   * @param datasetVectors 지운 메타 벡터 행 수
   * @param chunkVectors 지운 청크 벡터 행 수
   * @param rowIndexes 키워드 전용으로 전환한 행 검색 색인 수
   * @param failures 정리에 실패한 행 검색 색인 수 — 0 이 아니면 배포 1회 잡이 완료 플래그를 남기지 않는다
   * @param graphResidueDatasetIds 불허 데이터셋 중 지식그래프에 내용이 쓰였을 수 있는 것(수동 정리 대상). 회수하지 않으므로 정리할 때마다 다시
   *     나온다 — 완료 판정(failures)에는 영향이 없다
   */
  public record PurgeResult(
      int datasets,
      int datasetVectors,
      int chunkVectors,
      int rowIndexes,
      int failures,
      List<Long> graphResidueDatasetIds) {}

  /**
   * 현재 테넌트(TenantContext) 정리. 테넌트 문맥이 없으면 예외 — 문맥 없이 돌면 RLS 0행으로 조용히 무동작이 된다. 행 검색 색인 하나의 실패는 나머지를
   * 막지 않고 failures 로 센다.
   */
  public PurgeResult purgeDisallowed() {
    long tenantId = TenantContext.require("외부 벡터 정리");
    List<Long> ids = gate.disallowedDatasetIds();
    if (ids.isEmpty()) {
      return new PurgeResult(0, 0, 0, 0, 0, List.of());
    }
    int datasetVectors = datasetEmbeddingRepository.deleteVectorsOf(ids);
    int chunkVectors = chunkRepository.deleteVectorsOf(ids);
    int rowIndexes = 0;
    int failures = 0;
    for (Long id : ids) {
      try {
        if (purgeRowSearch(tenantId, id)) {
          rowIndexes++;
        }
      } catch (RuntimeException e) {
        failures++;
        log.warn("행 검색 벡터 정리 실패: tenant={}, dataset={}", tenantId, id, e);
      }
    }
    List<Long> graphResidue = graphSources.findDatasetsWithGraphHistory(tenantId, ids);
    if (!graphResidue.isEmpty()) {
      // 그래프(Neo4j)는 자동으로 회수할 수 없다 — 운영자가 수동 정리할 대상을 남긴다. 데이터셋 id 만 쓴다(등급 이름·내용 금지).
      log.warn(
          "외부 공급자 불허 데이터셋의 GraphRAG 기적재분 — 자동 회수 불가, 수동 정리 대상: tenant={}, datasets={}",
          tenantId,
          graphResidue);
    }
    PurgeResult result =
        new PurgeResult(
            ids.size(), datasetVectors, chunkVectors, rowIndexes, failures, graphResidue);
    log.info("외부 벡터 정리: tenant={}, 결과={}", tenantId, result);
    return result;
  }

  /**
   * 데이터셋 하나의 행 검색 벡터 정리. 색인이 없거나(검색 꺼짐) 이미 키워드 전용이면 할 일 없음(false) — 이것이 멱등의 근거다.
   *
   * <p>현재 색인 테이블의 벡터를 비우고, 재구축(swap) 도중 남은 이전 테이블(prev)도 버린다 — prev 에도 외부 공급자가 만든 벡터가 있고, 상태를 키워드
   * 전용으로 바꾸면 다음 스윕은 어차피 전체 재색인하므로 재사용할 일이 없다.
   */
  private boolean purgeRowSearch(long tenantId, long datasetId) {
    boolean needsPurge =
        searchStates
            .find(datasetId)
            .filter(s -> !RowSearchSyncService.KEYWORD_ONLY_MODEL.equals(s.embeddingModel()))
            .isPresent();
    if (!needsPurge) {
      return false;
    }
    String table = datasetRepository.findTableNameById(datasetId).orElse(null);
    if (table == null) {
      return false; // 삭제 중 — CASCADE 로 상태 행도 곧 사라진다
    }
    IndexRef ref = new IndexRef(tenantId, datasetId, table);
    rowSearchIndex.clearEmbeddings(ref);
    rowSearchIndex.dropPrev(ref);
    // 상태 모델을 즉시 키워드 전용으로 — 다음 스윕 전에도 검색이 키워드 경로를 탄다(질의 임베딩 안 함).
    searchStates.markKeywordOnly(datasetId, RowSearchSyncService.KEYWORD_ONLY_MODEL);
    return true;
  }
}
