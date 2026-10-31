package com.smartfirehub.analytics.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.SavedQueryListResponse;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.analytics.dto.UpdateSavedQueryRequest;
import com.smartfirehub.global.util.LikePatternUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SavedQueryRepository {

  private final DSLContext dsl;

  // saved_query table fields
  private static final Table<?> SQ = table(name("saved_query"));
  private static final Field<Long> SQ_ID = field(name("saved_query", "id"), Long.class);
  private static final Field<String> SQ_NAME = field(name("saved_query", "name"), String.class);
  private static final Field<String> SQ_DESCRIPTION =
      field(name("saved_query", "description"), String.class);
  private static final Field<String> SQ_SQL_TEXT =
      field(name("saved_query", "sql_text"), String.class);
  private static final Field<Long> SQ_DATASET_ID =
      field(name("saved_query", "dataset_id"), Long.class);
  private static final Field<String> SQ_FOLDER = field(name("saved_query", "folder"), String.class);
  private static final Field<Boolean> SQ_IS_SHARED =
      field(name("saved_query", "is_shared"), Boolean.class);
  private static final Field<Long> SQ_CREATED_BY =
      field(name("saved_query", "created_by"), Long.class);
  private static final Field<Long> SQ_UPDATED_BY =
      field(name("saved_query", "updated_by"), Long.class);
  private static final Field<LocalDateTime> SQ_CREATED_AT =
      field(name("saved_query", "created_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> SQ_UPDATED_AT =
      field(name("saved_query", "updated_at"), LocalDateTime.class);

  // dataset table
  private static final Table<?> DS = table(name("dataset"));
  private static final Field<Long> DS_ID = field(name("dataset", "id"), Long.class);
  private static final Field<String> DS_NAME = field(name("dataset", "name"), String.class);

  // user table
  private static final Table<?> USER_TABLE = table(name("user"));
  private static final Field<Long> U_ID = field(name("user", "id"), Long.class);
  private static final Field<String> U_NAME = field(name("user", "name"), String.class);

  // chart table (for count sub-query)
  private static final Table<?> CHART = table(name("chart"));
  private static final Field<Long> CHART_SAVED_QUERY_ID =
      field(name("chart", "saved_query_id"), Long.class);

  // aliased fields for result mapping
  private static final Field<String> DS_NAME_ALIAS =
      field(name("dataset", "name"), String.class).as("dataset_name");
  private static final Field<String> U_NAME_ALIAS =
      field(name("user", "name"), String.class).as("created_by_name");

  /** 조회자에게 보이는 쿼리 — 본인 소유이거나 공유된 것. 차트·대시보드 저장소의 visibleTo 와 같은 규칙. */
  private static Condition visibleTo(Long userId) {
    return SQ_CREATED_BY.eq(userId).or(SQ_IS_SHARED.isTrue());
  }

  /**
   * 목록·건수·폴더 목록이 함께 쓰는 소유/공유 범위 조건 (WD-328). 셋이 어긋나지 않도록 한 곳에 둔다.
   *
   * <ul>
   *   <li>mineOnly=true — 내가 만든 쿼리만. 「내 쿼리」 탭에 남의 공유 쿼리가 섞이지 않게 한다
   *   <li>sharedOnly=true — 공유된 쿼리만(내 공유 쿼리 포함). 「공유됨」 탭
   *   <li>둘 다 true — 별도 의미를 만들지 않고 두 조건을 AND 로 결합(내가 만든 공유 쿼리)
   *   <li>둘 다 없음 — 기존 {@link #visibleTo} 그대로(내 쿼리 + 공유된 쿼리). AI 에이전트 MCP 도구 등 다른 호출자 호환
   * </ul>
   */
  private static Condition listScope(Boolean sharedOnly, Boolean mineOnly, Long userId) {
    boolean shared = Boolean.TRUE.equals(sharedOnly);
    boolean mine = Boolean.TRUE.equals(mineOnly);
    if (!shared && !mine) {
      return visibleTo(userId);
    }
    Condition scope = trueCondition();
    if (shared) {
      scope = scope.and(SQ_IS_SHARED.isTrue());
    }
    if (mine) {
      scope = scope.and(SQ_CREATED_BY.eq(userId));
    }
    return scope;
  }

  /**
   * 목록.
   *
   * @param datasetNameVisible 연결 데이터셋 LEFT JOIN 의 ON 에 더하는 가시성 조건({@code "dataset"} 이름 관례) — 조회자가 볼
   *     수 없는 데이터셋은 이름만 null 이 되고 datasetId 는 유지된다(보안 등급, 스펙 §2.5). 웹은 id 있음+이름 null 을 "열람 권한 없음" 으로
   *     그린다
   */
  public List<SavedQueryListResponse> findAll(
      String search,
      String folder,
      Boolean sharedOnly,
      Boolean mineOnly,
      Long userId,
      int page,
      int size,
      Condition datasetNameVisible) {

    var chartCountField =
        dsl.selectCount().from(CHART).where(CHART_SAVED_QUERY_ID.eq(SQ_ID)).asField("chart_count");

    List<Condition> conditions = new ArrayList<>();

    conditions.add(listScope(sharedOnly, mineOnly, userId));

    if (search != null && !search.isBlank()) {
      String pattern = LikePatternUtils.containsPattern(search);
      conditions.add(
          SQ_NAME.likeIgnoreCase(pattern, '\\').or(SQ_DESCRIPTION.likeIgnoreCase(pattern, '\\')));
    }

    if (folder != null && !folder.isBlank()) {
      conditions.add(SQ_FOLDER.eq(folder));
    }

    Condition combined = conditions.stream().reduce(Condition::and).orElse(trueCondition());

    var records =
        dsl.select(
                SQ_ID,
                SQ_NAME,
                SQ_DESCRIPTION,
                SQ_FOLDER,
                SQ_DATASET_ID,
                DS_NAME_ALIAS,
                SQ_IS_SHARED,
                U_NAME_ALIAS,
                SQ_CREATED_BY,
                SQ_CREATED_AT,
                SQ_UPDATED_AT,
                chartCountField)
            .from(SQ)
            .leftJoin(DS)
            .on(SQ_DATASET_ID.eq(DS_ID).and(datasetNameVisible))
            .join(USER_TABLE)
            .on(SQ_CREATED_BY.eq(U_ID))
            .where(combined)
            .orderBy(SQ_UPDATED_AT.desc())
            .limit(size)
            .offset(page * size)
            .fetch();

    List<SavedQueryListResponse> result = new ArrayList<>();
    for (Record r : records) {
      result.add(
          new SavedQueryListResponse(
              r.get(SQ_ID),
              r.get(SQ_NAME),
              r.get(SQ_DESCRIPTION),
              r.get(SQ_FOLDER),
              r.get(SQ_DATASET_ID),
              r.get("dataset_name", String.class),
              Boolean.TRUE.equals(r.get(SQ_IS_SHARED)),
              r.get("created_by_name", String.class),
              r.get(SQ_CREATED_BY),
              r.get(SQ_CREATED_AT),
              r.get(SQ_UPDATED_AT),
              r.get("chart_count", Long.class)));
    }
    return result;
  }

  public long countAll(
      String search, String folder, Boolean sharedOnly, Boolean mineOnly, Long userId) {
    List<Condition> conditions = new ArrayList<>();

    conditions.add(listScope(sharedOnly, mineOnly, userId));

    if (search != null && !search.isBlank()) {
      String pattern = LikePatternUtils.containsPattern(search);
      conditions.add(
          SQ_NAME.likeIgnoreCase(pattern, '\\').or(SQ_DESCRIPTION.likeIgnoreCase(pattern, '\\')));
    }

    if (folder != null && !folder.isBlank()) {
      conditions.add(SQ_FOLDER.eq(folder));
    }

    Condition combined = conditions.stream().reduce(Condition::and).orElse(trueCondition());

    return dsl.selectCount().from(SQ).where(combined).fetchOne(0, Long.class);
  }

  /** 단건(소유자 또는 공유). {@code datasetNameVisible} 은 {@link #findAll} 과 같다. */
  public Optional<SavedQueryResponse> findById(Long id, Long userId, Condition datasetNameVisible) {
    var chartCountField =
        dsl.selectCount().from(CHART).where(CHART_SAVED_QUERY_ID.eq(SQ_ID)).asField("chart_count");

    Record r =
        dsl.select(
                SQ_ID,
                SQ_NAME,
                SQ_DESCRIPTION,
                SQ_SQL_TEXT,
                SQ_DATASET_ID,
                DS_NAME_ALIAS,
                SQ_FOLDER,
                SQ_IS_SHARED,
                U_NAME_ALIAS,
                SQ_CREATED_BY,
                SQ_CREATED_AT,
                SQ_UPDATED_AT,
                chartCountField)
            .from(SQ)
            .leftJoin(DS)
            .on(SQ_DATASET_ID.eq(DS_ID).and(datasetNameVisible))
            .join(USER_TABLE)
            .on(SQ_CREATED_BY.eq(U_ID))
            .where(SQ_ID.eq(id).and(SQ_CREATED_BY.eq(userId).or(SQ_IS_SHARED.isTrue())))
            .fetchOne();

    if (r == null) return Optional.empty();
    return Optional.of(mapToResponse(r));
  }

  /** 단건(소유자만). {@code datasetNameVisible} 은 {@link #findAll} 과 같다. */
  public Optional<SavedQueryResponse> findByIdForOwner(
      Long id, Long userId, Condition datasetNameVisible) {
    var chartCountField =
        dsl.selectCount().from(CHART).where(CHART_SAVED_QUERY_ID.eq(SQ_ID)).asField("chart_count");

    Record r =
        dsl.select(
                SQ_ID,
                SQ_NAME,
                SQ_DESCRIPTION,
                SQ_SQL_TEXT,
                SQ_DATASET_ID,
                DS_NAME_ALIAS,
                SQ_FOLDER,
                SQ_IS_SHARED,
                U_NAME_ALIAS,
                SQ_CREATED_BY,
                SQ_CREATED_AT,
                SQ_UPDATED_AT,
                chartCountField)
            .from(SQ)
            .leftJoin(DS)
            .on(SQ_DATASET_ID.eq(DS_ID).and(datasetNameVisible))
            .join(USER_TABLE)
            .on(SQ_CREATED_BY.eq(U_ID))
            .where(SQ_ID.eq(id).and(SQ_CREATED_BY.eq(userId)))
            .fetchOne();

    if (r == null) return Optional.empty();
    return Optional.of(mapToResponse(r));
  }

  /**
   * id로 레코드를 소유권 제한 없이 조회한다 (clone 등 내부 처리용).
   *
   * <p>이전에 존재하던 {@code findRawById(Long id)} 메서드는 WHERE 절에 {@code SQ_CREATED_BY.eq(SQ_CREATED_BY)}
   * — 컬럼을 자기 자신과 비교하는 tautology 조건이 포함되어 있었다. 해당 메서드는 호출부가 없는 dead code였으며, 실제 소유권 필터링이 필요한 경우라면
   * {@link #findByIdForOwner(Long, Long)}를 사용해야 한다. 소유권 무관 조회는 이 메서드({@code
   * findRawByIdUnrestricted})가 올바른 구현이다.
   */
  public Optional<Record> findRawByIdUnrestricted(Long id) {
    Record r =
        dsl.select(
                SQ_ID,
                SQ_NAME,
                SQ_DESCRIPTION,
                SQ_SQL_TEXT,
                SQ_DATASET_ID,
                SQ_FOLDER,
                SQ_IS_SHARED,
                SQ_CREATED_BY)
            .from(SQ)
            .where(SQ_ID.eq(id))
            .fetchOne();
    return Optional.ofNullable(r);
  }

  /**
   * 주어진 저장 쿼리 중 {@code ownerId} 가 만든 것만 공유로 바꾼다(WD-194).
   *
   * <p>대시보드 공유 시 위젯 차트가 쓰는 쿼리를 함께 공유하는 용도다. 차트 주인이 아니라 <b>쿼리 주인</b>으로 거른다 — 내 차트가 남의 쿼리(만들 때는 공유였다가
   * 주인이 비공개로 되돌린 것)를 쓰는 경우에도 그 쿼리는 주인만 공유할 수 있어야 하기 때문이다.
   */
  public Map<Long, String> shareQueries(List<Long> queryIds, Long ownerId) {
    // 이미 공유된 쿼리는 건드리지 않고, 이번에 실제로 공유로 바뀐 쿼리의 id → 이름을 돌려준다 — 호출자가 공유 켜기 감사를 남긴다(WD-464).
    if (queryIds.isEmpty()) return Map.of();
    Map<Long, String> flipped = new TreeMap<>();
    dsl.update(SQ)
        .set(SQ_IS_SHARED, true)
        .where(SQ_ID.in(queryIds).and(SQ_CREATED_BY.eq(ownerId)).and(SQ_IS_SHARED.isFalse()))
        .returning(SQ_ID, SQ_NAME)
        .fetch()
        .forEach(r -> flipped.put(r.get(SQ_ID), r.get(SQ_NAME)));
    return flipped;
  }

  /**
   * 주어진 저장 쿼리 중 {@code ownerId} 가 만든 것을 공유 여부별로 센다(WD-325) — [비공개 수, 공유 수]. {@link #shareQueries} 와
   * 같은 대상(작성자 = 요청자)이다.
   */
  public int[] countOwnedBySharing(List<Long> queryIds, Long ownerId) {
    if (queryIds.isEmpty()) return new int[] {0, 0};
    int[] counts = {0, 0};
    dsl.select(SQ_IS_SHARED, count())
        .from(SQ)
        .where(SQ_ID.in(queryIds).and(SQ_CREATED_BY.eq(ownerId)))
        .groupBy(SQ_IS_SHARED)
        .fetch()
        .forEach(r -> counts[Boolean.TRUE.equals(r.value1()) ? 1 : 0] = r.value2());
    return counts;
  }

  public Long insert(CreateSavedQueryRequest req, Long userId) {
    return dsl.insertInto(SQ)
        .set(SQ_NAME, req.name())
        .set(SQ_DESCRIPTION, req.description())
        .set(SQ_SQL_TEXT, req.sqlText())
        .set(SQ_DATASET_ID, req.datasetId())
        .set(SQ_FOLDER, req.folder())
        .set(SQ_IS_SHARED, req.isShared())
        .set(SQ_CREATED_BY, userId)
        .returning(SQ_ID)
        .fetchOne()
        .get(SQ_ID);
  }

  /**
   * 수정 시각을 행 잠금(FOR UPDATE)과 함께 읽는다 — 편집 충돌(낙관적 잠금) 비교용(WD-364, 파이프라인 WD-353 과 같은 방식).
   *
   * <p>잠금 없이 읽고 비교하면 동시에 들어온 두 저장이 같은 값을 읽어 둘 다 통과한다. 잠금을 잡으면 나중 저장은 앞 저장이 커밋될 때까지 기다렸다가 바뀐 값을 읽어
   * 충돌로 거부된다. 소유자 확인을 마친 뒤에 부른다 — 먼저 부르면 남의 행에 대한 낡은 요청이 404 대신 409 를 받아 존재가 드러난다.
   */
  public Optional<Record1<LocalDateTime>> lockUpdatedAtById(Long id) {
    return dsl.select(SQ_UPDATED_AT).from(SQ).where(SQ_ID.eq(id)).forUpdate().fetchOptional();
  }

  public void update(Long id, UpdateSavedQueryRequest req, Long userId) {
    var update = dsl.update(SQ).set(SQ_UPDATED_BY, userId).set(SQ_UPDATED_AT, LocalDateTime.now());

    if (req.name() != null) update = update.set(SQ_NAME, req.name());
    // 설명: 생략(null)은 "변경 없음", 빈 문자열(공백만 포함)은 "설명을 지움"으로 해석해 DB 값을 NULL로 비운다(WD-197).
    // 웹이 지운 설명을 빈 문자열로 보내도 이전에는 null과 같이 무시돼 옛 설명이 그대로 남았다.
    if (req.description() != null)
      update = update.set(SQ_DESCRIPTION, blankToNull(req.description()));
    if (req.sqlText() != null) update = update.set(SQ_SQL_TEXT, req.sqlText());
    if (req.datasetId() != null) update = update.set(SQ_DATASET_ID, req.datasetId());
    // 폴더: 생략(null)은 "변경 없음", 빈 문자열은 "폴더 없음으로 옮김"으로 해석해 NULL로 비운다(WD-197).
    // 폴더는 이름 문자열이라 설명과 같은 신호(빈 문자열)를 쓰면 별도 플래그 없이 의도를 구분할 수 있다.
    if (req.folder() != null) update = update.set(SQ_FOLDER, blankToNull(req.folder()));
    if (req.isShared() != null) update = update.set(SQ_IS_SHARED, req.isShared());

    update.where(SQ_ID.eq(id)).execute();
  }

  public boolean deleteById(Long id, Long userId) {
    int deleted = dsl.deleteFrom(SQ).where(SQ_ID.eq(id).and(SQ_CREATED_BY.eq(userId))).execute();
    return deleted > 0;
  }

  /**
   * 폴더 필터 드롭다운용 폴더 이름 목록. 목록과 같은 {@link #listScope} 로 범위를 맞춘다(WD-328) — 「내 쿼리」 탭에서 남의 공유 쿼리에만 있는
   * 폴더를 고르면 결과가 늘 비어 버리기 때문이다. 플래그가 없으면 기존처럼 내 쿼리 + 공유 쿼리의 폴더.
   */
  public List<String> findDistinctFolders(Boolean sharedOnly, Boolean mineOnly, Long userId) {
    return dsl.selectDistinct(SQ_FOLDER)
        .from(SQ)
        .where(SQ_FOLDER.isNotNull().and(listScope(sharedOnly, mineOnly, userId)))
        .orderBy(SQ_FOLDER.asc())
        .fetch(SQ_FOLDER);
  }

  /** Count charts referencing a query created by a user OTHER than the owner. */
  public long countOtherUserCharts(Long savedQueryId, Long ownerId) {
    Table<?> C = table(name("chart"));
    Field<Long> C_SAVED_QUERY_ID = field(name("chart", "saved_query_id"), Long.class);
    Field<Long> C_CREATED_BY = field(name("chart", "created_by"), Long.class);
    return dsl.selectCount()
        .from(C)
        .where(C_SAVED_QUERY_ID.eq(savedQueryId).and(C_CREATED_BY.ne(ownerId)))
        .fetchOne(0, Long.class);
  }

  private SavedQueryResponse mapToResponse(Record r) {
    return new SavedQueryResponse(
        r.get(SQ_ID),
        r.get(SQ_NAME),
        r.get(SQ_DESCRIPTION),
        r.get(SQ_SQL_TEXT),
        r.get(SQ_DATASET_ID),
        r.get("dataset_name", String.class),
        r.get(SQ_FOLDER),
        Boolean.TRUE.equals(r.get(SQ_IS_SHARED)),
        r.get("created_by_name", String.class),
        r.get(SQ_CREATED_BY),
        r.get(SQ_CREATED_AT),
        r.get(SQ_UPDATED_AT),
        r.get("chart_count", Long.class));
  }

  /**
   * 갱신 요청의 빈 문자열(공백만 포함)을 NULL 로 바꾼다(WD-197).
   *
   * <p>갱신 API 는 null 을 "변경 없음"으로 쓰므로, 사용자가 값을 지웠다는 뜻은 빈 문자열로 받는다. 저장할 때는 "값 없음"을 NULL 한 가지로 맞춰
   * 목록·상세가 "" 와 NULL 을 따로 다루지 않게 한다.
   */
  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
