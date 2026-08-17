package com.smartfirehub.pipeline.service.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code SqlValidator.POSTGIS_SAFE_FUNCTIONS}(리소스 파일에서 로드한 스냅샷)와 라이브 카탈로그를 양방향
 * 대조해 드리프트를 잡는다. (#385 재재리뷰 M3)
 *
 * <p><b>왜 필요한가.</b> {@code postgis-safe-functions.txt}는 특정 시점의 카탈로그 스냅샷을 파일로 옮긴
 * 것이라, 오늘은 바이트 단위로 일치해도 PostGIS 가 업그레이드되면 조용히 어긋난다. 이 격차는 {@code
 * fail-closed} 방향이라 새 보안 구멍은 아니다 — 새로 생긴 정상 함수가 허용목록에 없어 정상 쿼리가 조용히
 * 거부되는 <b>유지보수 갭</b>이다. 이 테스트는 그 갭을 발견형으로 잡는다({@code
 * TenantSchemaConformanceTest}의 발견형 전수 검사 패턴과 같은 설계 원칙).
 *
 * <p>리소스 파일 생성 쿼리와 이름 단위(오버로드 아님) 필터링 근거는 {@code postgis-safe-functions.txt} 파일
 * 헤더 주석 참고 — 이 테스트는 그 쿼리를 그대로 재실행해 대조한다.
 */
class PostgisSafeFunctionsConformanceTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  private static final String CATALOG_QUERY =
      "SELECT p.proname FROM pg_proc p"
          + " JOIN pg_depend d ON d.objid = p.oid AND d.deptype = 'e'"
          + " JOIN pg_extension e ON e.oid = d.refobjid"
          + " WHERE e.extname IN ('postgis','postgis_topology')"
          + " GROUP BY p.proname"
          + " HAVING bool_and(p.provolatile <> 'v')";

  @Test
  void resourceFileMatchesLiveCatalogExactly() {
    List<String> discovered = dsl.fetch(CATALOG_QUERY).getValues(0, String.class);

    // 자기검증 — 발견 쿼리가 망가져 빈 집합이 되면 아래 전수 단언이 공허하게 통과한다.
    assertThat(discovered)
        .as("postgis/postgis_topology 의 non-volatile 함수가 하나도 발견되지 않았다 — 발견 쿼리를 의심하라")
        .isNotEmpty();

    // 역방향까지 포함한 양방향 단언 — 리소스 파일에만 있는 이름(카탈로그에서 사라짐)과 카탈로그에만 있는
    // 이름(리소스 파일이 낡음) 둘 다 이 한 줄로 잡힌다.
    assertThat(SqlValidator.POSTGIS_SAFE_FUNCTIONS)
        .as(
            "postgis-safe-functions.txt 가 라이브 카탈로그와 어긋났다 — 파일 헤더의 생성 쿼리를 재실행해 갱신하라")
        .containsExactlyInAnyOrderElementsOf(discovered);
  }
}
