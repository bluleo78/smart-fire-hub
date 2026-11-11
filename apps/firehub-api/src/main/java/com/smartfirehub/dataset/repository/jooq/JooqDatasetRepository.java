package com.smartfirehub.dataset.repository.jooq;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.dataset.dto.CategoryResponse;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.dto.UpdateDatasetRequest;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.DatasetSchemaMeta;
import com.smartfirehub.dataset.repository.DatasetWriteTarget;
import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.global.util.LikePatternUtils;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.dto.SecurityLevelSummary;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.smartfirehub.securitylevel.repository.jooq.JooqDatasetVisibility;
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
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record4;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** DatasetRepository 의 jOOQ(PostgreSQL) 구현 — WD-485. */
@Repository
@ConditionalOnPostgresPlatformDb
@RequiredArgsConstructor
// 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없다. RLS GUC 는 트랜잭션 시작
// 시점에만 주입되므로, 트랜잭션이 없으면 TenantContext 를 세워도 정책이 전 행을 차단한다.
// REQUIRED 라 서비스가 이미 연 트랜잭션에는 합류한다(기존 경로 동작 불변).
@Transactional
public class JooqDatasetRepository implements DatasetRepository {

  private final DSLContext dsl;
  private final SecurityLevelRepository securityLevelRepository;

  /** advisory 잠금 창구 — 이름·테이블명 검사 직렬화 잠금(WD-540)에 쓴다. */
  private final PlatformLocks locks;

  private static final Table<?> DATASET = table(name("dataset"));
  private static final Field<Long> DS_ID = field(name("dataset", "id"), Long.class);
  private static final Field<String> DS_NAME = field(name("dataset", "name"), String.class);
  private static final Field<String> DS_TABLE_NAME =
      field(name("dataset", "table_name"), String.class);
  private static final Field<String> DS_DESCRIPTION =
      field(name("dataset", "description"), String.class);
  private static final Field<Long> DS_CATEGORY_ID =
      field(name("dataset", "category_id"), Long.class);
  // 저장 방식(TABLE/DOCUMENT)과 출처(SOURCE/DERIVED/TEMP)를 분리한 두 컬럼
  private static final Field<String> DS_STORAGE_TYPE =
      field(name("dataset", "storage_type"), String.class);
  private static final Field<String> DS_ORIGIN_TYPE =
      field(name("dataset", "origin_type"), String.class);
  private static final Field<Long> DS_CREATED_BY = field(name("dataset", "created_by"), Long.class);
  private static final Field<LocalDateTime> DS_CREATED_AT =
      field(name("dataset", "created_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> DS_UPDATED_AT =
      field(name("dataset", "updated_at"), LocalDateTime.class);
  private static final Field<Long> DS_UPDATED_BY = field(name("dataset", "updated_by"), Long.class);
  private static final Field<String> DS_STATUS = field(name("dataset", "status"), String.class);
  private static final Field<String> DS_STATUS_NOTE =
      field(name("dataset", "status_note"), String.class);
  private static final Field<Long> DS_STATUS_UPDATED_BY =
      field(name("dataset", "status_updated_by"), Long.class);
  private static final Field<LocalDateTime> DS_STATUS_UPDATED_AT =
      field(name("dataset", "status_updated_at"), LocalDateTime.class);
  private static final Field<Long> DS_SOURCE_PIPELINE_STEP_ID =
      field(name("dataset", "source_pipeline_step_id"), Long.class);
  // V133 보안 등급. 목록 필터·응답 요약·가시성 조건이 쓴다.
  private static final Field<Long> DS_SECURITY_LEVEL_ID =
      field(name("dataset", "security_level_id"), Long.class);
  private static final Field<LocalDateTime> DS_SECURITY_LEVEL_AUTO_RAISED_AT =
      field(name("dataset", "security_level_auto_raised_at"), LocalDateTime.class);

  private static final Table<?> DATASET_CATEGORY = table(name("dataset_category"));
  private static final Field<Long> DC_ID = field(name("dataset_category", "id"), Long.class);
  private static final Field<String> DC_NAME =
      field(name("dataset_category", "name"), String.class);
  private static final Field<String> DC_DESCRIPTION =
      field(name("dataset_category", "description"), String.class);

  private static final Table<?> DATASET_COLUMN = table(name("dataset_column"));
  private static final Field<Long> COL_DATASET_ID =
      field(name("dataset_column", "dataset_id"), Long.class);
  private static final Field<String> COL_COLUMN_NAME =
      field(name("dataset_column", "column_name"), String.class);
  private static final Field<String> COL_DISPLAY_NAME =
      field(name("dataset_column", "display_name"), String.class);
  private static final Field<String> COL_DESCRIPTION =
      field(name("dataset_column", "description"), String.class);

  private static final Table<?> DATASET_FAVORITE = table(name("dataset_favorite"));
  private static final Field<Long> DF_USER_ID =
      field(name("dataset_favorite", "user_id"), Long.class);
  private static final Field<Long> DF_DATASET_ID =
      field(name("dataset_favorite", "dataset_id"), Long.class);

  private static final Table<?> DATASET_TAG = table(name("dataset_tag"));
  private static final Field<Long> DT_DATASET_ID =
      field(name("dataset_tag", "dataset_id"), Long.class);
  private static final Field<String> DT_TAG_NAME =
      field(name("dataset_tag", "tag_name"), String.class);

  private static final Table<?> USER_TABLE = table(name("user"));
  private static final Field<Long> U_ID = field(name("user", "id"), Long.class);
  private static final Field<String> U_NAME = field(name("user", "name"), String.class);

  // status_updated_by 사용자 이름 JOIN을 위한 별칭 (user 테이블 중복 JOIN 시 충돌 방지)
  private static final Table<?> STATUS_USER_TABLE = table(name("user")).as("status_user");
  private static final Field<Long> SU_ID = field(name("status_user", "id"), Long.class);
  private static final Field<String> SU_NAME = field(name("status_user", "name"), String.class);

  private DatasetResponse mapToDatasetResponse(
      Record r,
      Set<Long> favoriteIds,
      Map<Long, List<String>> tagsByDatasetId,
      Map<Long, SecurityLevelSummary> levels) {
    CategoryResponse category =
        r.get(DC_ID) != null
            ? new CategoryResponse(r.get(DC_ID), r.get(DC_NAME), r.get(DC_DESCRIPTION))
            : null;
    Long id = r.get(DS_ID);
    boolean isFavorite = favoriteIds != null && favoriteIds.contains(id);
    List<String> tags =
        tagsByDatasetId != null ? tagsByDatasetId.getOrDefault(id, List.of()) : List.of();

    // N+1 제거: status_updated_by 사용자 이름은 JOIN으로 미리 조회된 값을 사용
    String statusUpdatedByName = r.get(SU_NAME);

    return new DatasetResponse(
        id,
        r.get(DS_NAME),
        r.get(DS_TABLE_NAME),
        r.get(DS_DESCRIPTION),
        category,
        r.get(DS_STORAGE_TYPE),
        r.get(DS_ORIGIN_TYPE),
        r.get(DS_CREATED_AT),
        isFavorite,
        tags,
        r.get(DS_STATUS) != null ? r.get(DS_STATUS) : "NONE",
        r.get(DS_STATUS_NOTE),
        statusUpdatedByName,
        r.get(DS_STATUS_UPDATED_AT),
        r.get(DS_SOURCE_PIPELINE_STEP_ID),
        levels.get(r.get(DS_SECURITY_LEVEL_ID)),
        r.get(DS_SECURITY_LEVEL_AUTO_RAISED_AT));
  }

  /** 테넌트 등급은 보통 4~10개 — 행마다 조인하는 대신 한 번 읽어 id 로 매핑한다. */
  private Map<Long, SecurityLevelSummary> fetchLevelSummaries() {
    return securityLevelRepository.findAll().stream()
        .collect(Collectors.toMap(LevelPolicy::id, SecurityLevelSummary::of));
  }

  private Map<Long, List<String>> fetchTagsByDatasetIds(List<Long> datasetIds) {
    if (datasetIds == null || datasetIds.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<Long, List<String>> result = new HashMap<>();
    dsl.select(DT_DATASET_ID, DT_TAG_NAME)
        .from(DATASET_TAG)
        .where(DT_DATASET_ID.in(datasetIds))
        .orderBy(DT_TAG_NAME.asc())
        .forEach(
            r -> {
              Long dsId = r.get(DT_DATASET_ID);
              result.computeIfAbsent(dsId, k -> new ArrayList<>()).add(r.get(DT_TAG_NAME));
            });
    return result;
  }

  private Set<Long> fetchFavoriteIds(Long currentUserId) {
    if (currentUserId == null) return Collections.emptySet();
    return new HashSet<>(
        dsl.select(DF_DATASET_ID)
            .from(DATASET_FAVORITE)
            .where(DF_USER_ID.eq(currentUserId))
            .fetch(r -> r.get(DF_DATASET_ID)));
  }

  private Condition buildCondition(
      Long categoryId,
      String storageType,
      String originType,
      String search,
      String status,
      Long securityLevelId,
      DatasetVisibility access) {
    // 열람 가능성(보안 등급)은 모든 다른 필터보다 먼저 AND 한다 — 검색어·필터가 가시성을 넓힐 수 없다.
    // 가시성 술어는 "dataset" 이름 관례(DS_ID·DS_SECURITY_LEVEL_ID 와 같은 이름)로 렌더한다.
    Condition condition = JooqDatasetVisibility.condition(access);
    if (securityLevelId != null) {
      condition = condition.and(DS_SECURITY_LEVEL_ID.eq(securityLevelId));
    }
    if (categoryId != null) {
      condition = condition.and(DS_CATEGORY_ID.eq(categoryId));
    }
    if (storageType != null && !storageType.isBlank()) {
      condition = condition.and(DS_STORAGE_TYPE.eq(storageType));
    }
    if (originType != null && !originType.isBlank()) {
      condition = condition.and(DS_ORIGIN_TYPE.eq(originType));
    }
    if (status != null && !status.isBlank()) {
      condition = condition.and(DS_STATUS.eq(status));
    }
    if (search != null && !search.isBlank()) {
      String pattern = LikePatternUtils.containsPattern(search);
      // 대규모 디스커버리(#282): 이름·설명·테이블명·컬럼 외에 태그명·카테고리명도 매칭해
      // "어느 데이터셋에서 답을 찾을지" 후보 선별의 신호를 넓힌다. (DATASET_TAG·DATASET_CATEGORY
      // LEFT JOIN 은 findAll/count 의 id 쿼리에 추가; selectDistinct/countDistinct 로 중복 제거됨)
      condition =
          condition.and(
              DS_NAME
                  .likeIgnoreCase(pattern, '\\')
                  .or(DS_DESCRIPTION.likeIgnoreCase(pattern, '\\'))
                  .or(DS_TABLE_NAME.likeIgnoreCase(pattern, '\\'))
                  .or(COL_COLUMN_NAME.likeIgnoreCase(pattern, '\\'))
                  .or(COL_DISPLAY_NAME.likeIgnoreCase(pattern, '\\'))
                  .or(COL_DESCRIPTION.likeIgnoreCase(pattern, '\\'))
                  .or(DT_TAG_NAME.likeIgnoreCase(pattern, '\\'))
                  .or(DC_NAME.likeIgnoreCase(pattern, '\\')));
    }
    return condition;
  }

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
    Condition condition =
        buildCondition(
            categoryId, storageType, originType, search, status, securityLevelId, access);

    // First get the distinct IDs for the page
    var idQuery =
        dsl.selectDistinct(DS_ID)
            .from(DATASET)
            .leftJoin(DATASET_COLUMN)
            .on(COL_DATASET_ID.eq(DS_ID))
            .leftJoin(DATASET_TAG)
            .on(DT_DATASET_ID.eq(DS_ID))
            .leftJoin(DATASET_CATEGORY)
            .on(DS_CATEGORY_ID.eq(DC_ID));

    if (favoriteOnly && currentUserId != null) {
      idQuery =
          idQuery
              .innerJoin(DATASET_FAVORITE)
              .on(DF_DATASET_ID.eq(DS_ID).and(DF_USER_ID.eq(currentUserId)));
    }

    List<Long> datasetIds =
        idQuery
            .where(condition)
            .orderBy(DS_ID.asc())
            .limit(size)
            .offset(page * size)
            .fetch(r -> r.get(DS_ID));

    if (datasetIds.isEmpty()) {
      return List.of();
    }

    Set<Long> favoriteIds = fetchFavoriteIds(currentUserId);
    Map<Long, List<String>> tagsByDatasetId = fetchTagsByDatasetIds(datasetIds);
    Map<Long, SecurityLevelSummary> levels = fetchLevelSummaries();

    // status_updated_by 사용자 이름을 JOIN으로 한 번에 조회하여 N+1 제거
    return dsl.select(
            DS_ID,
            DS_NAME,
            DS_TABLE_NAME,
            DS_DESCRIPTION,
            DS_STORAGE_TYPE,
            DS_ORIGIN_TYPE,
            DS_CREATED_AT,
            DS_STATUS,
            DS_STATUS_NOTE,
            DS_STATUS_UPDATED_BY,
            DS_STATUS_UPDATED_AT,
            DS_SOURCE_PIPELINE_STEP_ID,
            DS_SECURITY_LEVEL_ID,
            DS_SECURITY_LEVEL_AUTO_RAISED_AT,
            DC_ID,
            DC_NAME,
            DC_DESCRIPTION,
            SU_NAME)
        .from(DATASET)
        .leftJoin(DATASET_CATEGORY)
        .on(DS_CATEGORY_ID.eq(DC_ID))
        .leftJoin(STATUS_USER_TABLE)
        .on(SU_ID.eq(DS_STATUS_UPDATED_BY))
        .where(DS_ID.in(datasetIds))
        .orderBy(DS_ID.asc())
        .fetch(r -> mapToDatasetResponse(r, favoriteIds, tagsByDatasetId, levels));
  }

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
    Condition condition =
        buildCondition(
            categoryId, storageType, originType, search, status, securityLevelId, access);

    var countQuery =
        dsl.select(countDistinct(DS_ID))
            .from(DATASET)
            .leftJoin(DATASET_COLUMN)
            .on(COL_DATASET_ID.eq(DS_ID))
            .leftJoin(DATASET_TAG)
            .on(DT_DATASET_ID.eq(DS_ID))
            .leftJoin(DATASET_CATEGORY)
            .on(DS_CATEGORY_ID.eq(DC_ID));

    if (favoriteOnly && currentUserId != null) {
      return countQuery
          .innerJoin(DATASET_FAVORITE)
          .on(DF_DATASET_ID.eq(DS_ID).and(DF_USER_ID.eq(currentUserId)))
          .where(condition)
          .fetchOne(0, Long.class);
    }

    return countQuery.where(condition).fetchOne(0, Long.class);
  }

  // WD-186: 필드 이름 변경·타입 변경·삭제가 잡는 데이터셋 행 잠금. 매핑 저장·활성화(MappingService.validate — 이 행을 FOR SHARE
  // 로 잡고 나서 필드 목록을 읽는다)와 직렬화해, "옛 필드 목록으로 검증한 매핑이 필드 변경 뒤에 저장되는" 틈을 없앤다.
  // WD-666: FOR UPDATE 다 — 데이터 쓰기는 KEY SHARE 만 잡고, 그것과 충돌하는 모드는 FOR UPDATE 뿐이다.
  // 잠금 순서(교착 방지): 온톨로지 행 → 데이터셋 행 → 매핑 행. MappingReferenceService.lockForColumnChange 참고.
  @Override
  public void lockForColumnChange(Long id) {
    dsl.select(DS_ID).from(DATASET).where(DS_ID.eq(id)).forUpdate().fetch();
  }

  // WD-666: 데이터 쓰기 잠금(KEY SHARE) + 쓰기 대상 읽기를 한 문장으로. KEY SHARE 는 정보 수정과 호환되고 구조 변경·삭제(FOR UPDATE)와만
  // 충돌한다. 조인이 없어 OF 절 없이도 데이터셋 행만 잠근다.
  @Override
  public Optional<DatasetWriteTarget> lockForDataWrite(Long id) {
    return writeTargetWhere(DS_ID.eq(id)).forKeyShare().fetchOptional(this::toWriteTarget);
  }

  // WD-666: 테이블 이름판(파이프라인 출력). 테넌트 범위는 RLS 가 정한다(existsByTableName 과 같은 관례).
  @Override
  public Optional<DatasetWriteTarget> lockForDataWriteByTableName(String tableName) {
    return writeTargetWhere(DS_TABLE_NAME.eq(tableName))
        .forKeyShare()
        .fetchOptional(this::toWriteTarget);
  }

  // WD-666: 데이터 탭의 비우기·교체(사용자 truncate·replace) — FOR UPDATE 잠금 + 읽기를 한 문장으로.
  // 파일 가져오기 REPLACE 는 여기가 아니라 KEY SHARE(lockForDataWrite)다(WD-674 — DatasetDataWriteLock 클래스 문서).
  @Override
  public Optional<DatasetWriteTarget> lockForStructureWrite(Long id) {
    return writeTargetWhere(DS_ID.eq(id)).forUpdate().fetchOptional(this::toWriteTarget);
  }

  // WD-551·WD-666: 필드 순서 변경 — NO KEY UPDATE 잠금 + 읽기를 한 문장으로. 데이터 쓰기(KEY SHARE)는 막지 않는다.
  @Override
  public Optional<DatasetWriteTarget> lockForColumnReorder(Long id) {
    return writeTargetWhere(DS_ID.eq(id)).forNoKeyUpdate().fetchOptional(this::toWriteTarget);
  }

  /** 잠금 + 읽기 네 판이 같이 쓰는 쓰기 대상 SELECT(잠금 절은 호출자가 붙인다). */
  private SelectConditionStep<Record4<Long, String, String, String>> writeTargetWhere(
      Condition condition) {
    return dsl.select(DS_ID, DS_NAME, DS_TABLE_NAME, DS_STORAGE_TYPE)
        .from(DATASET)
        .where(condition);
  }

  private DatasetWriteTarget toWriteTarget(Record4<Long, String, String, String> r) {
    return new DatasetWriteTarget(r.value1(), r.value2(), r.value3(), r.value4());
  }

  // WD-184: 지식 모델 연결·재연결(DatasetOntologyService.bind)이 잡는 데이터셋 행 잠금. 재연결은 "이전 연결 읽기 → 연결 갱신 → 옛 모델
  // 기준 매핑 삭제"를 한 덩어리로 해야 하는데, 매핑 저장·활성화(MappingService.validate — 이 행을 FOR SHARE 로 잡는다)와 직렬화하지
  // 않으면 옛 모델로 검증한 매핑이 재연결 뒤에 저장돼 고아 매핑이 다시 생긴다.
  // NO KEY UPDATE 인 이유: 매핑 저장의 SHARE 와는 충돌해 줄 서고, 키를 바꾸지 않으므로 FK 참조 삽입·데이터 쓰기(KEY SHARE)는 막지 않는다.
  // 잠금 순서: 데이터셋 행 → 매핑 행(온톨로지 행은 잡지 않는다) — 온톨로지 행 → 데이터셋 행 → 매핑 행 규약의 부분 순서다.
  @Override
  public void lockForOntologyRebind(Long id) {
    dsl.select(DS_ID).from(DATASET).where(DS_ID.eq(id)).forNoKeyUpdate().fetch();
  }

  // WD-186: 매핑 저장·활성화가 필드 목록을 읽기 전에 잡는 공유 잠금 — 진행 중인 필드 변경(lockForColumnChange)이 끝난 뒤의 필드
  // 목록으로 검증하게 한다. 매핑 저장끼리는 서로 막지 않는다(SHARE 는 서로 호환).
  @Override
  public void lockForShare(Long id) {
    dsl.select(DS_ID).from(DATASET).where(DS_ID.eq(id)).forShare().fetch();
  }

  // WD-583: 데이터셋 삭제의 배타 잠금. findById 와 같은 DATASET 테이블 참조를 쓴다.
  // 뒤따르는 동시 삭제는 여기서 앞 트랜잭션 커밋을 기다리고, 커밋 뒤 다시 평가된 WHERE 가
  // 행을 찾지 못해 빈 결과(false)를 받는다(READ COMMITTED).
  // 잠금 순서: 삭제 경로에서 데이터셋 행을 가장 먼저 잡는다 — 필드 변경 경로(데이터셋 행 → 테이블)와 같은 순서.
  @Override
  public boolean lockForDelete(Long id) {
    return dsl.select(DS_ID)
        .from(DATASET)
        .where(DS_ID.eq(id))
        .forUpdate()
        .fetchOptional()
        .isPresent();
  }

  @Override
  public Optional<DatasetResponse> findById(Long id) {
    return findById(id, null);
  }

  @Override
  public Optional<DatasetResponse> findById(Long id, Long currentUserId) {
    Set<Long> favoriteIds = fetchFavoriteIds(currentUserId);
    Map<Long, List<String>> tagMap = fetchTagsByDatasetIds(List.of(id));
    Map<Long, SecurityLevelSummary> levels = fetchLevelSummaries();

    // status_updated_by 사용자 이름을 JOIN으로 조회하여 N+1 제거
    return dsl.select(
            DS_ID,
            DS_NAME,
            DS_TABLE_NAME,
            DS_DESCRIPTION,
            DS_STORAGE_TYPE,
            DS_ORIGIN_TYPE,
            DS_CREATED_AT,
            DS_STATUS,
            DS_STATUS_NOTE,
            DS_STATUS_UPDATED_BY,
            DS_STATUS_UPDATED_AT,
            DS_SOURCE_PIPELINE_STEP_ID,
            DS_SECURITY_LEVEL_ID,
            DS_SECURITY_LEVEL_AUTO_RAISED_AT,
            DC_ID,
            DC_NAME,
            DC_DESCRIPTION,
            SU_NAME)
        .from(DATASET)
        .leftJoin(DATASET_CATEGORY)
        .on(DS_CATEGORY_ID.eq(DC_ID))
        .leftJoin(STATUS_USER_TABLE)
        .on(SU_ID.eq(DS_STATUS_UPDATED_BY))
        .where(DS_ID.eq(id))
        .fetchOptional(r -> mapToDatasetResponse(r, favoriteIds, tagMap, levels));
  }

  @Override
  public DatasetResponse save(CreateDatasetRequest request, Long createdBy) {
    var insert =
        dsl.insertInto(DATASET)
            .set(DS_NAME, request.name())
            .set(DS_TABLE_NAME, request.tableName())
            .set(DS_DESCRIPTION, request.description())
            .set(DS_CATEGORY_ID, request.categoryId())
            .set(DS_STORAGE_TYPE, request.storageType())
            .set(DS_ORIGIN_TYPE, request.originType())
            .set(DS_CREATED_BY, createdBy);
    if (request.sourcePipelineStepId() != null) {
      insert = insert.set(DS_SOURCE_PIPELINE_STEP_ID, request.sourcePipelineStepId());
    }
    return insert
        .returning(
            DS_ID,
            DS_NAME,
            DS_TABLE_NAME,
            DS_DESCRIPTION,
            DS_STORAGE_TYPE,
            DS_ORIGIN_TYPE,
            DS_CREATED_AT,
            DS_STATUS,
            DS_STATUS_NOTE,
            DS_STATUS_UPDATED_BY,
            DS_STATUS_UPDATED_AT,
            DS_SOURCE_PIPELINE_STEP_ID,
            DS_CATEGORY_ID)
        .fetchOne(
            r -> {
              CategoryResponse category =
                  r.get(DS_CATEGORY_ID) != null
                      ? dsl.select(DC_ID, DC_NAME, DC_DESCRIPTION)
                          .from(DATASET_CATEGORY)
                          .where(DC_ID.eq(r.get(DS_CATEGORY_ID)))
                          .fetchOne(
                              cat ->
                                  new CategoryResponse(
                                      cat.get(DC_ID), cat.get(DC_NAME), cat.get(DC_DESCRIPTION)))
                      : null;
              return new DatasetResponse(
                  r.get(DS_ID),
                  r.get(DS_NAME),
                  r.get(DS_TABLE_NAME),
                  r.get(DS_DESCRIPTION),
                  category,
                  r.get(DS_STORAGE_TYPE),
                  r.get(DS_ORIGIN_TYPE),
                  r.get(DS_CREATED_AT),
                  false,
                  List.of(),
                  r.get(DS_STATUS) != null ? r.get(DS_STATUS) : "NONE",
                  r.get(DS_STATUS_NOTE),
                  null,
                  r.get(DS_STATUS_UPDATED_AT),
                  r.get(DS_SOURCE_PIPELINE_STEP_ID));
            });
  }

  @Override
  public Optional<Long> findBySourcePipelineStepId(Long stepId) {
    return dsl.select(DS_ID)
        .from(DATASET)
        .where(DS_SOURCE_PIPELINE_STEP_ID.eq(stepId))
        .limit(1)
        .fetchOptional(r -> r.get(DS_ID));
  }

  // WD-575: 정보 수정의 편집 기준 버전 비교용 행 잠금.
  // WD-666: FOR NO KEY UPDATE — 키를 바꾸지 않으니 충분하고, 데이터 쓰기(KEY SHARE)를 기다리지 않는다
  // (정보 수정끼리는 여전히 줄 선다).
  // 이 잠금은 트랜잭션 끝까지 유지돼 같은 데이터셋의 동시 정보 수정이 한 줄로 서고, 나중 요청은 앞 저장이 바꾼 수정 시각을 읽는다.
  @Override
  public Optional<LockedRowVersion> lockUpdatedAtById(Long id) {
    return dsl.select(DS_UPDATED_AT)
        .from(DATASET)
        .where(DS_ID.eq(id))
        .forNoKeyUpdate()
        .fetchOptional()
        .map(r -> new LockedRowVersion(r.value1()));
  }

  @Override
  public void update(Long id, UpdateDatasetRequest request, Long updatedBy) {
    dsl.update(DATASET)
        .set(DS_NAME, request.name())
        .set(DS_DESCRIPTION, request.description())
        .set(DS_CATEGORY_ID, request.categoryId())
        .set(DS_UPDATED_AT, LocalDateTime.now())
        .set(DS_UPDATED_BY, updatedBy)
        .where(DS_ID.eq(id))
        .execute();
  }

  @Override
  public void updateStatus(Long id, String status, String statusNote, Long statusUpdatedBy) {
    dsl.update(DATASET)
        .set(DS_STATUS, status)
        .set(DS_STATUS_NOTE, statusNote)
        .set(DS_STATUS_UPDATED_BY, statusUpdatedBy)
        .set(DS_STATUS_UPDATED_AT, LocalDateTime.now())
        .where(DS_ID.eq(id))
        .execute();
  }

  @Override
  public int deleteById(Long id) {
    // 영향 행 수를 돌려준다 — 0 이면 이미 지워진 데이터셋이라 호출부가 404 로 끝낸다(WD-583).
    return dsl.deleteFrom(DATASET).where(DS_ID.eq(id)).execute();
  }

  /**
   * 조회자가 볼 수 있는 데이터셋 가운데 같은 이름이 있는가(WD-155). 이름 유일 범위는 「볼 수 있는 데이터셋」이다 — 볼 수 없는(상위 등급) 데이터셋과 겹치는지까지
   * 보면 이름을 맞혀 보는 것만으로 존재가 드러난다. DB 유일 제약도 없다(V142).
   *
   * @param visible 조회자 가시성({@code DatasetAccessGuard.visibility})
   * @param excludeId 이름 변경 시 자기 자신(없으면 null)
   */
  @Override
  public boolean existsVisibleByName(String name, DatasetVisibility visible, Long excludeId) {
    Condition c = DS_NAME.eq(name).and(JooqDatasetVisibility.condition(visible));
    if (excludeId != null) {
      c = c.and(DS_ID.ne(excludeId));
    }
    return dsl.fetchExists(dsl.selectOne().from(DATASET).where(c));
  }

  /**
   * 이름 검사 직렬화 잠금(WD-540) — 키·이유는 {@link DatasetRepository#acquireNameLock} 참고. MANDATORY 라 트랜잭션 밖
   * 호출은 예외로 드러난다 — 클래스 기본(REQUIRED)이면 이 호출만의 트랜잭션이 열렸다 바로 닫혀 잠금이 아무것도 막지 못한다.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  @Override
  public void acquireNameLock(long tenantId, String name) {
    locks.xactLock("dataset-name:" + tenantId + ":" + name);
  }

  /**
   * 테이블명 검사 직렬화 잠금(WD-540) — 키·이유는 {@link DatasetRepository#acquireTableNameLock} 참고. MANDATORY 인
   * 이유는 위와 같다.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  @Override
  public void acquireTableNameLock(long tenantId, String tableName) {
    locks.xactLock("dataset-table:" + tenantId + ":" + tableName);
  }

  @Override
  public boolean existsByTableName(String tableName) {
    return dsl.fetchExists(dsl.selectOne().from(DATASET).where(DS_TABLE_NAME.eq(tableName)));
  }

  @Override
  public Optional<String> findTableNameById(Long id) {
    return dsl.select(DS_TABLE_NAME)
        .from(DATASET)
        .where(DS_ID.eq(id))
        .fetchOptional(r -> r.get(DS_TABLE_NAME));
  }

  @Override
  public Optional<Long> findCreatedByById(Long id) {
    return dsl.select(DS_CREATED_BY)
        .from(DATASET)
        .where(DS_ID.eq(id))
        .fetchOptional(r -> r.get(DS_CREATED_BY));
  }

  @Override
  public Optional<LocalDateTime> findUpdatedAtById(Long id) {
    return dsl.select(DS_UPDATED_AT)
        .from(DATASET)
        .where(DS_ID.eq(id))
        .fetchOptional(r -> r.get(DS_UPDATED_AT));
  }

  @Override
  public Optional<Long> findUpdatedByById(Long id) {
    return dsl.select(DS_UPDATED_BY)
        .from(DATASET)
        .where(DS_ID.eq(id))
        .fetchOptional(r -> r.get(DS_UPDATED_BY));
  }

  /**
   * 열람 가능한 데이터셋 메타를 한 번의 조회로 읽는다 — {@code dataset d LEFT JOIN dataset_column dc}. 가시성 조건은 호출자가 만든
   * SQL 조각이라 {@code d} 별칭 plain SQL 이다. 컬럼은 데이터셋 안에서 {@code column_order} 순으로 담고(예전 Tibero 경로의 정렬과
   * 같다), 컬럼 없는 데이터셋은 LEFT JOIN 이라 빈 컬럼 목록으로 남는다.
   *
   * <p>왜 한 번인가(최종 리뷰 지적 5): 예전에는 데이터셋 행을 읽은 뒤 그 id 전부를 컬럼 조회의 {@code IN} 목록으로 묶었다. 열람 가능한 데이터셋 수만큼
   * 바인드 값이 늘어 PG 바인드 상한(32767)에 닿을 수 있고 왕복도 한 번 더였다. 조인하면 id 목록을 넘길 필요가 없다.
   */
  @Override
  public List<DatasetSchemaMeta> findSchemaMetas(List<Long> datasetIds, String visibilitySql) {
    StringBuilder sql =
        new StringBuilder(
                "SELECT d.id, d.name, d.table_name, d.storage_type,"
                    + " dc.column_name, dc.data_type, dc.display_name"
                    + " FROM dataset d LEFT JOIN dataset_column dc ON dc.dataset_id = d.id WHERE ")
            .append(visibilitySql)
            .append(" ");
    if (datasetIds != null) {
      // datasetIds 는 컨트롤러에서 Long 으로 바인딩된 값이라 SQL injection 위험 없음. 호출자가 고른 목록이라 크기도 요청이 정한다.
      sql.append("AND d.id IN (")
          .append(datasetIds.stream().map(String::valueOf).collect(Collectors.joining(",")))
          .append(") ");
    }
    sql.append("ORDER BY d.table_name, d.id, dc.column_order");

    // 행은 데이터셋별로 붙어 오므로(ORDER BY d.table_name, d.id) 순서를 지키는 맵에 모은다.
    Map<Long, DatasetSchemaMeta> byId = new LinkedHashMap<>();
    for (var r : dsl.fetch(sql.toString())) {
      long id = r.get(0, Long.class);
      DatasetSchemaMeta meta =
          byId.computeIfAbsent(
              id,
              k ->
                  new DatasetSchemaMeta(
                      k,
                      r.get(1, String.class),
                      r.get(2, String.class),
                      r.get(3, String.class),
                      new ArrayList<>()));
      String columnName = r.get(4, String.class);
      if (columnName != null) {
        // LEFT JOIN 의 빈 쪽(컬럼 없는 데이터셋)은 column_name 이 null 이다 — 컬럼으로 담지 않는다.
        meta.columns()
            .add(
                new DatasetSchemaMeta.Column(
                    columnName, r.get(5, String.class), r.get(6, String.class)));
      }
    }
    return new ArrayList<>(byId.values());
  }

  @Override
  public List<DatasetTableLevel> findTableLevels(String onlyTable) {
    return dsl.select(DS_TABLE_NAME, DS_SECURITY_LEVEL_ID)
        .from(DATASET)
        .where(onlyTable == null ? DS_TABLE_NAME.isNotNull() : DS_TABLE_NAME.eq(onlyTable))
        .fetch(r -> new DatasetTableLevel(r.value1(), r.value2()));
  }
}
