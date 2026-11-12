package com.smartfirehub.apiconnection.repository.mybatis;

import com.smartfirehub.apiconnection.repository.ApiConnectionRepository;
import com.smartfirehub.apiconnection.repository.mybatis.ApiConnectionRows.ApiConnectionInsert;
import com.smartfirehub.apiconnection.repository.mybatis.ApiConnectionRows.PreviousStatus;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * ApiConnectionRepository 의 MyBatis 구현(WD-708) — {@code JooqApiConnectionRepository} 와 같은 의미.
 *
 * <p>트랜잭션 속성은 jOOQ 구현 그대로 클래스 단위 plain {@code @Transactional} 이다 — 배경 잡(주기 헬스체크)은 앰비언트 트랜잭션이 없고,
 * 트랜잭션이 없으면 테넌트 컨텍스트가 심기지 않아 정책이 0행이 된다. {@link #updateHealthStatus} 의 잠금 조회 + 갱신 두 문장도 이 트랜잭션이
 * 묶는다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisApiConnectionRepository implements ApiConnectionRepository {

  private final ApiConnectionMapper mapper;

  public MyBatisApiConnectionRepository(ApiConnectionMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<ApiConnectionRow> findAll() {
    return mapper.selectAll();
  }

  @Override
  public Optional<ApiConnectionRow> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id));
  }

  @Override
  public List<ApiConnectionRow> findByCreatedBy(Long userId) {
    return mapper.selectByCreatedBy(userId);
  }

  /**
   * baseUrl 이 null 이면 jOOQ 판처럼 빈 문자열로 넣는다 — PG 에서만 뜻이 있다. Tibero 는 빈 문자열을 NULL 로 저장해 NOT NULL
   * 열(base_url)이 거절하지만 이 경로에는 오지 않는다: 서비스(ApiConnectionService.validateAndNormalizeBaseUrl)가 생성 시
   * null·공백 baseUrl 을 400 으로 막아 저장소에는 늘 비어 있지 않은 값이 온다.
   */
  @Override
  public Long save(
      String name,
      String description,
      String authType,
      String encryptedAuthConfig,
      Long createdBy,
      String baseUrl,
      String healthCheckPath) {
    ApiConnectionInsert row =
        new ApiConnectionInsert(
            name,
            description,
            authType,
            encryptedAuthConfig,
            createdBy,
            baseUrl != null ? baseUrl : "",
            healthCheckPath);
    mapper.insert(row);
    return row.getId();
  }

  @Override
  public void update(
      Long id,
      String name,
      String description,
      String authType,
      String encryptedAuthConfig,
      String baseUrl,
      String healthCheckPath) {
    // 수정 시각은 jOOQ 판처럼 자바 시각이다. baseUrl 은 null(바꾸지 않음)이거나 비어 있지 않은 값이다 — 서비스가 공백 요청을 null 로 바꾸고
    // 바꿀 값은 validateAndNormalizeBaseUrl 로 검사하므로 빈 문자열(Tibero 에서 NULL → NOT NULL 위반)은 오지 않는다.
    mapper.update(
        id,
        name,
        description,
        authType,
        encryptedAuthConfig,
        baseUrl,
        healthCheckPath,
        LocalDateTime.now());
  }

  /**
   * 행을 잠그고 직전 상태를 읽은 뒤 갱신한다 — 같은 연결을 동시에 바꾸는 호출은 첫 호출이 커밋할 때까지 잠금에서 기다렸다가 커밋된 새 값을 읽는다(WD-189). 행이
   * 없으면 null(알릴 전환 없음).
   */
  @Override
  public String updateHealthStatus(Long id, String status, Long latencyMs, String errorMessage) {
    PreviousStatus previous = mapper.selectPreviousStatusForUpdate(id);
    if (previous == null) {
      return null;
    }
    mapper.updateHealth(id, status, LocalDateTime.now(), latencyMs, errorMessage);
    return previous.lastStatus();
  }

  @Override
  public List<HealthCheckTarget> findHealthCheckable() {
    return mapper.selectHealthCheckable();
  }

  @Override
  public boolean existsByName(String name) {
    return mapper.countByName(name) > 0;
  }

  @Override
  public boolean existsByNameExcludingId(String name, Long excludeId) {
    return mapper.countByNameExcludingId(name, excludeId) > 0;
  }

  @Override
  public boolean existsById(Long id) {
    return mapper.countById(id) > 0;
  }

  @Override
  public int deleteById(Long id) {
    return mapper.deleteById(id);
  }

  @Override
  public List<ReferencingPipeline> findReferencingPipelines(Long apiConnectionId) {
    return mapper.selectReferencingPipelines(apiConnectionId);
  }
}
