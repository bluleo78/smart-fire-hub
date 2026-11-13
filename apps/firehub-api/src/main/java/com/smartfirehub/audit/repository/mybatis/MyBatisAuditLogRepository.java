package com.smartfirehub.audit.repository.mybatis;

import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.audit.repository.AuditLogRepository;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformText;
import com.smartfirehub.global.platformdb.mybatis.TiberoTextLimits;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * AuditLogRepository 의 MyBatis 구현(WD-706) — {@code JooqAuditLogRepository} 와 같은 의미. SQL 은 {@code
 * mybatis/audit/AuditLogMapper.xml}.
 *
 * <p>클래스 레벨 {@code @Transactional} 은 jOOQ 구현에서 그대로 옮겼다: {@code AuditLogService.log} 에는 트랜잭션이 없다.
 * 로그인처럼 트랜잭션 안에서 호출되는 경로는 편승하지만, 알림·비동기 내보내기 경로는 편승할 트랜잭션이 없어 테넌트(PG GUC·Tibero 컨텍스트)가 심기지 않고
 * tenant_id DEFAULT 가 NULL 이 되어 테넌트 행이 플랫폼 행으로 잘못 남는다. 전파는 REQUIRED 라 기존 경로의 동작은 불변이다. (이슈 #384-3)
 *
 * <p>이 클래스는 공백 필터 무시·LIKE 이스케이프·가시성 값 펼치기·페이지 계산만 맡고, 조건 렌더는 XML 이 한다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisAuditLogRepository implements AuditLogRepository {

  private final AuditLogMapper mapper;

  public MyBatisAuditLogRepository(AuditLogMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Long save(
      Long userId,
      String username,
      String actionType,
      String resource,
      String resourceId,
      String description,
      String ipAddress,
      String userAgent,
      String result,
      String errorMessage,
      String metadataJson) {
    AuditLogInsert row =
        new AuditLogInsert(
            userId,
            username,
            actionType,
            resource,
            resourceId,
            description,
            ipAddress,
            userAgent,
            result,
            // 오류 메시지는 호출처 예외 문구라 길이를 통제할 수 없다 — Tibero VARCHAR2(65532) 바이트 한도를 넘으면 INSERT 가 실패해
            // 감사 행이 사라지므로 앞부분으로 자른다(WD-728).
            TiberoTextLimits.truncateErrorMessage(errorMessage),
            metadataJson);
    mapper.insert(row);
    return row.getId();
  }

  @Override
  public Optional<AuditLogResponse> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id));
  }

  @Override
  public PageResponse<AuditLogResponse> findByResource(
      String actionType, String resource, String resourceId, int page, int size) {
    // jOOQ 와 같이 null 만 조건에서 뺀다(공백은 그대로 비교한다).
    AuditLogQuery q =
        new AuditLogQuery()
            .resource(resource)
            .actionType(actionType)
            .resourceId(resourceId)
            .page(page, size);
    return page(mapper.countByResource(q), mapper.selectByResource(q), page, size);
  }

  @Override
  public PageResponse<AuditLogResponse> findAll(
      String search,
      Long userId,
      String actionType,
      String resource,
      String result,
      LocalDateTime startDate,
      LocalDateTime endDate,
      int page,
      int size,
      Optional<DatasetVisibility> datasetVisibility) {
    // Optional.empty()(ADMIN 무제한)는 vis null — XML 이 데이터셋 필터를 붙이지 않는다.
    AuditLogQuery q =
        new AuditLogQuery()
            .vis(datasetVisibility.map(MyBatisDatasetVisibility::params).orElse(null))
            .searchPattern(PlatformText.searchPattern(search))
            .userId(userId)
            .actionType(PlatformText.blankToNull(actionType))
            .resource(PlatformText.blankToNull(resource))
            .result(PlatformText.blankToNull(result))
            .fromInclusive(startDate)
            .toInclusive(endDate)
            .page(page, size);
    return page(mapper.countAll(q), mapper.selectAll(q), page, size);
  }

  @Override
  public PageResponse<AuditLogResponse> findPlatform(
      String actor,
      String target,
      String actionType,
      LocalDateTime fromInclusive,
      LocalDateTime toExclusive,
      int page,
      int size) {
    AuditLogQuery q =
        new AuditLogQuery()
            .actorPattern(PlatformText.searchPattern(actor))
            .actionType(PlatformText.blankToNull(actionType))
            .fromInclusive(fromInclusive)
            .toExclusive(toExclusive)
            .page(page, size);
    String t = PlatformText.blankToNull(target);
    if (t != null) {
      t = t.trim(); // 정확 일치 값과 패턴 모두 trim 한 값으로 — 이전 동작과 같다.
      q.target(t, PlatformText.searchPattern(t));
    }
    return page(mapper.countPlatform(q), mapper.selectPlatform(q), page, size);
  }

  /** 건수·한 쪽 내용으로 페이지 응답을 만든다(전체 쪽 수는 올림). */
  private static PageResponse<AuditLogResponse> page(
      long total, List<AuditLogResponse> content, int page, int size) {
    int totalPages = (int) Math.ceil((double) total / size);
    return new PageResponse<>(content, page, size, total, totalPages);
  }
}
