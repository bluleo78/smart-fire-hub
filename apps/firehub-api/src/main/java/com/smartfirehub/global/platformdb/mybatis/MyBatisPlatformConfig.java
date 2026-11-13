package com.smartfirehub.global.platformdb.mybatis;

import com.smartfirehub.global.platformdb.tibero.TiberoSqlExceptionTranslator;
import java.io.IOException;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.JdbcType;
import org.mybatis.spring.MyBatisExceptionTranslator;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 플랫폼 DB 용 MyBatis 배선(WD-487 PoC). {@link PlatformMapper} 매퍼만 플랫폼 {@code @Primary} DataSource 에
 * 묶는다.
 *
 * <p>왜 직접 배선하나: mybatis-spring-boot-starter 는 DataSource 가 여럿일 때 자동 구성이 잘못 붙을 수 있고, Tibero 테스트 하네스가
 * 같은 설정으로 팩토리를 만들어야 해서 {@link #sqlSessionFactory(DataSource)} 를 정적으로 둔다. 트랜잭션은 앱 기본 트랜잭션 관리자(같은
 * DataSource)가 쥐고 SqlSessionTemplate 이 그 커넥션에 참여한다.
 *
 * <p>왜 기본 꺼짐: {@link MyBatisPlatformCondition} — {@code
 * firehub.platform-db.mybatis.enabled=true}(테스트 프로필) 또는 {@code firehub.platform-db.type=tibero} 일
 * 때만 켜진다. PG 운영 컨텍스트에는 매퍼 스캔·XML 파싱·기동 시 DB 연결·구현 빈이 하나도 생기지 않아야 한다. 구현 빈은 {@link
 * PlatformMyBatisRepositoryRegistrar} 가 {@link PlatformMyBatisRepository} 를 스캔해 등록한다.
 *
 * <p>매퍼 XML 공통 규칙(각 XML 머리말은 여기를 가리킨다):
 *
 * <ul>
 *   <li>databaseId: 벤더 공통 문장은 databaseId 를 달지 않고, PG 전용 문법이 든 문장·조각만 {@code
 *       databaseId="postgresql"}, Tibero 판은 같은 id 로 {@code databaseId="tibero"} 에 둔다. MyBatis 는 현재
 *       databaseId 판을 고르고 없으면 공통 판을 쓴다. 리터럴·행 제한만 다른 문장은 한 본문에 공용 조각({@code
 *       mybatis/common/PlatformSql.xml})을 끼워 쓴다(PG 렌더 결과는 공백만 다르다).
 *   <li>tenant_id: 두 DB 모두 컬럼 DEFAULT(PG GUC {@code app.tenant_id}, Tibero 컨텍스트 — WD-492)가 채우고
 *       RLS(PG 정책, Tibero DBMS_RLS)가 읽기·쓰기를 거르므로 문장에 INSERT 값·WHERE 술어로 쓰지 않는다(jOOQ 와 같음, WD-706).
 *       그래서 테넌트 없는 경로(로그인·플랫폼 관리 등)에서도 문장이 {@code TenantContext} 를 요구하지 않는다. 예외는 인터페이스 계약이 테넌트를 인자로
 *       받는 문장(claimDue·countPendingByChannel)뿐이고 그 인자를 그대로 쓴다. 테넌트를 가로지르는 조회는 정책을 넘는 함수(PG SECURITY
 *       DEFINER, Tibero {@code FH_TENANT} 패키지)를 부른다.
 *   <li>null 바인드: Tibero 팩토리 전역 {@code jdbcTypeForNull=VARCHAR}(아래 {@code applyTiberoSettings}) 라
 *       Tibero 문장에는 null 때문에 {@code jdbcType} 을 달지 않는다.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@Conditional(MyBatisPlatformCondition.class)
@Import(PlatformMyBatisRepositoryRegistrar.class)
@MapperScan(
    basePackages = "com.smartfirehub",
    annotationClass = PlatformMapper.class,
    sqlSessionTemplateRef = "platformSqlSessionTemplate")
public class MyBatisPlatformConfig {

  /** MyBatis 배선을 켜는 속성 이름. 없거나 true 가 아니면 이 설정 전체가 빠진다. */
  public static final String ENABLED_PROPERTY = "firehub.platform-db.mybatis.enabled";

  /** Tibero 의 databaseId. 벤더별 전역 설정(null 바인드 타입·타입 핸들러)을 이 값으로 가른다. */
  static final String TIBERO = "tibero";

  /** 매퍼 XML 위치. */
  static final String MAPPER_LOCATIONS = "classpath*:mybatis/**/*.xml";

  /**
   * 설정을 적용한 SqlSessionFactory 를 만든다. 스프링 빈이 아니라도 쓸 수 있게 정적으로 둔다(Tibero 하네스 공유).
   *
   * @param dataSource 팩토리가 묶일 DataSource
   * @return databaseId 판별·매퍼 XML·snake_case→camelCase 매핑이 설정된 팩토리
   */
  public static SqlSessionFactory sqlSessionFactory(DataSource dataSource) {
    try {
      // databaseId 를 XML 파싱 전에 먼저 정한다 — resultMap·파라미터의 타입 핸들러는 파싱 시점에 레지스트리에서 해소되므로, 벤더별 핸들러를
      // 등록하려면 파싱 전에 벤더를 알아야 한다. 팩토리 빈에는 provider 를 넘기지 않아 DB 연결을 두 번 열지 않는다.
      String databaseId = databaseIdProvider().getDatabaseId(dataSource);
      Resource[] mapperXml =
          new PathMatchingResourcePatternResolver().getResources(MAPPER_LOCATIONS);
      return sqlSessionFactory(dataSource, databaseId, mapperXml);
    } catch (IOException e) {
      throw new IllegalStateException("MyBatis SqlSessionFactory 구성 실패", e);
    }
  }

  /**
   * databaseId 와 매퍼 XML 을 정해 놓고 팩토리를 만든다 — 운영 빈과 매퍼 로드 시험(WD-731 {@code PlatformMapperLoadTest})이
   * 함께 쓰는 유일한 조립 경로.
   *
   * <p>왜 따로 뽑나: 시험이 XML 파싱을 손으로 흉내 내면 운영 설정(타입 핸들러·인터셉터·팩토리 빈 옵션)이 늘 때 두 경로가 갈라진다. 이 메서드는 DB 에 연결하지
   * 않으므로(databaseId provider 를 팩토리 빈에 넘기지 않는다) 시험은 연결 없는 DataSource 로 같은 팩토리를 만든다.
   *
   * @param dataSource 팩토리가 묶일 DataSource(여기서는 연결을 열지 않는다)
   * @param databaseId {@code postgresql}·{@code tibero}. 매핑에 없는 제품이면 null(공통 문장만 쓴다)
   * @param mapperXml 파싱할 매퍼 XML
   */
  static SqlSessionFactory sqlSessionFactory(
      DataSource dataSource, String databaseId, Resource[] mapperXml) {
    try {
      SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
      bean.setDataSource(dataSource);
      bean.setMapperLocations(mapperXml);
      org.apache.ibatis.session.Configuration configuration =
          new org.apache.ibatis.session.Configuration();
      configuration.setDatabaseId(databaseId);
      applyPlatformSettings(configuration, databaseId);
      bean.setConfiguration(configuration);
      return bean.getObject();
    } catch (Exception e) {
      throw new IllegalStateException("MyBatis SqlSessionFactory 구성 실패", e);
    }
  }

  /**
   * 전역 설정. XML 파싱 전에 불러야 한다 — 벤더별 타입 핸들러는 파싱 시점에 해소되기 때문이다. 벤더를 설정 객체에서 읽지 않고 인자로 받아, {@code
   * setDatabaseId} 전에 불려 Tibero 설정이 조용히 빠지는 일을 막는다.
   *
   * @param databaseId tibero 면 Tibero 전용 설정을 더한다
   */
  private static void applyPlatformSettings(
      org.apache.ibatis.session.Configuration configuration, String databaseId) {
    // 컬럼 snake_case 를 필드 camelCase 로 자동 매핑해 resultMap 보일러플레이트를 줄인다.
    configuration.setMapUnderscoreToCamelCase(true);
    // 매퍼 파라미터 queryTimeoutSeconds 가 있는 문장에만 JDBC 문장 시간 제한을 건다(두 DB 공통, WD-707).
    configuration.addInterceptor(new StatementTimeoutInterceptor());
    if (TIBERO.equals(databaseId)) {
      applyTiberoSettings(configuration);
    }
  }

  /**
   * Tibero 팩토리 전용 전역 설정. PG 팩토리는 MyBatis 기본값 그대로다(null 바인드 OTHER, 기본 Instant 핸들러).
   *
   * <ul>
   *   <li>null 바인드 타입 VARCHAR: MyBatis 기본 {@code jdbcTypeForNull=OTHER} 는 Tibero 드라이버가 거절한다({@code
   *       setNull(Types.OTHER)} → JDBC-590704, 실측). VARCHAR 는 실측으로 동작하고, Tibero 가 대상 컬럼 타입으로 암묵
   *       변환한다. 문장마다 {@code jdbcType} 을 다는 대신 여기서 한 번에 정한다.
   *   <li>{@link Instant} → {@link UtcInstantTypeHandler}: Tibero 플랫폼 테이블은 timestamptz 대신 UTC 벽시계
   *       TIMESTAMP 라, 기본 핸들러(JVM 시간대로 해석)로 읽으면 KST JVM 에서 9시간 어긋난다(실측). 전역 등록이라 resultMap 을 벤더별로 둘
   *       필요가 없다.
   *   <li>{@link UUID} → {@link UuidStringTypeHandler}: UUID 컬럼이 VARCHAR2(36) 이고 드라이버가 {@code
   *       setObject(UUID)} 를 지원하지 않는다(실측). PG 는 등록하지 않아 {@code jdbcType=OTHER} 경로 그대로다.
   * </ul>
   */
  private static void applyTiberoSettings(org.apache.ibatis.session.Configuration configuration) {
    configuration.setJdbcTypeForNull(JdbcType.VARCHAR);
    configuration.getTypeHandlerRegistry().register(Instant.class, new UtcInstantTypeHandler());
    configuration.getTypeHandlerRegistry().register(UUID.class, new UuidStringTypeHandler());
  }

  /** JDBC 제품명 → databaseId. Tibero JDBC 는 제품명을 "Tibero" 로 보고한다. 매핑에 없는 제품은 databaseId 가 null 이다. */
  static VendorDatabaseIdProvider databaseIdProvider() {
    Properties vendors = new Properties();
    vendors.setProperty("PostgreSQL", "postgresql");
    vendors.setProperty("Tibero", TIBERO);
    VendorDatabaseIdProvider provider = new VendorDatabaseIdProvider();
    provider.setProperties(vendors);
    return provider;
  }

  /** 플랫폼 DataSource 에 묶인 팩토리. DataSource 가 여럿이라 {@code @Primary} 빈으로 해소된다. */
  @Bean
  public SqlSessionFactory platformSqlSessionFactory(DataSource dataSource) {
    return sqlSessionFactory(dataSource);
  }

  /** 매퍼가 쓰는 스레드 안전 세션 템플릿. 진행 중인 스프링 트랜잭션의 커넥션에 참여한다. */
  @Bean
  public SqlSessionTemplate platformSqlSessionTemplate(
      SqlSessionFactory platformSqlSessionFactory) {
    return sqlSessionTemplate(platformSqlSessionFactory);
  }

  /**
   * 세션 템플릿 — Tibero 면 예외 번역기를 Tibero 오류 코드 표({@link TiberoSqlExceptionTranslator})로 바꾼다(WD-496).
   *
   * <p>기본 번역기는 DataSource 제품명으로 스프링 오류 코드 표를 찾는데 "Tibero" 는 표에 없어, 유일 위반이 {@code
   * DuplicateKeyException} 이 아니라 일반 무결성 위반으로 번역된다. PostgreSQL 은 기본 번역기 그대로다. 테스트 하네스도 같은 템플릿을 쓰도록
   * 공개한다.
   */
  public static SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
    if (!TIBERO.equals(factory.getConfiguration().getDatabaseId())) {
      return new SqlSessionTemplate(factory);
    }
    return new SqlSessionTemplate(
        factory,
        factory.getConfiguration().getDefaultExecutorType(),
        new MyBatisExceptionTranslator(TiberoSqlExceptionTranslator::create, true));
  }
}
