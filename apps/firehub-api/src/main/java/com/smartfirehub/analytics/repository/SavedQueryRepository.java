package com.smartfirehub.analytics.repository;

import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.SavedQueryListResponse;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.analytics.dto.UpdateSavedQueryRequest;
import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 분석 저장 쿼리(saved_query) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqSavedQueryRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface SavedQueryRepository {

  /**
   * 목록.
   *
   * @param nameVisibility 연결 데이터셋 LEFT JOIN 의 ON 에 더하는 가시성({@code "dataset"} 이름 관례로 렌더) — 조회자가 볼 수
   *     없는 데이터셋은 이름만 null 이 되고 datasetId 는 유지된다(보안 등급, 스펙 §2.5). 웹은 id 있음+이름 null 을 "열람 권한 없음" 으로
   *     그린다
   */
  List<SavedQueryListResponse> findAll(
      String search,
      String folder,
      Boolean sharedOnly,
      Boolean mineOnly,
      Long userId,
      int page,
      int size,
      DatasetVisibility nameVisibility);

  long countAll(String search, String folder, Boolean sharedOnly, Boolean mineOnly, Long userId);

  /** 단건(소유자 또는 공유). {@code nameVisibility} 는 {@link #findAll} 과 같다. */
  Optional<SavedQueryResponse> findById(Long id, Long userId, DatasetVisibility nameVisibility);

  /** 단건(소유자만). {@code nameVisibility} 는 {@link #findAll} 과 같다. */
  Optional<SavedQueryResponse> findByIdForOwner(
      Long id, Long userId, DatasetVisibility nameVisibility);

  /** 소유권 제한 없이 읽은 저장 쿼리 원본 행 — 복제(clone)가 접근 판정(공유 여부·소유자)과 사본 생성에 읽는 열 전부다(WD-485). */
  record RawSavedQuery(
      Long id,
      String name,
      String description,
      String sqlText,
      Long datasetId,
      String folder,
      Boolean isShared,
      Long createdBy) {}

  /**
   * id로 레코드를 소유권 제한 없이 조회한다 (clone 등 내부 처리용).
   *
   * <p>이전에 존재하던 {@code findRawById(Long id)} 메서드는 WHERE 절에 {@code SQ_CREATED_BY.eq(SQ_CREATED_BY)}
   * — 컬럼을 자기 자신과 비교하는 tautology 조건이 포함되어 있었다. 해당 메서드는 호출부가 없는 dead code였으며, 실제 소유권 필터링이 필요한 경우라면
   * {@link #findByIdForOwner(Long, Long)}를 사용해야 한다. 소유권 무관 조회는 이 메서드({@code
   * findRawByIdUnrestricted})가 올바른 구현이다.
   *
   * <p>서비스가 jOOQ Record 를 다루지 않도록 복제에 필요한 열을 {@link RawSavedQuery} 로 돌려준다(WD-485).
   */
  Optional<RawSavedQuery> findRawByIdUnrestricted(Long id);

  /**
   * 주어진 저장 쿼리 중 {@code ownerId} 가 만든 것만 공유로 바꾼다(WD-194).
   *
   * <p>대시보드 공유 시 위젯 차트가 쓰는 쿼리를 함께 공유하는 용도다. 차트 주인이 아니라 <b>쿼리 주인</b>으로 거른다 — 내 차트가 남의 쿼리(만들 때는 공유였다가
   * 주인이 비공개로 되돌린 것)를 쓰는 경우에도 그 쿼리는 주인만 공유할 수 있어야 하기 때문이다.
   */
  Map<Long, String> shareQueries(List<Long> queryIds, Long ownerId);

  /**
   * 주어진 저장 쿼리 중 {@code ownerId} 가 만든 것을 공유 여부별로 센다(WD-325) — [비공개 수, 공유 수]. {@link #shareQueries} 와
   * 같은 대상(작성자 = 요청자)이다.
   */
  int[] countOwnedBySharing(List<Long> queryIds, Long ownerId);

  Long insert(CreateSavedQueryRequest req, Long userId);

  /**
   * 수정 시각을 행 잠금(FOR UPDATE)과 함께 읽는다 — 편집 충돌(낙관적 잠금) 비교용(WD-364, 파이프라인 WD-353 과 같은 방식).
   *
   * <p>잠금 없이 읽고 비교하면 동시에 들어온 두 저장이 같은 값을 읽어 둘 다 통과한다. 잠금을 잡으면 나중 저장은 앞 저장이 커밋될 때까지 기다렸다가 바뀐 값을 읽어
   * 충돌로 거부된다. 소유자 확인을 마친 뒤에 부른다 — 먼저 부르면 남의 행에 대한 낡은 요청이 404 대신 409 를 받아 존재가 드러난다.
   */
  Optional<LockedRowVersion> lockUpdatedAtById(Long id);

  void update(Long id, UpdateSavedQueryRequest req, Long userId);

  boolean deleteById(Long id, Long userId);

  /**
   * 폴더 필터 드롭다운용 폴더 이름 목록. 목록과 같은 {@link #listScope} 로 범위를 맞춘다(WD-328) — 「내 쿼리」 탭에서 남의 공유 쿼리에만 있는
   * 폴더를 고르면 결과가 늘 비어 버리기 때문이다. 플래그가 없으면 기존처럼 내 쿼리 + 공유 쿼리의 폴더.
   */
  List<String> findDistinctFolders(Boolean sharedOnly, Boolean mineOnly, Long userId);

  /** Count charts referencing a query created by a user OTHER than the owner. */
  long countOtherUserCharts(Long savedQueryId, Long ownerId);
}
