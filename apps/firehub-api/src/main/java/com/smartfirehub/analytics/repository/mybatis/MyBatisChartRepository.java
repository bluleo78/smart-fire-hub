package com.smartfirehub.analytics.repository.mybatis;

import static com.smartfirehub.global.platformdb.mybatis.PlatformText.blankToNull;
import static com.smartfirehub.global.platformdb.mybatis.PlatformText.searchPattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.analytics.dto.ChartResponse;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.DeleteImpactResponse;
import com.smartfirehub.analytics.dto.UpdateChartRequest;
import com.smartfirehub.analytics.repository.ChartRepository;
import com.smartfirehub.analytics.repository.mybatis.AnalyticsRows.SharingCounts;
import com.smartfirehub.analytics.repository.mybatis.ChartRows.ChartInsert;
import com.smartfirehub.analytics.repository.mybatis.ChartRows.ChartRow;
import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.global.exception.SerializationException;
import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformJson;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformSharing;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

/**
 * ChartRepository 의 MyBatis 구현(WD-710) — {@code JooqChartRepository} 와 같은 의미.
 *
 * <p>트랜잭션 속성은 jOOQ 구현 그대로 클래스 단위 트랜잭션이 없다(서비스 트랜잭션 안에서 불린다). 예외는 {@link #shareCharts} — 저장 쿼리의
 * {@code shareQueries} 와 같이 jOOQ 의 한 문장 {@code UPDATE … RETURNING} 을 「잠금 조회 + UPDATE」 두 문장으로 옮겼으므로
 * 둘이 한 트랜잭션이어야 같은 원자성이 된다(호출자 트랜잭션이 있으면 그대로 합류).
 *
 * <p>공유 가시성(소유 또는 공유)은 XML 의 {@code DashboardVisibility.ownedOrShared} 조각 하나로 목록·단건·SQL 조회·가시 id·삭제
 * 영향 판정을 맞춘다. config JSON 은 jOOQ 판과 같게 쓰기는 null·빈 맵·직렬화 실패 → {@code {}}, 읽기는 null·빈 값 → {@code {}},
 * 깨진 값 → 빈 맵이다.
 */
@PlatformMyBatisRepository
public class MyBatisChartRepository implements ChartRepository {

  private final ChartMapper mapper;
  private final ObjectMapper objectMapper;

  public MyBatisChartRepository(ChartMapper mapper, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<ChartResponse> findAll(
      String search,
      String chartType,
      Long savedQueryId,
      Boolean sharedOnly,
      Boolean mineOnly,
      Long userId,
      int page,
      int size) {
    // jOOQ 판 offset(page * size)과 같은 값 — int 곱 오버플로를 피하려 long 으로 곱한다.
    return mapper
        .selectPage(
            Boolean.TRUE.equals(sharedOnly),
            Boolean.TRUE.equals(mineOnly),
            userId,
            searchPattern(search),
            blankToNull(chartType),
            savedQueryId,
            size,
            (long) page * size)
        .stream()
        .map(this::toResponse)
        .toList();
  }

  @Override
  public long countAll(
      String search,
      String chartType,
      Long savedQueryId,
      Boolean sharedOnly,
      Boolean mineOnly,
      Long userId) {
    return mapper.count(
        Boolean.TRUE.equals(sharedOnly),
        Boolean.TRUE.equals(mineOnly),
        userId,
        searchPattern(search),
        blankToNull(chartType),
        savedQueryId);
  }

  @Override
  public Optional<ChartResponse> findById(Long id, Long userId) {
    return Optional.ofNullable(mapper.selectDetail(id, userId, false)).map(this::toResponse);
  }

  @Override
  public Optional<ChartResponse> findByIdForOwner(Long id, Long userId) {
    return Optional.ofNullable(mapper.selectDetail(id, userId, true)).map(this::toResponse);
  }

  @Override
  public List<DeleteImpactResponse.ChartRawRef> findImpactRefsBySavedQueryId(
      Long savedQueryId, Long userId) {
    return mapper.selectImpactRefs(savedQueryId, userId).stream()
        .map(r -> new DeleteImpactResponse.ChartRawRef(r.id(), r.name(), r.visible()))
        .toList();
  }

  @Override
  public Long findSavedQueryId(Long chartId) {
    return mapper.selectSavedQueryId(chartId);
  }

  @Override
  public Optional<String> findSavedQuerySqlTextById(Long savedQueryId) {
    return Optional.ofNullable(mapper.selectSqlTextBySavedQueryId(savedQueryId));
  }

  @Override
  public Optional<String> findSavedQuerySqlText(Long chartId, Long userId) {
    return Optional.ofNullable(mapper.selectVisibleSqlText(chartId, userId));
  }

  @Override
  public Long insert(CreateChartRequest req, Long userId) {
    ChartInsert row =
        new ChartInsert(
            req.name(),
            req.description(),
            req.savedQueryId(),
            req.chartType(),
            writeConfig(req.config()),
            req.isShared(),
            userId);
    mapper.insert(row);
    return row.getId();
  }

  @Override
  public Optional<LockedRowVersion> lockUpdatedAtById(Long id) {
    return Optional.ofNullable(mapper.lockVersionById(id))
        .map(row -> new LockedRowVersion(row.updatedAt()));
  }

  /**
   * 넘긴 필드만 고친다 — null 은 「변경 없음」, 설명의 빈 문자열(공백만 포함)은 「비움」(NULL, WD-197). config 는 null 이 아닐 때만 쓰고 빈
   * 맵은 {@code {}} 로 덮어쓴다(jOOQ 판과 같다). 수정 시각은 jOOQ 판과 같은 자바 시각을 바인딩한다.
   */
  @Override
  public void update(Long id, UpdateChartRequest req, Long userId) {
    mapper.update(
        id,
        req.name(),
        req.description() != null,
        blankToNull(req.description()),
        req.chartType(),
        req.config() == null ? null : writeConfig(req.config()),
        req.isShared(),
        userId,
        LocalDateTime.now());
  }

  /**
   * 내가 만든 비공유 차트만 공유로 바꾸고 바뀐 것의 id → 이름을 돌려준다(WD-194·464, Review Focus 4). 대상을 먼저 FOR UPDATE 로 잠가
   * 읽고 그 id 만 바꾸므로, 이미 공유된 것·남의 것·다른 테넌트 것은 돌려주는 맵에도 UPDATE 에도 들지 않는다. 잠근 행 수와 바꾼 행 수가 다르면 감사 맵이
   * 실제와 어긋나므로 조용히 넘기지 않고 예외로 알린다.
   */
  @Override
  @Transactional
  public Map<Long, String> shareCharts(List<Long> chartIds, Long ownerId) {
    return PlatformSharing.flip(
        chartIds,
        chunks -> mapper.selectShareTargetsForUpdate(chunks, ownerId),
        mapper::markShared,
        "차트");
  }

  @Override
  public Set<Long> findVisibleIds(Collection<Long> chartIds, Long userId) {
    if (chartIds.isEmpty()) return Set.of();
    return new HashSet<>(mapper.selectVisibleIds(InChunks.of(chartIds), userId));
  }

  @Override
  public int[] countOwnedBySharing(List<Long> chartIds, Long ownerId) {
    if (chartIds.isEmpty()) return new int[] {0, 0};
    SharingCounts c = mapper.countOwnedBySharing(InChunks.of(chartIds), ownerId);
    return new int[] {(int) c.privateCount(), (int) c.sharedCount()};
  }

  @Override
  public List<Long> findSavedQueryIdsByChartIds(List<Long> chartIds) {
    if (chartIds.isEmpty()) return List.of();
    return mapper.selectSavedQueryIds(InChunks.of(chartIds));
  }

  @Override
  public boolean deleteById(Long id, Long userId) {
    return mapper.deleteByIdAndOwner(id, userId) > 0;
  }

  /** 조회 행 → 응답. config 텍스트를 맵으로 바꾼다(configWithheld 는 리포지토리에서 늘 false — 가림은 서비스 몫). */
  private ChartResponse toResponse(ChartRow r) {
    return new ChartResponse(
        r.id(),
        r.name(),
        r.description(),
        r.savedQueryId(),
        r.savedQueryName(),
        r.chartType(),
        readConfig(r.configJson()),
        r.shared(),
        r.createdByName(),
        r.createdBy(),
        r.createdAt(),
        r.updatedAt(),
        r.dashboardCount(),
        false);
  }

  /** config 맵 → JSON 텍스트. null·빈 맵·직렬화 실패는 {@code {}} — jOOQ 판 mapToJson 과 같다. */
  String writeConfig(Map<String, Object> config) {
    if (config == null || config.isEmpty()) return "{}";
    try {
      return PlatformJson.write(objectMapper, config, "chart config");
    } catch (SerializationException e) {
      return "{}";
    }
  }

  /**
   * config JSON 텍스트 → 맵. null·빈 값은 {@code {}} 로 읽고, 깨진 값(객체가 아님 포함)은 빈 맵 — jOOQ 판 parseJson 과 같다.
   */
  Map<String, Object> readConfig(String json) {
    String text = json == null || json.isBlank() ? "{}" : json;
    try {
      return PlatformJson.toMap(objectMapper, text, "chart config");
    } catch (SerializationException e) {
      return Map.of();
    }
  }
}
