package com.smartfirehub.analytics.service;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.DeleteImpactResponse;
import com.smartfirehub.analytics.dto.SavedQueryListResponse;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.analytics.dto.UpdateSavedQueryRequest;
import com.smartfirehub.analytics.exception.SavedQueryNotFoundException;
import com.smartfirehub.analytics.repository.AnalyticsDashboardRepository;
import com.smartfirehub.analytics.repository.ChartRepository;
import com.smartfirehub.analytics.repository.SavedQueryRepository;
import com.smartfirehub.global.config.TransactionTemplateConfig;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class SavedQueryService {

  private final SavedQueryRepository savedQueryRepository;
  private final GuardedSqlExecutor guardedSqlExecutor;
  private final ClearanceResolver clearanceResolver;
  private final DatasetAccessGuard datasetAccessGuard;

  /** 사용자 실행 결과에 실행 기록(runId)을 붙인다(애드혹 실행과 같은 규칙). */
  private final QueryResultExportService queryResultExportService;

  /** 삭제 영향 범위(WD-86) — 쿼리를 지우면 CASCADE 로 함께 사라지는 차트·위젯을 센다. */
  private final ChartRepository chartRepository;

  private final AnalyticsDashboardRepository dashboardRepository;

  /** 실행 구간을 코드로 나누는 트랜잭션 템플릿(쓰기 기본값) — Tibero 실행을 PG 트랜잭션 밖에 두려고 쓴다(WD-207). */
  private final TransactionTemplate tx;

  /** 테넌트 저장소 판정 — Tibero 인지에 따라 실행 구간을 나눈다. */
  private final PipelineDataSchema pipelineDataSchema;

  /**
   * Tibero 실행 전 저장 쿼리 조회 구간의 readOnly 트랜잭션 템플릿(WD-207) — 공용 빈({@link
   * TransactionTemplateConfig#READ_ONLY})을 주입받는다(ChartService·AnalyticsDashboardService 와 같은 방식).
   */
  @Qualifier(TransactionTemplateConfig.READ_ONLY)
  private final TransactionTemplate readOnlyTx;

  /**
   * 응답의 연결 데이터셋 이름 가시성(보안 등급, 스펙 §2.5) — 조회자가 볼 수 없는 데이터셋은 이름만 null, datasetId 는 유지(웹 편집기가 PUT 으로
   * 되돌려 보내는 참조를 지우지 않게).
   */
  private Condition datasetNameVisible(Long userId) {
    return datasetNameVisible(clearanceResolver.resolve(userId));
  }

  /** 이미 계산한 자격으로 — 한 메서드에서 판정과 이름 가시성에 같은 자격을 쓸 때 DB 왕복을 한 번으로(리뷰 M3). */
  private Condition datasetNameVisible(Clearance viewer) {
    return datasetAccessGuard.visibleCondition(viewer);
  }

  /** List saved queries with optional filters and pagination. */
  // RLS 가 걸린 saved_query 를 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  @Transactional(readOnly = true)
  public PageResponse<SavedQueryListResponse> list(
      String search, String folder, Boolean sharedOnly, Long userId, int page, int size) {
    List<SavedQueryListResponse> content =
        savedQueryRepository.findAll(
            search, folder, sharedOnly, userId, page, size, datasetNameVisible(userId));
    long total = savedQueryRepository.countAll(search, folder, sharedOnly, userId);
    int totalPages = (int) Math.ceil((double) total / size);
    return new PageResponse<>(content, page, size, total, totalPages);
  }

  /** Create a new saved query. */
  @Transactional
  public SavedQueryResponse create(CreateSavedQueryRequest req, Long userId) {
    // 연결 데이터셋은 생성자가 볼 수 있어야 한다(보안 등급) — 필터 없는 존재 확인이면 숨김 id 는 201·이름 노출, 없는 id 는 404 로 갈려 이름
    // 확인 경로가 됐다. requireView 는 숨김·없음을 바이트 단위로 같은 404 로 낸다.
    Clearance viewer = clearanceResolver.resolve(userId);
    if (req.datasetId() != null) {
      datasetAccessGuard.requireView(viewer, req.datasetId());
    }
    Long id = savedQueryRepository.insert(req, userId);
    return savedQueryRepository
        .findById(id, userId, datasetNameVisible(viewer))
        .orElseThrow(() -> SavedQueryNotFoundException.reloadFailed());
  }

  /** Get a single saved query — owner or any shared query. */
  // RLS 가 걸린 saved_query 를 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  // 참고(자기호출): executeById 가 이 메서드를 this. 로 직접 호출하지만, 항상 TransactionTemplate 이 연
  // 트랜잭션 안(PG 는 쓰기, Tibero 는 readOnly)에서만 부르므로 프록시를 안 타도 무해하다.
  @Transactional(readOnly = true)
  public SavedQueryResponse getById(Long id, Long userId) {
    return savedQueryRepository
        .findById(id, userId, datasetNameVisible(userId))
        .orElseThrow(() -> new SavedQueryNotFoundException());
  }

  /**
   * Update a saved query. Only the owner can update. If the query is shared and other users' charts
   * reference it, sqlText cannot be changed.
   */
  @Transactional
  public SavedQueryResponse update(Long id, UpdateSavedQueryRequest req, Long userId) {
    Clearance viewer = clearanceResolver.resolve(userId);
    SavedQueryResponse existing =
        savedQueryRepository
            .findByIdForOwner(id, userId, datasetNameVisible(viewer))
            .orElseThrow(() -> new SavedQueryNotFoundException());

    // 연결 데이터셋을 바꾸는 경우만 판정한다(create 와 같은 404) — 웹 편집기가 기존 값(편집자가 자격을 잃은 숨김 id 포함)을 그대로 되돌려
    // 보내는 저장은 막지 않는다(왕복 보존).
    if (req.datasetId() != null && !Objects.equals(req.datasetId(), existing.datasetId())) {
      datasetAccessGuard.requireView(viewer, req.datasetId());
    }

    // Protect shared query SQL if other users' charts reference it
    if (req.sqlText() != null && !req.sqlText().equals(existing.sqlText()) && existing.isShared()) {
      long otherChartCount = savedQueryRepository.countOtherUserCharts(id, userId);
      if (otherChartCount > 0) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "공유 쿼리의 SQL은 수정할 수 없습니다. '복제' 후 수정하세요.");
      }
    }

    savedQueryRepository.update(id, req, userId);
    return savedQueryRepository
        .findByIdForOwner(id, userId, datasetNameVisible(viewer))
        .orElseThrow(() -> new SavedQueryNotFoundException());
  }

  /** Delete a saved query (owner only). CASCADE removes linked charts and widgets. */
  @Transactional
  public void delete(Long id, Long userId) {
    // Verify ownership first — 소유 확인만 하고 응답을 쓰지 않으므로 이름 가시성 조건은 필요 없다.
    savedQueryRepository
        .findByIdForOwner(id, userId, DSL.trueCondition())
        .orElseThrow(() -> new SavedQueryNotFoundException());
    boolean deleted = savedQueryRepository.deleteById(id, userId);
    if (!deleted) {
      throw new SavedQueryNotFoundException();
    }
  }

  /**
   * 저장 쿼리 삭제 시 함께 사라지는 차트·대시보드 위젯(WD-86). 삭제 확인 창이 "함께 삭제되는 항목"을 보여 주는 데 쓴다.
   *
   * <p>삭제와 같은 관문(소유자만, 남의 것·없는 것은 같은 404)을 쓴다 — 지울 수 없는 쿼리의 영향 범위는 의미가 없고, 소유자가 아닌 사람에게 "볼 수 없는 항목
   * n개"를 알려 주면 남의 자산 존재가 새어 나간다. 개수는 전부, 이름은 볼 수 있는 것만({@link DeleteImpactResponse}).
   */
  // RLS 가 걸린 chart/dashboard/dashboard_widget 을 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 되어
  // "연결 없음"(일반 확인 창)으로 위장된다.
  @Transactional(readOnly = true)
  public DeleteImpactResponse getDeleteImpact(Long id, Long userId) {
    savedQueryRepository
        .findByIdForOwner(id, userId, DSL.trueCondition())
        .orElseThrow(() -> new SavedQueryNotFoundException());
    List<DeleteImpactResponse.ChartRawRef> charts =
        chartRepository.findImpactRefsBySavedQueryId(id, userId);
    List<Long> chartIds = charts.stream().map(DeleteImpactResponse.ChartRawRef::id).toList();
    return DeleteImpactResponse.forQuery(
        charts, dashboardRepository.findImpactByChartIds(chartIds, userId));
  }

  /**
   * Clone a saved query. The clone is private (is_shared=false) and belongs to the requesting user.
   */
  @Transactional
  public SavedQueryResponse clone(Long id, Long userId) {
    var raw =
        savedQueryRepository
            .findRawByIdUnrestricted(id)
            .orElseThrow(() -> new SavedQueryNotFoundException());

    // Verify access: owner or shared
    boolean isShared = Boolean.TRUE.equals(raw.get("is_shared", Boolean.class));
    Long ownerId = raw.get("created_by", Long.class);
    if (!isShared && !ownerId.equals(userId)) {
      throw new SavedQueryNotFoundException();
    }

    String originalName = raw.get("name", String.class);
    CreateSavedQueryRequest cloneReq =
        new CreateSavedQueryRequest(
            originalName + " (복사본)",
            raw.get("description", String.class),
            raw.get("sql_text", String.class),
            raw.get("dataset_id", Long.class),
            raw.get("folder", String.class),
            false);

    Long newId = savedQueryRepository.insert(cloneReq, userId);
    return savedQueryRepository
        .findById(newId, userId, datasetNameVisible(userId))
        .orElseThrow(() -> SavedQueryNotFoundException.cloneReloadFailed());
  }

  /**
   * Execute a saved query by ID. 성공한 사용자 SELECT 는 애드혹 실행과 같은 규칙으로 실행 기록(runId)을 남긴다 — 저장 쿼리 결과도 서버
   * 재실행 내보내기를 할 수 있게(code-review 4). 기록은 실행 시점 SQL 스냅샷·실행자 소유다.
   */
  public AnalyticsQueryResponse executeById(Long id, int maxRows, boolean readOnly, Long userId) {
    if (!pipelineDataSchema.isTibero()) {
      // PG 테넌트: 기존과 같이 조회·실행·기록 부착 전체를 쓰기 트랜잭션 하나로 실행한다.
      return tx.execute(status -> runSavedQuery(getById(id, userId), maxRows, readOnly, userId));
    }
    // Tibero 실행(최대 35초) 동안 PG 연결을 쥐지 않으려고 구간을 나눈다(WD-207).
    // 1) RLS GUC 가 필요한 저장 쿼리 조회만 readOnly 트랜잭션으로, 2) 실행·기록 부착은 트랜잭션 밖에서
    //    (attachRun 은 자체 트랜잭션 리포지토리다).
    SavedQueryResponse query = readOnlyTx.execute(status -> getById(id, userId));
    return runSavedQuery(query, maxRows, readOnly, userId);
  }

  /** 조회해 둔 저장 쿼리를 실행하고 실행 기록을 붙인다. 트랜잭션 구간은 호출자(executeById)가 정한다. */
  private AnalyticsQueryResponse runSavedQuery(
      SavedQueryResponse query, int maxRows, boolean readOnly, Long userId) {
    // 저장 쿼리 실행도 실행자 기준 판정 — 공유 쿼리를 자격 없는 사람이 실행하는 경로(스펙 §4.2 3행).
    Clearance c = clearanceResolver.resolve(userId);
    AnalyticsQueryResponse r =
        guardedSqlExecutor.executeAnalytics(c, query.sqlText(), maxRows, readOnly);
    return queryResultExportService.attachRun(c, query.sqlText(), maxRows, r);
  }

  /** Get distinct folder names visible to the user. */
  // RLS 가 걸린 saved_query 를 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  @Transactional(readOnly = true)
  public List<String> getFolders(Long userId) {
    return savedQueryRepository.findDistinctFolders(userId);
  }
}
