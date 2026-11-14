package com.smartfirehub.vectorstore;

import java.util.Collection;
import java.util.List;

/**
 * 별도 벡터 저장소의 정합성 확인에 쓰는 표시 열 읽기·비우기(WD-721, ADR 0002 결정 4·6). 표시 열({@code embedded_dim}·{@code
 * embedded_model}·{@code keyword_marker})은 "저장소에 이 점이 있다" 는 플랫폼 DB 쪽 기록이다. 대조 스윕은 이 기록과 저장소의 점을
 * 양방향으로 맞춘다. jOOQ(PG)·MyBatis(Tibero) 두 벌이고 RLS 로 현재 테넌트 행만 본다. 쓰는 문장은 RLS 와 별개로 {@code tenant_id}
 * 를 명시한다(조건 없는 UPDATE 금지 규율).
 *
 * <p>데이터셋은 {@code dataset_embedding}(id = 데이터셋 id), 청크는 {@code document_chunk}(id = 청크 id)다.
 */
public interface VectorMarkerRepository {

  /**
   * 한 행의 표시.
   *
   * @param id 데이터셋 id 또는 청크 id
   * @param datasetId 데이터셋 id(데이터셋이면 id 와 같다)
   * @param dim 임베딩한 차원. null 이면 dense 점이 없다고 기록됨
   * @param model 임베딩한 모델
   * @param keyword 키워드 표시가 있는가
   */
  record Marker(long id, long datasetId, Integer dim, String model, boolean keyword) {}

  /**
   * dense 표시가 있는 데이터셋 수. 별도 저장소 구현이 "지울 벡터 수" 를 저장소에 묻지 않고 여기서 센다 — 삭제는 아웃박스로 미뤄지므로 저장소가 내려가 있어도 정리
   * 경로가 실패하지 않게.
   *
   * @param datasetIds 이 데이터셋들만. null 이면 테넌트 전체
   * @param exceptDim 이 차원 표시는 세지 않는다. null 이면 모두
   */
  int countDatasetDense(Collection<Long> datasetIds, Integer exceptDim);

  /** dense 표시가 있는 청크 수({@link #countDatasetDense} 와 같은 뜻, 데이터셋 id 로 좁힌다). */
  int countChunkDense(Collection<Long> datasetIds, Integer exceptDim);

  /** 주어진 데이터셋들 중 dense 표시가 {@code dim} 이 아닌 차원을 가리키는 것(쓰기 뒤 다른 차원 정리 대상). */
  List<Long> findDatasetsMarkedOtherThan(Collection<Long> datasetIds, int dim);

  /** 주어진 청크들 중 dense 표시가 {@code dim} 이 아닌 차원을 가리키는 것의 데이터셋 id(중복 없음). */
  List<Long> findChunkDatasetsMarkedOtherThan(Collection<Long> chunkIds, int dim);

  /** 주어진 데이터셋들의 표시(없는 id 는 빠진다 — 저장소에만 있는 점). */
  List<Marker> findDatasetMarkers(Collection<Long> datasetIds);

  /** 주어진 청크들의 표시(없는 id 는 빠진다). */
  List<Marker> findChunkMarkers(Collection<Long> chunkIds);

  /** dense 나 키워드 표시가 있는 데이터셋을 id 순으로 {@code limit} 건(키셋). */
  List<Marker> pageMarkedDatasets(long afterId, int limit);

  /** dense 나 키워드 표시가 있는 청크를 id 순으로 {@code limit} 건(키셋). */
  List<Marker> pageMarkedChunks(long afterId, int limit);

  /**
   * 데이터셋 원문(키워드 대조용). 없으면 null. 대조 스윕이 "키워드 표시는 있는데 점이 없음" 을 볼 때, 낱말이 하나도 없는 원문(점을 지우고 표시만 채운다)인지
   * 가리려고 읽는다.
   */
  String findDatasetSourceText(long datasetId);

  /** 청크 본문(키워드 대조용, {@link #findDatasetSourceText} 와 같은 이유). 없으면 null. */
  String findChunkContent(long chunkId);

  /** 데이터셋 dense 표시를 비운다 — 표시가 아직 {@code dim}·{@code model} 일 때만(그새 다시 쓴 표시는 두기). */
  int clearDatasetDense(long datasetId, int dim, String model);

  /** 데이터셋 키워드 표시를 비운다. */
  int clearDatasetKeyword(long datasetId);

  /** 청크 dense 표시를 비운다 — 표시가 아직 {@code dim}·{@code model} 일 때만. */
  int clearChunkDense(long chunkId, int dim, String model);

  /** 청크 키워드 표시를 비운다. */
  int clearChunkKeyword(long chunkId);
}
