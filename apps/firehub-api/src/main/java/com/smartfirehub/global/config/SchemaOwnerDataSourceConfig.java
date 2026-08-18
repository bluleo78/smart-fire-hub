package com.smartfirehub.global.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 테넌트 스키마 생성(DDL) 전용 DataSource.
 *
 * <p>왜 런타임 DataSource(app_tenant)를 쓰지 않는가: app_tenant 는 CREATE ON DATABASE 권한이
 * 없다(실측). 여기서 권한을 부여하면 RLS 를 지는 런타임 롤이 임의 스키마를 만들 수 있게 되는
 * 권한 확대다. 대신 이미 CREATE 권한을 가진 소유자 롤(app — Flyway 가 쓰는 그 롤)로 별도 풀을
 * 연다. 새 스키마 소유자도 기존 data 와 같은 app 이 되어 권한 모델이 일관된다.
 *
 * <p>풀 크기 1(2026-08-18 라운드 1 리뷰로 하향): 이 DataSource 는 스키마 생성 DDL <b>한 문장씩
 * 순차 실행</b>에만 쓰인다({@link TenantSchemaProvisioner#ensureCurrentTenantSchema} 가 유일한
 * 호출부이고, 그 안의 모든 DDL 이 한 트랜잭션 안에서 순서대로 돈다) — 동시에 2개 커넥션을
 * 필요로 할 이유가 없다. 공유 test DB 의 max_connections=100 을 다른 워크트리와 나눠 쓰므로
 * 상한을 최소로 잡는다.
 *
 * <p><b>minimumIdle=0 + initializationFailTimeout(-1) + idleTimeout 짧게(Step 9 실측 + 라운드 1
 * 리뷰로 추가):</b> `minimumIdle=0` 만으로는 창을 <b>좁혔을 뿐 닫지 못했다</b> — HikariCP 는
 * `initializationFailTimeout` 기본값(1ms 이상)이면 풀 생성 시 `checkFailFast()` 로 커넥션을
 * 하나 열었다 곧바로 닫는다. 즉 스프링 컨텍스트가 생성될 때마다(전체 스위트에서 24개 이상의
 * 서로 다른 컨텍스트 조합이 뜬다) 이 DataSource 를 실제로 쓰지 않는 컨텍스트조차 순간적으로
 * 커넥션을 1개 열었다 닫는다. `initializationFailTimeout(-1)`은 이 즉시-검증 자체를 꺼서(이미
 * `TenantSchemaProvisioner`의 지연 사용 패턴과 일관됨 — 실제로 스키마를 만들 때만 커넥션을
 * 연다) 그 순간 풋프린트마저 없앤다. 또한 기본 `idleTimeout`(600초)이면 실제로 프로비저닝을
 * 수행한 컨텍스트는 커넥션을 스위트가 끝날 때까지 붙잡고 있으므로, 그 컨텍스트가 스프링
 * 테스트 컨텍스트 캐시에 오래 남아 있는 동안(기본 캐시 크기 32) 불필요하게 자리를 차지한다 —
 * `idleTimeout`을 30초로 짧게 줘 곧 반납되게 한다.
 *
 * <p>이 값들 없이 전체 스위트를 4회 돌려 매번 30~56개의 무관한 테스트가 "FATAL: remaining
 * connection slots are reserved for roles with the SUPERUSER attribute" 로 산발 실패했고,
 * 같은 커밋에서 이 필드들만 뺀 베이스라인은 4회 모두 깨끗했다(원인 확정). `minimumIdle(0)`만
 * 넣고 재검증했을 때도 3회 연속 클린이 나왔지만, 라운드 1 리뷰가 `checkFailFast`/`idleTimeout`
 * 잔여 창을 실측으로 지적해 위 두 값을 추가했다 — 즉 이전 3회 클린은 "창이 좁아 안 걸렸을 뿐"
 * 이었을 수 있고, 이번 수정으로 창 자체를 없앤다.
 */
@Configuration
public class SchemaOwnerDataSourceConfig {

  @Bean(name = "schemaOwnerDataSource")
  public DataSource schemaOwnerDataSource(
      @Value("${spring.flyway.url}") String url,
      // ⚠ 키 이름은 user 다 — Spring Boot Flyway 규약. username 으로 쓰면 기동이 깨진다.
      @Value("${spring.flyway.user}") String username,
      @Value("${spring.flyway.password}") String password) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(username);
    config.setPassword(password);
    config.setMaximumPoolSize(1); // 순차 DDL 전용 — 동시 2커넥션을 쓸 이유가 없다(위 Javadoc).
    config.setMinimumIdle(0); // 평상시 유휴 커넥션을 두지 않는다.
    config.setInitializationFailTimeout(-1); // 풀 생성 시 즉시 검증 커넥션(checkFailFast)을 끈다.
    config.setIdleTimeout(30_000); // 실제로 쓰인 커넥션도 30초 후에는 반납한다.
    config.setPoolName("schema-owner");
    return new HikariDataSource(config);
  }
}
