package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.dto.CategoryResponse;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.dto.UpdateDatasetRequest;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.DatasetSchemaMeta;
import com.smartfirehub.dataset.repository.DatasetWriteTarget;
import com.smartfirehub.dataset.repository.mybatis.DatasetRows.DatasetRow;
import com.smartfirehub.dataset.repository.mybatis.DatasetRows.LockedRow;
import com.smartfirehub.dataset.repository.mybatis.DatasetRows.SchemaRow;
import com.smartfirehub.dataset.repository.mybatis.DatasetRows.TagRow;
import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.util.LikePatternUtils;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.dto.SecurityLevelSummary;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * DatasetRepository 의 MyBatis 구현(WD-707) — {@code JooqDatasetRepository} 와 같은 의미.
 *
 * <p>클래스 레벨 {@code @Transactional} 은 jOOQ 구현 그대로다 — 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없고
 * RLS 테넌트 컨텍스트는 트랜잭션이 열릴 때 심기므로, 트랜잭션이 없으면 정책이 전 행을 막는다(REQUIRED 라 서비스 트랜잭션에는 합류).
 *
 * <p>등급 요약: jOOQ 판은 {@code SecurityLevelRepository.findAll()} 로 읽지만 그 인터페이스는 아직 MyBatis 짝이 없다(묶음
 * ⑥). Tibero 설치본에서 주입할 빈이 없으므로 같은 행(현재 테넌트의 security_level 전부)을 이 매퍼가 직접 읽어 {@link
 * SecurityLevelSummary} 로 옮긴다 — 결과는 {@code SecurityLevelSummary.of(LevelPolicy)} 와 같다.
 *
 * <p>잠금 모드는 스펙 D2 — PG 는 jOOQ 와 같은 모드, Tibero 는 모두 FOR UPDATE(매퍼 XML 의 lock* 조각). 빈 문자열은 변환하지 않는다 —
 * Tibero 는 {@code ''} 를 NULL 로 저장한다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisDatasetRepository implements DatasetRepository {

  private final DatasetMapper mapper;

  /** 즐겨찾기 표시용 조회 — dataset_favorite 문장은 DatasetFavoriteMapper 한 곳에 둔다(중복 문장 제거). */
  private final DatasetFavoriteMapper favoriteMapper;

  /** advisory 잠금 창구 — 이름·테이블명 검사 직렬화 잠금(WD-540)에 쓴다. */
  private final PlatformLocks locks;

  public MyBatisDatasetRepository(
      DatasetMapper mapper, DatasetFavoriteMapper favoriteMapper, PlatformLocks locks) {
    this.mapper = mapper;
    this.favoriteMapper = favoriteMapper;
    this.locks = locks;
  }

  /** 빈·공백 필터는 걸지 않는다(jOOQ 의 isBlank 검사와 같다). */
  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  /** 검색 패턴 — jOOQ 의 LikePatternUtils.containsPattern 처럼 앞뒤 공백을 걷고 %·_·\ 를 이스케이프한다. 빈 검색어면 null. */
  private static String searchPattern(String search) {
    return search == null || search.isBlank() ? null : LikePatternUtils.containsPattern(search);
  }

  /** 즐겨찾기만 보기는 사용자가 있을 때만 건다(jOOQ 와 같다). */
  private static Long favoriteUser(boolean favoriteOnly, Long currentUserId) {
    return favoriteOnly && currentUserId != null ? currentUserId : null;
  }

  /** 목록 한 쪽 — 가시성·필터·검색어를 건 id 한 쪽을 먼저 고른 뒤 응답 행·태그·즐겨찾기·등급을 모아 조립한다(jOOQ 판과 같은 모양·순서). */
  @Override
  public List<DatasetResponse> findAll(
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
      DatasetVisibility access) {
    // 먼저 조인으로 늘어난 행을 걷은 id 한 쪽을 고른다(다중 조인 중복 제거 뒤 페이지).
    List<Long> datasetIds =
        mapper.selectPageIds(
            categoryId,
            blankToNull(storageType),
            blankToNull(originType),
            blankToNull(status),
            securityLevelId,
            searchPattern(search),
            favoriteUser(favoriteOnly, currentUserId),
            MyBatisDatasetVisibility.params(access),
            size,
            page * size);
    if (datasetIds.isEmpty()) {
      return List.of();
    }
    Set<Long> favoriteIds = fetchFavoriteIds(currentUserId, datasetIds);
    Map<Long, List<String>> tags = fetchTagsByDatasetIds(datasetIds);
    Map<Long, SecurityLevelSummary> levels = fetchLevelSummaries();
    List<DatasetResponse> out = new ArrayList<>();
    for (DatasetRow r : mapper.selectByIds(InChunks.of(datasetIds))) {
      out.add(toResponse(r, favoriteIds, tags, levels));
    }
    return out;
  }

  /** {@link #findAll} 과 같은 조건의 데이터셋 수(중복 제거). */
  @Override
  public long count(
      Long categoryId,
      String storageType,
      String originType,
      String search,
      Long currentUserId,
      String status,
      boolean favoriteOnly,
      Long securityLevelId,
      DatasetVisibility access) {
    return mapper.countFiltered(
        categoryId,
        blankToNull(storageType),
        blankToNull(originType),
        blankToNull(status),
        securityLevelId,
        searchPattern(search),
        favoriteUser(favoriteOnly, currentUserId),
        MyBatisDatasetVisibility.params(access));
  }

  /** WD-186·WD-666: 필드 변경 잠금 — FOR UPDATE(두 DB). 잠금 순서: 온톨로지 행 → 데이터셋 행 → 매핑 행. */
  @Override
  public void lockForColumnChange(Long id) {
    mapper.lockIdForUpdate(id);
  }

  /** WD-184: 지식 모델 재연결 잠금 — PG NO KEY UPDATE, Tibero FOR UPDATE. */
  @Override
  public void lockForOntologyRebind(Long id) {
    mapper.lockIdNoKeyUpdate(id);
  }

  /** WD-186: 매핑 저장의 공유 잠금 — PG SHARE, Tibero FOR UPDATE(매핑 저장끼리도 줄 선다 — 스펙 D2 의 대가). */
  @Override
  public void lockForShare(Long id) {
    mapper.lockIdShare(id);
  }

  /** WD-666: 데이터 쓰기 잠금 + 쓰기 대상 읽기 — PG KEY SHARE, Tibero FOR UPDATE. */
  @Override
  public Optional<DatasetWriteTarget> lockForDataWrite(Long id) {
    return Optional.ofNullable(mapper.lockWriteTargetKeyShare(id));
  }

  /** 테이블 이름으로 찾는 데이터 쓰기 잠금 — {@link #lockForDataWrite} 와 같은 모드(PG KEY SHARE, Tibero FOR UPDATE). */
  @Override
  public Optional<DatasetWriteTarget> lockForDataWriteByTableName(String tableName) {
    return Optional.ofNullable(mapper.lockWriteTargetKeyShareByTableName(tableName));
  }

  /** WD-666: 비우기·교체 — FOR UPDATE(두 DB). */
  @Override
  public Optional<DatasetWriteTarget> lockForStructureWrite(Long id) {
    return Optional.ofNullable(mapper.lockWriteTargetForUpdate(id));
  }

  /** WD-551·WD-666: 필드 순서 변경 — PG NO KEY UPDATE, Tibero FOR UPDATE. */
  @Override
  public Optional<DatasetWriteTarget> lockForColumnReorder(Long id) {
    return Optional.ofNullable(mapper.lockWriteTargetNoKeyUpdate(id));
  }

  /** WD-583: 삭제 배타 잠금 — FOR UPDATE(두 DB). 동시 삭제의 뒤 요청은 앞 커밋 뒤 행이 없어 false. */
  @Override
  public boolean lockForDelete(Long id) {
    return !mapper.lockIdForUpdate(id).isEmpty();
  }

  /** 사용자 없이 단건 조회 — 즐겨찾기는 거짓으로 채운다. */
  @Override
  public Optional<DatasetResponse> findById(Long id) {
    return findById(id, null);
  }

  /**
   * 단건 조회 — 행을 먼저 읽어 없으면 곧바로 빈 값을 돌려주고(즐겨찾기·태그·등급 조회 왕복을 아낀다), 즐겨찾기 여부는 사용자의 즐겨찾기 전체가 아니라 이 데이터셋
   * 하나만 확인한다. 응답 모양은 목록 경로와 같은 {@code toResponse} 를 거쳐 jOOQ 판과 같다.
   */
  @Override
  public Optional<DatasetResponse> findById(Long id, Long currentUserId) {
    DatasetRow row = mapper.selectById(id);
    if (row == null) {
      return Optional.empty();
    }
    Set<Long> favoriteIds =
        currentUserId != null && favoriteMapper.count(currentUserId, id) > 0
            ? Set.of(id)
            : Collections.emptySet();
    Map<Long, List<String>> tags = fetchTagsByDatasetIds(List.of(id));
    Map<Long, SecurityLevelSummary> levels = fetchLevelSummaries();
    return Optional.of(toResponse(row, favoriteIds, tags, levels));
  }

  /**
   * 넣고 생성 키로 다시 읽어 돌려준다(Tibero 는 RETURNING 이 없다). 응답 모양은 jOOQ 판과 같다 — 카테고리만 채우고 즐겨찾기 false·태그 빈
   * 목록·상태 변경자 이름 null·등급 요약 null(등급을 모르는 15 인자 생성자).
   */
  @Override
  public DatasetResponse save(CreateDatasetRequest request, Long createdBy) {
    DatasetInsert row = new DatasetInsert(request, createdBy);
    mapper.insert(row);
    DatasetRow r = mapper.selectById(row.getId());
    return new DatasetResponse(
        r.id(),
        r.name(),
        r.tableName(),
        r.description(),
        category(r),
        r.storageType(),
        r.originType(),
        r.createdAt(),
        false,
        List.of(),
        r.status() != null ? r.status() : "NONE",
        r.statusNote(),
        null,
        r.statusUpdatedAt(),
        r.sourcePipelineStepId());
  }

  /** 이 파이프라인 스텝이 출력으로 만든 데이터셋 id. 없으면 빈 값. */
  @Override
  public Optional<Long> findBySourcePipelineStepId(Long stepId) {
    return Optional.ofNullable(mapper.selectIdBySourcePipelineStepId(stepId));
  }

  /**
   * WD-575·WD-666: 정보 수정의 편집 기준 버전 비교용 행 잠금 — PG NO KEY UPDATE, Tibero FOR UPDATE. 행은 있는데 수정 시각이
   * NULL 이면 updatedAt=null 인 값(행 없음과 구분 — id 를 함께 읽는다).
   */
  @Override
  public Optional<LockedRowVersion> lockUpdatedAtById(Long id) {
    LockedRow row = mapper.lockUpdatedAt(id);
    return row == null ? Optional.empty() : Optional.of(new LockedRowVersion(row.updatedAt()));
  }

  /** 수정 시각은 jOOQ 판처럼 앱 시계(LocalDateTime.now())다. */
  @Override
  public void update(Long id, UpdateDatasetRequest request, Long updatedBy) {
    mapper.update(
        id,
        request.name(),
        request.description(),
        request.categoryId(),
        LocalDateTime.now(),
        updatedBy);
  }

  /** 상태·상태 메모·변경자를 바꾸고 변경 시각을 자바 현재 시각으로 남긴다. */
  @Override
  public void updateStatus(Long id, String status, String statusNote, Long statusUpdatedBy) {
    mapper.updateStatus(id, status, statusNote, statusUpdatedBy, LocalDateTime.now());
  }

  /** 영향 행 수 — 0 이면 이미 지워진 데이터셋이라 호출부가 404 로 끝낸다(WD-583). */
  @Override
  public int deleteById(Long id) {
    return mapper.deleteById(id);
  }

  /** 이름 유일 범위는 「볼 수 있는 데이터셋」(WD-155) — 가시성 조각을 함께 건다. */
  @Override
  public boolean existsVisibleByName(String name, DatasetVisibility visible, Long excludeId) {
    return mapper.countVisibleByName(name, MyBatisDatasetVisibility.params(visible), excludeId) > 0;
  }

  /**
   * 이름 검사 직렬화 잠금(WD-540) — 키·이유는 {@link DatasetRepository#acquireNameLock}. 키는 jOOQ 판과 같은 문자열이다.
   * MANDATORY 라 트랜잭션 밖 호출은 예외로 드러난다(REQUIRED 면 이 호출만의 트랜잭션이 바로 닫혀 잠금이 아무것도 막지 못한다).
   */
  @Transactional(propagation = Propagation.MANDATORY)
  @Override
  public void acquireNameLock(long tenantId, String name) {
    locks.xactLock("dataset-name:" + tenantId + ":" + name);
  }

  /** 테이블명 검사 직렬화 잠금(WD-540) — 키·MANDATORY 이유는 위와 같다. */
  @Transactional(propagation = Propagation.MANDATORY)
  @Override
  public void acquireTableNameLock(long tenantId, String tableName) {
    locks.xactLock("dataset-table:" + tenantId + ":" + tableName);
  }

  /** 같은 테이블 이름의 데이터셋이 있는지(현재 테넌트, RLS). */
  @Override
  public boolean existsByTableName(String tableName) {
    return mapper.countByTableName(tableName) > 0;
  }

  /** 데이터셋의 테이블 이름. 없으면 빈 값. */
  @Override
  public Optional<String> findTableNameById(Long id) {
    return Optional.ofNullable(mapper.selectTableNameById(id));
  }

  /** 데이터셋 생성자 id. 행이 없거나 값이 NULL 이면 빈 값. */
  @Override
  public Optional<Long> findCreatedByById(Long id) {
    return Optional.ofNullable(mapper.selectCreatedById(id));
  }

  /** 값이 NULL 이어도 빈 값 — jOOQ 의 fetchOptional(매퍼) 와 같다. */
  @Override
  public Optional<LocalDateTime> findUpdatedAtById(Long id) {
    return Optional.ofNullable(mapper.selectUpdatedAtById(id));
  }

  /** 데이터셋 마지막 수정자 id. 행이 없거나 값이 NULL 이면 빈 값. */
  @Override
  public Optional<Long> findUpdatedByById(Long id) {
    return Optional.ofNullable(mapper.selectUpdatedById(id));
  }

  /**
   * 스키마 메타를 한 번의 조회(데이터셋 LEFT JOIN 컬럼)로 읽는다(WD-494). id 목록은 예전 문자열 IN 대신 500 개씩 나눈 바인드 청크다 — Tibero
   * 는 IN 목록 1000 개를 넘으면 거절한다(Review Focus 3).
   */
  @Override
  public List<DatasetSchemaMeta> findSchemaMetas(List<Long> datasetIds, String visibilitySql) {
    List<List<Long>> chunks = datasetIds == null ? null : InChunks.of(datasetIds);
    // 행은 데이터셋별로 붙어 오므로(ORDER BY table_name, id) 순서를 지키는 맵에 모은다.
    Map<Long, DatasetSchemaMeta> byId = new LinkedHashMap<>();
    for (SchemaRow r : mapper.selectSchemaMetaRows(visibilitySql, chunks)) {
      DatasetSchemaMeta meta =
          byId.computeIfAbsent(
              r.id(),
              k ->
                  new DatasetSchemaMeta(
                      k, r.name(), r.tableName(), r.storageType(), new ArrayList<>()));
      if (r.columnName() != null) {
        // LEFT JOIN 의 빈 쪽(컬럼 없는 데이터셋)은 column_name 이 null 이다 — 컬럼으로 담지 않는다.
        meta.columns()
            .add(new DatasetSchemaMeta.Column(r.columnName(), r.dataType(), r.displayName()));
      }
    }
    return new ArrayList<>(byId.values());
  }

  /** 테이블 이름별 보안 등급 id — onlyTable 이 있으면 그 테이블만. */
  @Override
  public List<DatasetTableLevel> findTableLevels(String onlyTable) {
    return mapper.selectTableLevels(onlyTable);
  }

  // ---------------------------------------------------------------- 응답 조립(jOOQ 판과 같은 모양)

  private DatasetResponse toResponse(
      DatasetRow r,
      Set<Long> favoriteIds,
      Map<Long, List<String>> tagsByDatasetId,
      Map<Long, SecurityLevelSummary> levels) {
    return new DatasetResponse(
        r.id(),
        r.name(),
        r.tableName(),
        r.description(),
        category(r),
        r.storageType(),
        r.originType(),
        r.createdAt(),
        favoriteIds.contains(r.id()),
        tagsByDatasetId.getOrDefault(r.id(), List.of()),
        r.status() != null ? r.status() : "NONE",
        r.statusNote(),
        r.statusUpdatedByName(),
        r.statusUpdatedAt(),
        r.sourcePipelineStepId(),
        levels.get(r.securityLevelId()),
        r.securityLevelAutoRaisedAt());
  }

  private static CategoryResponse category(DatasetRow r) {
    return r.categoryId() != null
        ? new CategoryResponse(r.categoryId(), r.categoryName(), r.categoryDescription())
        : null;
  }

  /** 테넌트 등급은 보통 4~10개 — 행마다 조인하는 대신 한 번 읽어 id 로 매핑한다(jOOQ 판과 같다). */
  private Map<Long, SecurityLevelSummary> fetchLevelSummaries() {
    Map<Long, SecurityLevelSummary> out = new HashMap<>();
    for (SecurityLevelSummary s : mapper.selectLevelSummaries()) {
      out.put(s.id(), s);
    }
    return out;
  }

  private Map<Long, List<String>> fetchTagsByDatasetIds(List<Long> datasetIds) {
    if (datasetIds == null || datasetIds.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<Long, List<String>> result = new HashMap<>();
    for (TagRow t : mapper.selectTagsByDatasetIds(InChunks.of(datasetIds))) {
      result.computeIfAbsent(t.datasetId(), k -> new ArrayList<>()).add(t.tagName());
    }
    return result;
  }

  /** 한 쪽의 데이터셋 id 가운데 사용자가 즐겨찾기한 것만 읽는다 — 사용자의 즐겨찾기 전체를 읽지 않는다(응답의 즐겨찾기 표시는 같다). */
  private Set<Long> fetchFavoriteIds(Long currentUserId, List<Long> datasetIds) {
    if (currentUserId == null) {
      return Collections.emptySet();
    }
    return new HashSet<>(
        favoriteMapper.selectDatasetIdsAmong(currentUserId, InChunks.of(datasetIds)));
  }
}
