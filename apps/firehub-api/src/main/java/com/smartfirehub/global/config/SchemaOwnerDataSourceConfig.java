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
 * <p>풀 크기 2: 이 DataSource 는 스키마 생성 DDL 에만 쓰인다. 공유 test DB 의
 * max_connections=100 을 다른 워크트리와 나눠 쓰므로 크게 잡지 않는다.
 *
 * <p><b>minimumIdle=0(Step 9 실측으로 추가):</b> HikariCP 는 기본값(minimumIdle=maximumPoolSize)이면
 * 풀 생성 즉시 커넥션을 선점적으로 연다. 이 빈은 테스트 스위트 전체에서 스프링 컨텍스트가 새로
 * 뜰 때마다(단위 테스트만 24개 이상의 서로 다른 컨텍스트 조합을 만든다) 함께 생성되는데, 대부분의
 * 컨텍스트는 실제로 스키마를 만들지 않는다(데이터셋 관련 테스트가 아니면 이 DataSource 를 아예
 * 건드리지 않는다). minimumIdle 기본값을 쓰면 그 무관한 컨텍스트들도 매번 커넥션을 선점해, 공유
 * test DB 의 max_connections=100 을 넘겨 "FATAL: remaining connection slots are reserved for
 * roles with the SUPERUSER attribute" 로 전체 스위트가 산발적으로 실패한다(실측: 이 값 없이 전체
 * 스위트를 4회 돌려 매번 30~56개 무관 테스트가 이 원인으로 깨졌고, 같은 커밋에서 이 필드만 뺀
 * 베이스라인은 4회 모두 깨끗했다). minimumIdle=0 은 실제로 스키마를 만드는 컨텍스트에서만
 * 지연 연결을 열게 해, maximumPoolSize=2 라는 상한은 그대로 유지하면서 평상시 풋프린트를 0으로
 * 낮춘다.
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
    config.setMaximumPoolSize(2);
    config.setMinimumIdle(0); // 위 Javadoc 참조 — 평상시 커넥션 풋프린트를 0으로 유지한다.
    config.setPoolName("schema-owner");
    return new HikariDataSource(config);
  }
}
