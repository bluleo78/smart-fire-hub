package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.dto.UpdateDatasetRequest;
import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 데이터셋 정의(dataset) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqDatasetRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface DatasetRepository {

  /** 필터(카테고리·저장유형·출처·검색어·상태·즐겨찾기·보안등급)와 열람 가시성을 적용해 데이터셋 목록을 페이지 단위로 조회한다. */
  List<DatasetResponse> findAll(
      Long categoryId,
      String storageType,
      String originType,
      String search,
      int page,
      int size,
      Long currentUserId,
      String status,
      boolean favoriteOnly,
      Long securityLevelId,
      DatasetVisibility access);

  /** {@link #findAll} 과 같은 필터·가시성을 적용한 전체 건수를 센다. */
  long count(
      Long categoryId,
      String storageType,
      String originType,
      String search,
      Long currentUserId,
      String status,
      boolean favoriteOnly,
      Long securityLevelId,
      DatasetVisibility access);

  /**
   * WD-186: 필드 이름 변경·타입 변경·삭제가 잡는 데이터셋 행 잠금. 매핑 저장·활성화(MappingService.validate — 이 행을 FOR SHARE 로
   * 잡고 나서 필드 목록을 읽는다)와 직렬화해, "옛 필드 목록으로 검증한 매핑이 필드 변경 뒤에 저장되는" 틈을 없앤다. 잠금 순서(교착 방지): 온톨로지 행 → 데이터셋
   * 행 → 매핑 행. MappingReferenceService.lockForColumnChange 참고.
   *
   * <p>WD-666: 모드는 FOR UPDATE 다(예전 NO KEY UPDATE). 데이터 테이블 쓰기(가져오기·파이프라인 적재·SQL DML·행 쓰기)가 정보 수정을
   * 막지 않도록 KEY SHARE({@link #lockForDataWrite})만 잡게 됐고, KEY SHARE 와 충돌하는 행 잠금은 FOR UPDATE 뿐이라 구조
   * 변경이 쓰기 뒤로 줄 서려면 이 모드여야 한다. 대가: 구조 변경이 도는 짧은 동안 이 데이터셋을 FK 로 참조하는 다른 트랜잭션의 삽입(KEY SHARE)도
   * 기다린다(WD-186 이 NO KEY UPDATE 를 고른 이유를 의도적으로 맞바꾼 것).
   */
  void lockForColumnChange(Long id);

  /**
   * WD-184: 지식 모델 연결·재연결(DatasetOntologyService.bind)이 잡는 데이터셋 행 잠금. 재연결은 "이전 연결 읽기 → 연결 갱신 → 옛 모델
   * 기준 매핑 삭제"를 한 덩어리로 해야 하는데, 매핑 저장·활성화(MappingService.validate — 이 행을 FOR SHARE 로 잡는다)와 직렬화하지 않으면
   * 옛 모델로 검증한 매핑이 재연결 뒤에 저장돼 고아 매핑이 다시 생긴다. NO KEY UPDATE 인 이유는 lockForColumnChange 와 같다. 잠금 순서:
   * 데이터셋 행 → 매핑 행(온톨로지 행은 잡지 않는다) — 온톨로지 행 → 데이터셋 행 → 매핑 행 규약의 부분 순서다.
   */
  void lockForOntologyRebind(Long id);

  /**
   * WD-186: 매핑 저장·활성화가 필드 목록을 읽기 전에 잡는 공유 잠금 — 진행 중인 필드 변경(lockForColumnChange)이 끝난 뒤의 필드 목록으로 검증하게
   * 한다. 매핑 저장끼리는 서로 막지 않는다(SHARE 는 서로 호환). 데이터 테이블 쓰기는 이 잠금이 아니라 {@link #lockForDataWrite} 를
   * 쓴다(WD-666 — SHARE 는 정보 수정의 UPDATE 와 충돌한다).
   */
  void lockForShare(Long id);

  /**
   * WD-666: 데이터 테이블 쓰기(행 추가·수정·삭제, 가져오기, 파이프라인 적재·맞바꿈, 사용자 SQL DML)의 첫 문장 — 데이터셋 행을 {@code FOR KEY
   * SHARE OF dataset} 로 잡으면서 쓰기에 필요한 값을 같은 문장으로 읽는다(잠금·조회 한 번).
   *
   * <p>왜 KEY SHARE: 정보 수정(이름·설명 — {@link #lockUpdatedAtById} 의 NO KEY UPDATE 와 이어지는 UPDATE)과는 충돌하지
   * 않고, 구조 변경({@link #lockForColumnChange}·{@link #lockForStructureWrite}·{@link #lockForDelete} —
   * 모두 FOR UPDATE)과만 충돌해 그 뒤로 줄 선다. 쓰기끼리도 서로 막지 않는다. 한 문장이므로 READ COMMITTED 는 잠금을 기다린 뒤 행을 다시 확인한다
   * — 기다리는 동안 삭제가 커밋됐다면 빈 값이다.
   *
   * @return 잠근 데이터셋의 쓰기 대상 정보, 행이 없으면(삭제됨) 빈 값
   */
  Optional<DatasetWriteTarget> lockForDataWrite(Long id);

  /** {@link #lockForDataWrite} 의 테이블 이름판 — 출력 테이블 이름만 아는 파이프라인 적재가 쓴다(현재 테넌트, RLS). */
  Optional<DatasetWriteTarget> lockForDataWriteByTableName(String tableName);

  /**
   * WD-666: 데이터 테이블을 통째로 바꾸는 쓰기(truncate·replace)와 필드 순서 변경의 첫 문장 — {@code FOR UPDATE OF dataset} 로
   * 잡으며 같은 문장으로 읽는다. 데이터 쓰기(KEY SHARE)·필드 변경과 줄 선다. 정보 수정과도 짧게 줄 선다(구조 변경에만 강한 잠금을 쓴다).
   *
   * @return 잠근 데이터셋의 쓰기 대상 정보, 행이 없으면 빈 값
   */
  Optional<DatasetWriteTarget> lockForStructureWrite(Long id);

  /**
   * WD-551·WD-666: 필드 순서 변경의 첫 문장 — {@code FOR NO KEY UPDATE} 로 잡으며 같은 문장으로 읽는다. 순서 변경·필드 변경(FOR
   * UPDATE)끼리 줄 세우되, 데이터 테이블을 건드리지 않으므로 데이터 쓰기(KEY SHARE)는 막지 않는다.
   *
   * @return 잠근 데이터셋 정보, 행이 없으면 빈 값
   */
  Optional<DatasetWriteTarget> lockForColumnReorder(Long id);

  /**
   * WD-583: 데이터셋 삭제가 맨 앞에서 잡는 배타 잠금({@code FOR UPDATE}). 같은 데이터셋 삭제가 동시에 들어오면 뒤 요청은 이 잠금에서 앞 요청 커밋을
   * 기다렸다가 행이 없어 false 를 받는다 — 색인 삭제·DROP·감사가 한 번만 돈다. FOR UPDATE 인 이유: 삭제는 키를 지우므로 이 행을 FK 로 참조하는
   * 삽입(KEY SHARE, 예: 스텝 출력 데이터셋 지정)까지 막아야 참조 검사와 삭제 사이에 새 참조가 끼어들지 않는다.
   *
   * @return 행을 잠갔으면 true, 행이 없으면(이미 삭제됨) false
   */
  boolean lockForDelete(Long id);

  /** id 로 데이터셋 한 건을 조회한다(즐겨찾기 여부는 채우지 않는다). 없으면 빈 값. */
  Optional<DatasetResponse> findById(Long id);

  /** id 로 데이터셋 한 건을 조회하며 현재 사용자의 즐겨찾기 여부를 함께 채운다. 없으면 빈 값. */
  Optional<DatasetResponse> findById(Long id, Long currentUserId);

  /** 데이터셋 정의를 새로 저장하고 저장된 행을 돌려준다. */
  DatasetResponse save(CreateDatasetRequest request, Long createdBy);

  /** 파이프라인 스텝이 만든 데이터셋의 id 를 조회한다. 없으면 빈 값. */
  Optional<Long> findBySourcePipelineStepId(Long stepId);

  /**
   * 수정 시각을 행 잠금(FOR NO KEY UPDATE)과 함께 읽는다 — 정보 수정의 편집 충돌(낙관적 잠금) 비교용(WD-575, 차트 WD-364 와 같은 방식).
   * 정보 수정끼리는 여전히 줄 서고, 데이터 쓰기(KEY SHARE, WD-666)와는 충돌하지 않는다 — 키를 바꾸지 않으므로 FOR UPDATE 일 이유가 없다.
   *
   * <p>잠금 없이 읽고 비교하면 동시에 들어온 두 저장이 같은 값을 읽어 둘 다 통과한다. 잠금을 잡으면 나중 저장은 앞 저장이 커밋될 때까지 기다렸다가 바뀐 값을 읽어
   * 충돌로 거부된다. 행이 없으면 빈 값, 행은 있는데 수정 시각이 비어 있으면 {@code updatedAt} 이 null 인 값이다.
   */
  Optional<LockedRowVersion> lockUpdatedAtById(Long id);

  /** 데이터셋의 이름·설명·카테고리·태그 등 변경 가능한 항목을 갱신한다. */
  void update(Long id, UpdateDatasetRequest request, Long updatedBy);

  /** 데이터셋의 이름·설명·카테고리·태그 등 변경 가능한 항목을 갱신한다. */
  void updateStatus(Long id, String status, String statusNote, Long statusUpdatedBy);

  /**
   * id 로 데이터셋 정의를 삭제한다.
   *
   * @return 지운 행 수(0 이면 이미 없음) — 호출부가 「지운 것이 없는데 성공」으로 끝나지 않게 판단한다(WD-583).
   */
  int deleteById(Long id);

  /**
   * 조회자가 볼 수 있는 데이터셋 가운데 같은 이름이 있는가(WD-155). 이름 유일 범위는 「볼 수 있는 데이터셋」이다 — 볼 수 없는(상위 등급) 데이터셋과 겹치는지까지
   * 보면 이름을 맞혀 보는 것만으로 존재가 드러난다. DB 유일 제약도 없다(V142).
   *
   * @param visible 조회자 가시성({@code DatasetAccessGuard.visibility})
   * @param excludeId 이름 변경 시 자기 자신(없으면 null)
   */
  boolean existsVisibleByName(String name, DatasetVisibility visible, Long excludeId);

  /**
   * 테넌트 안에서 데이터셋 이름 하나를 두고 트랜잭션 범위 잠금을 건다(WD-540). 커밋·롤백 때 풀린다.
   *
   * <p>왜: 이름 중복 검사({@link #existsVisibleByName})는 확인 뒤 쓰는 구조인데 이름에는 DB 유일 제약이 없다(V142 — 유일 범위가 「볼 수
   * 있는 데이터셋」이라 제약으로 표현할 수 없다). 같은 이름의 생성·복제·이름 변경이 겹치면 서로의 미커밋 행을 못 봐 모두 통과한다. 검사 직전에 이 잠금을 잡으면 두
   * 번째 트랜잭션은 첫 번째가 끝난 뒤 검사해 커밋된 행을 본다(READ COMMITTED).
   *
   * <p>키는 {@code "dataset-name:" + 테넌트 id + ":" + 이름} 문자열의 해시다 — 접두어로 다른 advisory 잠금과 키 공간을 나누고,
   * 테넌트를 넣어 다른 테넌트의 같은 이름끼리는 막지 않는다. 이름은 검사({@code name = ?})와 같게 원문 그대로 쓴다. 반드시 호출자의 트랜잭션 안에서 불러야
   * 한다(밖이면 문장이 끝나자마자 풀린다 — 구현은 트랜잭션이 없으면 예외로 거부한다).
   *
   * <p>테이블명 잠금({@link #acquireTableNameLock})과 함께 잡을 때는 항상 이 잠금을 먼저 잡는다(고정 순서 — 교착 방지).
   *
   * @param tenantId 호출자가 {@code TenantContext.require} 로 확정한 테넌트
   */
  void acquireNameLock(long tenantId, String name);

  /**
   * 테넌트 안에서 물리 테이블 이름 하나를 두고 트랜잭션 범위 잠금을 건다(WD-540). 커밋·롤백 때 풀린다.
   *
   * <p>왜: 테이블명은 {@code (tenant_id, table_name)} 유일 인덱스가 있지만(V109), 검사({@link #existsByTableName}) 뒤
   * 쓰는 사이에 같은 테이블명의 생성·복제가 끼어들면 둘 다 검사를 통과하고 늦은 쪽이 인덱스 위반(500)으로 끝난다. 이 잠금으로 줄 세우면 늦은 쪽은 순차일 때와 같은
   * 409 를 받는다. 키 접두어 {@code "dataset-table:"} 는 이름 잠금과 키 공간을 나눈다. 이름 잠금 뒤에만 잡는다(고정 순서).
   *
   * @param tenantId 호출자가 {@code TenantContext.require} 로 확정한 테넌트
   */
  void acquireTableNameLock(long tenantId, String tableName);

  /** 같은 물리 테이블 이름을 쓰는 데이터셋이 있는지 확인한다. */
  boolean existsByTableName(String tableName);

  /** 데이터셋의 물리 테이블 이름을 조회한다. 없으면 빈 값. */
  Optional<String> findTableNameById(Long id);

  /** 데이터셋 생성자 id 를 조회한다. 없으면 빈 값. */
  Optional<Long> findCreatedByById(Long id);

  /** 데이터셋의 마지막 수정 시각을 조회한다. 없으면 빈 값. */
  Optional<LocalDateTime> findUpdatedAtById(Long id);

  /** 데이터셋의 마지막 수정자 id 를 조회한다. 없으면 빈 값. */
  Optional<Long> findUpdatedByById(Long id);

  /**
   * 열람 가능한 데이터셋의 스키마 메타(데이터셋 + 컬럼 메타)를 {@code table_name} 순으로 조회한다(WD-494). 분석 편집기 스키마 정보는 이 메타와
   * 데이터 저장소의 물리 컬럼을 앱에서 합쳐 만든다.
   *
   * @param datasetIds null 이면 전체, 아니면 해당 id 만(빈 목록은 호출자가 걸러 넣지 않는다)
   * @param visibilitySql 별칭 {@code d}(dataset) 기준 열람 가능 조건 SQL 조각({@code
   *     DatasetAccessGuard.visibleSql})
   */
  List<DatasetSchemaMeta> findSchemaMetas(List<Long> datasetIds, String visibilitySql);

  /**
   * 물리 테이블이 있는 데이터셋의 (테이블 이름, 보안 등급 id) 목록(WD-494). PYTHON 읽기 GRANT 동기화가 "어느 테이블이 어느 등급인가" 를 읽는 데
   * 쓴다.
   *
   * @param onlyTable null 이면 전체, 아니면 그 테이블 하나만
   */
  List<DatasetTableLevel> findTableLevels(String onlyTable);

  /** 데이터셋 테이블 이름과 보안 등급 id 한 쌍. */
  record DatasetTableLevel(String tableName, Long securityLevelId) {}
}
