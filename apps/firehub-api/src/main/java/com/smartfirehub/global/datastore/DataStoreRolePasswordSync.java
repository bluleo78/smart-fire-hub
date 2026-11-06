package com.smartfirehub.global.datastore;

import com.smartfirehub.global.config.FlywayCallbackConfig;
import com.smartfirehub.global.config.RolePasswordSyncCallback;
import com.smartfirehub.global.platformdb.PlatformDbType;
import com.smartfirehub.tenant.repository.TenantRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 분리 모드 데이터 저장소 DB 의 롤 비밀번호를 설정값으로 맞춘다(WD-494 스펙 4절).
 *
 * <p>왜: PG 설치본은 플랫폼 Flyway 콜백({@link FlywayCallbackConfig})이 플랫폼 DB(= 데이터 저장소)의 롤 비밀번호를 매 기동 맞춘다.
 * 분리 모드에서는 롤이 <b>데이터 저장소 서버</b>에 있으므로 그 서버에서 맞춰야 한다. 그 콜백은 Flyway 연결로 {@code tenant} 를 읽는데, 데이터 저장소
 * DB 에는 {@code tenant} 가 없어 그대로 쓸 수 없다 — 테넌트 목록은 플랫폼 리포지토리로 읽는다.
 *
 * <p>언제·어느 연결로: 데이터 저장소 Flyway({@link DataStoreFlywayConfig})의 {@code AFTER_MIGRATE} 콜백으로 붙는다 — 즉
 * 기준선 직후, {@code ApplicationReadyEvent} 보다 앞, 데이터 저장소 풀이 처음 접속하기 전에 한 번 돈다(풀 빈이 Flyway 빈에 의존).
 * ALTER ROLE 은 그 Flyway 의 연결 = 데이터 저장소 소유자 연결로 한다. 소유자 풀(SchemaOwnerDataSourceConfig, 크기 1)을 쓰지 않아
 * 노출 가드에 새 주입 지점을 늘리지 않는다.
 *
 * <p>비밀번호 출처는 PG 설치본과 같다: {@code app_tenant} = {@code firehub.data-store.postgres.password}, {@code
 * pipeline_executor} = {@code app.pipeline.datasource.password}, 테넌트 롤 = {@code
 * app.pipeline.role-password-secret} 파생({@link
 * FlywayCallbackConfig#existingTenantPipelineRolePasswords}). 두 고정 롤은 기준선이 반드시 만들므로 없으면 ALTER 가 실패해
 * 기동이 멈춘다(fail-closed). 테넌트 롤은 프로비저너가 만들므로 없으면 조용히 건너뛴다(콜백과 같은 판단).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = DataStoreDbConfig.SEPARATE_PROPERTY, havingValue = "true")
public class DataStoreRolePasswordSync {

  /** Flyway 로그에 보이는 콜백 이름. */
  static final String CALLBACK_NAME = "dataStoreRolePasswordSync";

  private final TenantRepository tenantRepository;
  private final String appTenantPassword;
  private final String pipelineExecutorPassword;
  private final String tenantPipelineRoleSecret;

  public DataStoreRolePasswordSync(
      TenantRepository tenantRepository,
      @Value("${firehub.data-store.postgres.password}") String appTenantPassword,
      @Value("${app.pipeline.datasource.password}") String pipelineExecutorPassword,
      @Value("${app.pipeline.role-password-secret}") String tenantPipelineRoleSecret,
      @Value("${" + PlatformDbType.PROPERTY + ":postgres}") String platformDbType,
      @Value("${spring.datasource.password:}") String platformAppTenantPassword) {
    this.tenantRepository = tenantRepository;
    this.appTenantPassword = appTenantPassword;
    this.pipelineExecutorPassword = pipelineExecutorPassword;
    this.tenantPipelineRoleSecret = tenantPipelineRoleSecret;
    if (conflictsWithPlatformAppTenant(
        platformDbType, appTenantPassword, platformAppTenantPassword)) {
      // 비밀번호 값은 남기지 않는다 — "다르다"는 사실만 알린다.
      log.warn(
          "분리 모드 데이터 저장소 app_tenant 비밀번호(firehub.data-store.postgres.password)가 플랫폼 PG"
              + " 비밀번호(spring.datasource.password)와 다르다. app_tenant 는 PG 클러스터 전역 롤이라, 두 DB 가 같은 클러스터면"
              + " 기동마다 플랫폼 Flyway 와 데이터 저장소 Flyway 가 서로 다른 값으로 덮어써 플랫폼 풀 접속이 실패한다."
              + " 같은 클러스터면 두 값을 같게 둘 것(ADR 0001 분리 모드).");
    }
  }

  /**
   * 플랫폼 DB 가 PG 이고 두 app_tenant 비밀번호가 다른지 — 같은 클러스터라면 서로 덮어쓰는 조합이다(최종 리뷰 지적 4).
   *
   * <p>왜 막지 않고 경고만 하는가: 클러스터가 같은지는 URL 만으로 확실히 가를 수 없고(별칭 호스트·프록시), 다른 클러스터면 비밀번호가 달라도 정상이다. Tibero
   * 플랫폼이면 플랫폼 쪽에 app_tenant 롤이 없으므로 따지지 않는다.
   */
  static boolean conflictsWithPlatformAppTenant(
      String platformDbType, String dataStorePassword, String platformPassword) {
    boolean postgresPlatform =
        PlatformDbType.POSTGRES.name().equals(platformDbType.trim().toUpperCase(Locale.ROOT));
    return postgresPlatform && !Objects.equals(dataStorePassword, platformPassword);
  }

  /**
   * 데이터 저장소 Flyway 에 붙일 AFTER_MIGRATE 콜백 — ALTER ROLE 자체는 기존 {@link RolePasswordSyncCallback} 이 한다.
   */
  RolePasswordSyncCallback callback() {
    return new RolePasswordSyncCallback(CALLBACK_NAME, this::resolve);
  }

  /**
   * 동기화 대상 "롤 → 비밀번호". 고정 롤 두 개 + ACTIVE 테넌트의 실행·슬롯 롤 중 데이터 저장소 서버에 있는 것.
   *
   * @param connection 데이터 저장소 소유자 연결(Flyway 콜백 연결) — 롤 존재 확인을 그 서버에서 한다
   */
  Map<String, String> resolve(Connection connection) throws SQLException {
    Map<String, String> targets = new LinkedHashMap<>();
    targets.put("app_tenant", appTenantPassword);
    targets.put("pipeline_executor", pipelineExecutorPassword);

    // 테넌트 롤은 이 서버(데이터 저장소)에 있는 것만 — 후보 계산과 존재 확인은 PG 설치본 콜백과 같은 헬퍼로 한다.
    targets.putAll(
        FlywayCallbackConfig.existingTenantPipelineRolePasswords(
            connection, tenantRepository.findActiveTenantIds(), tenantPipelineRoleSecret));
    return targets;
  }
}
