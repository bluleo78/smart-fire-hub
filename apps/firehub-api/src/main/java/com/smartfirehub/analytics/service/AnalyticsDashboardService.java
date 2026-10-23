package com.smartfirehub.analytics.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smartfirehub.analytics.dto.AddWidgetRequest;
import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.ChartDataResponse;
import com.smartfirehub.analytics.dto.ChartResponse;
import com.smartfirehub.analytics.dto.CreateDashboardRequest;
import com.smartfirehub.analytics.dto.DashboardDataResponse;
import com.smartfirehub.analytics.dto.DashboardGridLimits;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.dto.UpdateDashboardRequest;
import com.smartfirehub.analytics.dto.UpdateWidgetLayoutRequest;
import com.smartfirehub.analytics.dto.UpdateWidgetRequest;
import com.smartfirehub.analytics.exception.ChartNotFoundException;
import com.smartfirehub.analytics.exception.DashboardNotFoundException;
import com.smartfirehub.analytics.repository.AnalyticsDashboardRepository;
import com.smartfirehub.analytics.repository.ChartRepository;
import com.smartfirehub.analytics.repository.DashboardWidgetRepository;
import com.smartfirehub.analytics.repository.SavedQueryRepository;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AnalyticsDashboardService {

  // 위젯 한도는 그리드 좌표 상한(DashboardGridLimits.MAX_POSITION_Y)의 근거이기도 해서 한 곳에 둔다(WD-193).
  private static final int MAX_WIDGETS_PER_DASHBOARD =
      DashboardGridLimits.MAX_WIDGETS_PER_DASHBOARD;

  private final AnalyticsDashboardRepository dashboardRepository;
  private final DashboardWidgetRepository widgetRepository;
  private final ChartService chartService;
  private final ChartRepository chartRepository;
  private final SavedQueryRepository savedQueryRepository;
  private final ClearanceResolver clearanceResolver;

  /** 캐시 히트 시 조회자별 감사 등급 접근 기록(실행은 chartService.executeJudged 가 한다). */
  private final GuardedSqlExecutor guardedSqlExecutor;

  /**
   * 대시보드 결과 캐시 키 — 저장 쿼리 id 와 <b>그 요청에서 판정한 SQL 원문</b>. id 만 키로 쓰면 소유자가 SQL 을 바꾼 뒤(TTL 60초 안) 새 SQL
   * 로 판정을 통과한 조회자가 옛 SQL(판정받지 않은 테이블)의 결과를 캐시 히트로 받는다. 원문이 같으면 정규화본(판정·실행 문자열)도 같다 — 정규화는 결정적이다.
   */
  private record QueryCacheKey(Long savedQueryId, String sqlText) {}

  // Caffeine cache: TTL 60s, max 200 entries, keyed by (saved_query_id, 판정한 SQL 원문)
  private final Cache<QueryCacheKey, AnalyticsQueryResponse> queryResultCache =
      Caffeine.newBuilder().expireAfterWrite(60, TimeUnit.SECONDS).maximumSize(200).build();

  // RLS 가 걸린 dashboard 를 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  @Transactional(readOnly = true)
  public com.smartfirehub.global.dto.PageResponse<DashboardResponse> list(
      String search, Boolean sharedOnly, Long userId, int page, int size) {
    List<DashboardResponse> content =
        dashboardRepository.findAll(search, sharedOnly, userId, page, size);
    long total = dashboardRepository.countAll(search, sharedOnly, userId);
    int totalPages = (int) Math.ceil((double) total / size);
    return new com.smartfirehub.global.dto.PageResponse<>(content, page, size, total, totalPages);
  }

  @Transactional
  public DashboardResponse create(CreateDashboardRequest req, Long userId) {
    Long id = dashboardRepository.insert(req, userId);
    List<DashboardResponse.DashboardWidgetResponse> widgets = viewerWidgets(id, userId);
    return dashboardRepository
        .findById(id, userId, widgets)
        .orElseThrow(() -> new DashboardNotFoundException("저장한 대시보드를 다시 읽지 못했습니다"));
  }

  // RLS 가 걸린 dashboard/dashboard_widget 을 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  // 참고(자기호출): addWidget/updateWidget 이 이 메서드를 this. 로 직접 호출하지만, 두 호출자 모두
  // 이미 @Transactional(쓰기)로 열려 있어 프록시를 안 타도 무해하다(같은 트랜잭션에 합류).
  @Transactional(readOnly = true)
  public DashboardResponse getById(Long id, Long userId) {
    List<DashboardResponse.DashboardWidgetResponse> widgets = viewerWidgets(id, userId);
    return dashboardRepository
        .findById(id, userId, widgets)
        .orElseThrow(() -> DashboardNotFoundException.forId(id));
  }

  @Transactional
  public DashboardResponse update(Long id, UpdateDashboardRequest req, Long userId) {
    dashboardRepository
        .findByIdForOwner(id, userId)
        .orElseThrow(() -> DashboardNotFoundException.forId(id));
    dashboardRepository.update(id, req, userId);

    // 대시보드를 공유하면 위젯이 쓰는 차트·쿼리 중 요청자 본인 것만 함께 공유한다(WD-194).
    // 웹 설정 창은 이름만 바꿔도 isShared 를 늘 보내므로, 재저장마다 이 경로를 탄다.
    if (Boolean.TRUE.equals(req.isShared())) {
      shareOwnedWidgetResources(id, userId);
    }

    List<DashboardResponse.DashboardWidgetResponse> widgets = viewerWidgets(id, userId);
    return dashboardRepository
        .findById(id, userId, widgets)
        .orElseThrow(() -> DashboardNotFoundException.forId(id));
  }

  /**
   * 대시보드 위젯이 쓰는 차트·저장 쿼리 중 {@code userId} 가 만든 것만 공유로 바꾼다(WD-194).
   *
   * <p>무엇을: 위젯의 차트 id 와 그 차트들이 쓰는 저장 쿼리 id 를 모아, 각각 작성자가 요청자인 행만 is_shared=true 로 바꾼다.
   *
   * <p>왜: 대시보드는 남이 공유한 차트도 위젯으로 담을 수 있다. 예전에는 소유자 조건 없이 일괄 공유해서, 남의 비공개 쿼리나 주인이 비공개로 되돌린 차트가 대시보드
   * 저장 한 번으로 주인 모르게 공유로 바뀌었다. 남의 자원의 공유 여부는 그 주인만 정한다. 남의 차트가 비공개 쿼리를 쓰더라도 조회자 화면의 차트 데이터 경로는 쿼리
   * 가시성을 따로 보지 않으므로(차트 가시성 + 보안 등급 판정만 본다) 이 제한으로 공유 대시보드 표시가 달라지지 않는다.
   *
   * <p>위젯 추가({@link #addWidget}) 시에도 대시보드가 공유 상태면 이 메서드를 그대로 쓴다(WD-195).
   */
  private void shareOwnedWidgetResources(Long dashboardId, Long userId) {
    List<Long> chartIds =
        widgetRepository.findByDashboardId(dashboardId).stream()
            .map(DashboardResponse.DashboardWidgetResponse::chartId)
            .distinct()
            .toList();
    if (chartIds.isEmpty()) {
      return;
    }
    chartRepository.shareCharts(chartIds, userId);
    // 쿼리는 남의 차트가 쓰는 것까지 모으되, 리포지토리가 쿼리 작성자로 다시 거른다 — 남의 차트가 내 쿼리를 쓰면 내 쿼리는 공유된다.
    List<Long> savedQueryIds = chartRepository.findSavedQueryIdsByChartIds(chartIds);
    savedQueryRepository.shareQueries(savedQueryIds, userId);
  }

  /**
   * 조회자 기준 위젯 목록 — 조회자가 볼 수 없는 차트의 위젯은 이름·종류를 지우고 accessible=false 로 싣는다(WD-195).
   *
   * <p>무엇을: 위젯 차트 중 조회자에게 보이는(본인 소유 또는 공유) 차트를 한 번에 판정하고, 나머지 위젯은 {@link
   * DashboardResponse.DashboardWidgetResponse#withheld()} 로 바꾼다. 위치·크기는 남긴다.
   *
   * <p>왜: 예전에는 위젯 목록이 차트 가시성을 보지 않아, 공유 대시보드에 들어간 남의(또는 주인이 나중에 비공개로 돌린) 차트 이름·종류가 다른 사람 화면과 AI 도구
   * 응답에 그대로 실렸고, 같은 위젯의 데이터는 404 라 「새로고침 실패」로 깨져 보였다. 판정은 차트 단건·차트 데이터 경로와 같은
   * 규칙(ChartRepository.visibleTo)이라 "이름은 보이는데 데이터는 404" 가 생기지 않는다. 대시보드 주인에게도 같은 규칙이다 — 남이 공유했다가
   * 비공개로 돌린 차트는 주인에게도 가려지되, 위젯 삭제는 차트 가시성과 무관하게 할 수 있다.
   *
   * <p>보안 등급(원본 데이터 열람 불가)은 다른 축이다 — 그 차트는 보이는 차트라 여기서는 accessible=true 이고, 데이터 응답의 denied 로 가린다.
   */
  private List<DashboardResponse.DashboardWidgetResponse> viewerWidgets(
      Long dashboardId, Long userId) {
    List<DashboardResponse.DashboardWidgetResponse> widgets =
        widgetRepository.findByDashboardId(dashboardId);
    if (widgets.isEmpty()) {
      return widgets;
    }
    Set<Long> visibleChartIds =
        chartRepository.findVisibleIds(
            widgets.stream().map(DashboardResponse.DashboardWidgetResponse::chartId).toList(),
            userId);
    return widgets.stream()
        .map(w -> visibleChartIds.contains(w.chartId()) ? w : w.withheld())
        .toList();
  }

  @Transactional
  public void delete(Long id, Long userId) {
    dashboardRepository
        .findByIdForOwner(id, userId)
        .orElseThrow(() -> DashboardNotFoundException.forId(id));
    boolean deleted = dashboardRepository.deleteById(id, userId);
    if (!deleted) {
      throw DashboardNotFoundException.forId(id);
    }
  }

  // RLS 가 걸린 dashboard/dashboard_widget 을 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  @Transactional(readOnly = true)
  public DashboardDataResponse getDashboardData(Long dashboardId, Long userId) {
    // 1. Load dashboard + widgets — 조회자 기준(볼 수 없는 차트는 이름을 가린 위젯, WD-195)
    List<DashboardResponse.DashboardWidgetResponse> widgets = viewerWidgets(dashboardId, userId);
    DashboardResponse dashboard =
        dashboardRepository
            .findById(dashboardId, userId, widgets)
            .orElseThrow(() -> DashboardNotFoundException.forId(dashboardId));

    // 2. Enforce widget limit — 볼 수 없는 차트의 위젯은 데이터 조립에서 먼저 뺀다(WD-195).
    // 예전에는 위젯 루프에서 건너뛰기 전에 그 차트의 저장 쿼리를 이 조회자 자격으로 판정·실행하고 감사 기록(recordDelivered)까지 남겼다 —
    // 조회자가 받지도 않는 데이터를 실행할 이유가 없다. 이 위젯은 dashboard.widgets 에 accessible=false 로 남아 자리를 지킨다.
    List<DashboardResponse.DashboardWidgetResponse> limitedWidgets =
        (widgets.size() > MAX_WIDGETS_PER_DASHBOARD
                ? widgets.subList(0, MAX_WIDGETS_PER_DASHBOARD)
                : widgets)
            .stream().filter(DashboardResponse.DashboardWidgetResponse::accessible).toList();

    // 3. Deduplicate by saved_query_id, execute cache misses
    // Build map: savedQueryId -> sqlText (via chart->savedQuery join)
    Map<Long, Long> chartIdToSavedQueryId = new HashMap<>();
    for (DashboardResponse.DashboardWidgetResponse widget : limitedWidgets) {
      Long chartId = widget.chartId();
      if (!chartIdToSavedQueryId.containsKey(chartId)) {
        Long savedQueryId = chartRepository.findSavedQueryId(chartId);
        if (savedQueryId != null) {
          chartIdToSavedQueryId.put(chartId, savedQueryId);
        }
      }
    }

    // Execute each unique savedQueryId — 보안 등급(S2): 조회자 판정이 먼저, 캐시는 그 뒤(스펙 §4.2 4행).
    //
    // 캐시를 조회자 간에 공유해도 안전한 이유:
    //  (1) 캐시를 읽기 전에 매 요청 이 조회자로 SQL 을 판정하고, 캐시 키에 그 판정한 SQL 원문을 넣는다 — 히트한 결과는 반드시
    //      이 조회자가 방금 판정을 통과한 바로 그 SQL 의 결과다(SQL 이 바뀌면 다른 키라 미스).
    //  (2) 판정 단위(VIEW)는 데이터셋 전체 허용/거부뿐이다(행·열 필터 없음) — 같은 SQL 은 VIEW 를 통과한 누가 실행해도 같은 행을 본다.
    //  (3) saved_query id 는 전역 시퀀스라 테넌트 간 키 충돌이 없다.
    // 행·열 단위 필터가 생기면 (2) 가 깨지므로 키에 조회자 가시성을 넣어야 한다.
    Clearance viewer = clearanceResolver.resolve(userId);
    Map<Long, AnalyticsQueryResponse> resultByQuery = new HashMap<>();
    java.util.Set<Long> deniedQueries = new java.util.HashSet<>();
    // 조회자별 내보내기 플래그 — 공유 캐시(queryResultCache) 밖에 둔다. 캐시 결과에 실으면 먼저 데운 조회자의 값이 다른 조회자에게 샌다(설계 결정 5,
    // Review Focus 2). 빈 SQL·판정 없음은 false(fail-closed).
    Map<Long, Boolean> exportByQuery = new HashMap<>();
    for (Long savedQueryId : new java.util.HashSet<>(chartIdToSavedQueryId.values())) {
      String sqlText = chartRepository.findSavedQuerySqlTextById(savedQueryId).orElse("");
      if (sqlText.isBlank()) {
        // 빈 SQL 은 판정할 테이블이 없다 — 기존 동작(빈 결과)을 유지한다.
        resultByQuery.put(savedQueryId, emptyQueryResponse());
        continue;
      }
      GuardedSqlExecutor.AnalyticsJudgment judgment = chartService.judge(viewer, sqlText);
      if (judgment.denied()) {
        deniedQueries.add(savedQueryId);
        continue;
      }
      exportByQuery.put(savedQueryId, judgment.exportAllowedFor());
      // 판정을 통과한 쿼리만 캐시에 닿는다. 캐시 미스면 방금 판정한 토큰을 그대로 실행한다(다시 판정하지 않는다 — 판정 = 실행). 결과를 지역
      // 맵에 담아 위젯 루프가 getIfPresent(만료·축출 시 null)에 의존하지 않게 한다.
      // 이 조회자가 로더를 실제로 돌렸는지 — 돌렸으면 실행 지점이 감사 등급 접근을 이미 남겼다. 아니면(남이 데운 캐시, 동시 로드 대기 포함) 받은
      // 결과를 이 조회자에게 넘기는 것이므로 조회자별로 남긴다(공유 캐시 값 안에 넣지 않는다).
      boolean[] loadedHere = {false};
      AnalyticsQueryResponse result =
          queryResultCache.get(
              new QueryCacheKey(savedQueryId, sqlText),
              k -> {
                loadedHere[0] = true;
                return chartService.executeJudged(judgment);
              });
      if (!loadedHere[0] && result.error() == null) {
        guardedSqlExecutor.recordDelivered(judgment);
      }
      resultByQuery.put(savedQueryId, result);
    }

    // 4. Build widget data list
    List<DashboardDataResponse.WidgetData> widgetDataList = new ArrayList<>();
    for (DashboardResponse.DashboardWidgetResponse widget : limitedWidgets) {
      Long savedQueryId = chartIdToSavedQueryId.get(widget.chartId());
      // chartId 별로 한 번만 조회하여 불필요한 중복 DB 쿼리 방지 (이슈 #148)
      ChartResponse chartResponse =
          chartService.getByIdOptional(widget.chartId(), userId).orElse(null);
      if (chartResponse == null) {
        continue;
      }
      ChartDataResponse chartData;
      if (savedQueryId != null && deniedQueries.contains(savedQueryId)) {
        // 위반 위젯 — 빈 결과 + denied. 거부 코드·원본 데이터셋 이름은 싣지 않는다(스펙 §2.5).
        chartData = ChartService.deniedData(chartResponse);
      } else {
        AnalyticsQueryResponse queryResult =
            savedQueryId != null ? resultByQuery.get(savedQueryId) : null;
        chartData =
            new ChartDataResponse(
                chartResponse,
                queryResult != null ? queryResult : emptyQueryResponse(),
                false,
                savedQueryId != null && exportByQuery.getOrDefault(savedQueryId, false));
      }
      widgetDataList.add(new DashboardDataResponse.WidgetData(widget.id(), chartData));
    }

    return new DashboardDataResponse(dashboard, widgetDataList);
  }

  @Transactional
  public DashboardResponse addWidget(Long dashboardId, AddWidgetRequest req, Long userId) {
    // 대시보드 행을 먼저 잠가 같은 대시보드의 위젯 추가를 직렬화한다(WD-202). 잠금 없이 동시 요청 12건이 같은 개수(18)를 보고 모두 통과해
    // 위젯이 29개까지 쌓였다. 잠금은 이 트랜잭션이 끝날 때 풀리므로 뒤 요청은 앞 요청의 삽입을 본 뒤 개수·중복을 판정한다.
    if (!dashboardRepository.lockByIdForOwner(dashboardId, userId)) {
      throw DashboardNotFoundException.forId(dashboardId);
    }

    // Verify dashboard ownership (공유 여부는 잠금 뒤에 읽어 최신 값을 쓴다)
    DashboardResponse dashboard =
        dashboardRepository
            .findByIdForOwner(dashboardId, userId)
            .orElseThrow(() -> DashboardNotFoundException.forId(dashboardId));

    // Verify chart access
    chartService
        .getByIdOptional(req.chartId(), userId)
        .orElseThrow(() -> ChartNotFoundException.forId(req.chartId()));

    // 같은 차트는 한 대시보드에 한 번만(WD-202). 예전에는 웹 편집 화면만 막아 AI 도구·API·차트 편집기의 「대시보드에 추가」로는 중복이 쌓였다.
    // 이미 쌓인 중복은 건드리지 않는다(DB 유일 제약 없음) — 새로 넣는 것만 거부한다. 409 문구는 웹 토스트·AI 도구 결과로 그대로 나간다.
    if (widgetRepository.existsByDashboardIdAndChartId(dashboardId, req.chartId())) {
      throw new IllegalStateException("이미 대시보드에 추가된 차트입니다");
    }

    // Check widget limit — 문구는 화면 토스트로 그대로 나가므로 한국어(WD-196)
    int currentCount = widgetRepository.countByDashboardId(dashboardId);
    if (currentCount >= MAX_WIDGETS_PER_DASHBOARD) {
      throw new IllegalStateException(
          "대시보드에는 위젯을 최대 " + MAX_WIDGETS_PER_DASHBOARD + "개까지 넣을 수 있습니다");
    }

    widgetRepository.insert(dashboardId, req);

    // 이미 공유된 대시보드에 위젯을 넣으면 그 차트·쿼리 중 요청자 본인 것을 공유한다(WD-195) — 대시보드 공유 시와 같은 규칙(WD-194).
    // 예전에는 update() 에만 있어, 공유 대시보드에 비공개 차트를 넣으면 다른 사람 화면에서 그 위젯만 깨졌고 주인은 설정 창을 한 번 더
    // 저장해야 했다. 남의 차트는 여기서도 공유로 바꾸지 않는다 — 남이 공유한 차트만 위젯으로 넣을 수 있어 추가 시점에는 이미 공유 상태다.
    if (dashboard.isShared()) {
      shareOwnedWidgetResources(dashboardId, userId);
    }
    return getById(dashboardId, userId);
  }

  // 위젯 단건 수정은 위치·크기만 바꾼다(UpdateWidgetRequest 에 chartId 가 없다) — 차트가 바뀌지 않으므로 공유 처리가 필요 없다(WD-195).
  @Transactional
  public DashboardResponse updateWidget(
      Long dashboardId, Long widgetId, UpdateWidgetRequest req, Long userId) {
    dashboardRepository
        .findByIdForOwner(dashboardId, userId)
        .orElseThrow(() -> DashboardNotFoundException.forId(dashboardId));

    widgetRepository.findById(widgetId, dashboardId).orElseThrow(() -> widgetNotFound(widgetId));

    widgetRepository.update(widgetId, req);
    return getById(dashboardId, userId);
  }

  @Transactional
  public void removeWidget(Long dashboardId, Long widgetId, Long userId) {
    dashboardRepository
        .findByIdForOwner(dashboardId, userId)
        .orElseThrow(() -> DashboardNotFoundException.forId(dashboardId));

    boolean deleted = widgetRepository.deleteById(widgetId, dashboardId);
    if (!deleted) {
      throw widgetNotFound(widgetId);
    }
  }

  /**
   * 이 대시보드에 없는 위젯 id 거절(400) — 단건 수정·삭제 공용.
   *
   * <p>WD-196: 영어 원문(「Widget not found: 1 in dashboard 3」)이 토스트로 그대로 보였다. 한국어로 바꾸고 대시보드 id 는 빼서, 일괄
   * 배치 저장의 「이 대시보드에 없는 위젯이 포함되어 있습니다」와 같은 결로 맞춘다.
   */
  private static IllegalArgumentException widgetNotFound(Long widgetId) {
    return new IllegalArgumentException("위젯을 찾을 수 없습니다: " + widgetId);
  }

  /**
   * 위젯 배치(좌표·크기)를 일괄 저장한다.
   *
   * <p>WD-193: 예전에는 경로 대시보드의 소유만 확인하고 본문의 위젯 id 는 대시보드 소속을 보지 않아, 같은 워크스페이스의 다른 사람 대시보드 위젯을 옮기거나
   * 크기를 바꿀 수 있었다. 이제 쓰기 전에 요청한 위젯 id 가 모두 이 대시보드 소속인지 먼저 확인하고, 하나라도 아니면 아무것도 쓰지 않고 전체를 거절한다(일부만
   * 반영하지 않는다).
   *
   * <p>거절은 단건 수정·삭제({@code /widgets/{wId}})와 같은 400(IllegalArgumentException) 관례를 따른다. 없는 위젯 id 와
   * 남의 대시보드 위젯 id 는 같은 문구로 응답해, 다른 대시보드에 그 위젯이 있는지 드러내지 않는다.
   */
  @Transactional
  public void updateWidgetLayout(Long dashboardId, UpdateWidgetLayoutRequest req, Long userId) {
    dashboardRepository
        .findByIdForOwner(dashboardId, userId)
        .orElseThrow(() -> DashboardNotFoundException.forId(dashboardId));

    List<UpdateWidgetLayoutRequest.WidgetPosition> positions = req.widgets();
    // 빈 목록은 바꿀 것이 없으므로 그대로 성공 처리한다(컨트롤러 검증이 null 은 이미 400 으로 걸렀다).
    if (positions == null || positions.isEmpty()) {
      return;
    }

    // 요청 위젯 id 중 이 대시보드 소속이 아닌 것을 쓰기 전에 모두 찾는다 — 먼저 검사하므로 거절 시 어떤 행도 바뀌지 않는다.
    Set<Long> requestedIds = new LinkedHashSet<>();
    positions.forEach(p -> requestedIds.add(p.widgetId()));
    Set<Long> ownedIds = widgetRepository.findIdsInDashboard(dashboardId, requestedIds);
    List<Long> foreignIds = requestedIds.stream().filter(id -> !ownedIds.contains(id)).toList();
    if (!foreignIds.isEmpty()) {
      throw new IllegalArgumentException("이 대시보드에 없는 위젯이 포함되어 있습니다: " + foreignIds);
    }

    widgetRepository.batchUpdateLayout(dashboardId, positions);
  }

  private AnalyticsQueryResponse emptyQueryResponse() {
    return new AnalyticsQueryResponse("SELECT", List.of(), List.of(), 0, 0L, 0, false, null);
  }
}
