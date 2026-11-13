package com.smartfirehub.support.tibero;

import com.smartfirehub.global.platformdb.mybatis.MyBatisPlatformConfig;
import com.smartfirehub.global.platformdb.tibero.TiberoPlatformTenantSession;
import com.smartfirehub.global.tenant.TenantAwareTransactionManager;
import com.smartfirehub.global.tenant.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.PreparedStatement;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 플랫폼 리포지토리 MyBatis 구현을 Tibero 실서버에서 돌리는 계약 테스트 하네스(WD-487 PoC → WD-497 통합 → WD-706 두 계정·RLS).
 *
 * <p>무엇을: 운영과 같은 두 계정 구조({@link TiberoTenantRlsSchema} — 소유자에 {@code db/migration-tibero} 전부 적용,
 * 런타임 계정은 {@code CURRENT_SCHEMA = 소유자})를 그대로 쓴다.
 *
 * <ul>
 *   <li>리포지토리 쪽({@link #dataSource()}·{@link #tx()}·{@link #mapper}·{@link #transactional}) —
 *       <b>런타임 계정</b> 풀(크기 2) 위 {@link TenantAwareTransactionManager} + {@link
 *       TiberoPlatformTenantSession}. 운영과 같은 길로 트랜잭션 시작에 {@code FH_TENANT.set_tenant}, 끝에 {@code
 *       clear_tenant} 가 돌고, DBMS_RLS 정책이 다른 테넌트 행을 거른다.
 *   <li>픽스처 쪽({@link #jdbc()}·{@link #insertReturningId}) — <b>소유자 계정</b>(자동 커밋). 정책 함수가 소유자 세션은
 *       거르지 않으므로 테넌트 없이도 어느 테넌트 행이든 넣고 지운다. 런타임으로 정리하면 테넌트가 없을 때 DELETE 가 0행이 되어 공유 서버에 행이 쌓인다.
 * </ul>
 *
 * <p>JVM 당 하나({@link #shared()}): 기준선 마이그레이션이 2~3분 걸려 테스트 클래스마다 다시 만들지 않는다. 계정은 RLS 하네스가 만들고 종료 훅에서
 * 지운다 — 이 하네스는 계정을 만들지 않고 런타임 풀 하나만 더 연다(세션 예산: RLS 하네스 소유자 3 + 런타임 2 + 기본 1, 여기 런타임 2 = JVM 당 8).
 * RLS 하네스의 공용 런타임 풀을 같이 쓰지 않는 이유: 크기 2 를 계약 테스트의 {@code REQUIRES_NEW}·동시성 시험과 나누면 서로 굶길 수 있다. 각 테스트
 * 클래스는 자기가 넣은 행을 스스로 지운다(다음 클래스가 같은 스키마를 본다).
 */
public final class TiberoPlatformSchema {

  /** JVM 당 하나인 하네스. 준비에 실패하면 남기지 않아 다음 호출이 다시 시도한다. */
  private static TiberoPlatformSchema shared;

  private final TiberoTenantRlsSchema rls;
  private final HikariDataSource runtimePool;
  private final SqlSessionTemplate sqlSessionTemplate;
  private final TenantAwareTransactionManager transactionManager;
  private final TransactionTemplate transactionTemplate;
  private final JdbcTemplate ownerJdbc;

  private TiberoPlatformSchema(TiberoTenantRlsSchema rls, HikariDataSource runtimePool) {
    this.rls = rls;
    this.runtimePool = runtimePool;
    // 팩토리·트랜잭션 관리자·테넌트 세션이 같은 DataSource 인스턴스를 써야 SqlSessionTemplate 이 트랜잭션 커넥션(컨텍스트가 심긴
    // 세션)에 참여한다.
    this.sqlSessionTemplate =
        MyBatisPlatformConfig.sqlSessionTemplate(
            MyBatisPlatformConfig.sqlSessionFactory(runtimePool));
    this.transactionManager =
        new TenantAwareTransactionManager(
            runtimePool, new TiberoPlatformTenantSession(runtimePool));
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.ownerJdbc = new JdbcTemplate(rls.owner().dataSource());
  }

  /**
   * JVM 공용 하네스를 돌려준다. 첫 호출이 RLS 하네스(두 계정 + migrate)를 준비하고 런타임 풀을 연다. 드라이버 jar 가 없으면 테스트를 건너뛴다. 풀은
   * 런타임 계정이 지워지기 전에 RLS 하네스 종료 훅이 닫지만({@link TiberoTestAccount#newPool}), 순서에 기대지 않도록 직접 닫는 훅도 하나
   * 둔다(두 번 닫아도 무해).
   */
  public static synchronized TiberoPlatformSchema shared() {
    TiberoTestServer.assumeDriverPresent();
    if (shared == null) {
      TiberoTenantRlsSchema rls = TiberoTenantRlsSchema.shared();
      HikariDataSource pool = rls.newRuntimePool(2);
      try {
        shared = new TiberoPlatformSchema(rls, pool);
      } catch (RuntimeException e) {
        pool.close();
        throw e;
      }
      Runtime.getRuntime()
          .addShutdownHook(new Thread(pool::close, "tibero-platform-schema-pool-close"));
    }
    return shared;
  }

  /** 소유자 계정(= 스키마) 이름 — 실행마다 다르다. */
  public String ownerName() {
    return rls.owner().name();
  }

  /** 런타임 계정 이름 — 리포지토리 쪽 세션의 {@code USER}. */
  public String runtimeName() {
    return rls.runtime().name();
  }

  /** 무작위 테넌트 id 가 겹쳐 삽입이 중복 키로 실패할 때 새 난수로 다시 시도하는 최대 횟수. */
  private static final int TENANT_ID_ATTEMPTS = 5;

  /**
   * 무작위 id(9억+난수) 로 테넌트 한 행을 넣고 id 를 돌려준다(소유자 계정, 픽스처용). slug·name 은 {@code <접두>-<id>}.
   *
   * <p>왜 재시도하는가(WD-736): 공유 Tibero 에는 다른 실행이 남긴 행이 있을 수 있어 난수가 겹치면 중복 키로 시험이 흔들린다. 삽입이 중복 키로 실패하면 새
   * 난수로 최대 {@value #TENANT_ID_ATTEMPTS}회 다시 시도한다. 다른 무결성 오류는 재시도하지 않고 바로 터뜨린다.
   */
  public long insertTenantWithRandomId(String slugPrefix) {
    DuplicateKeyException last = null;
    for (int attempt = 0; attempt < TENANT_ID_ATTEMPTS; attempt++) {
      long id = 900_000_000L + ThreadLocalRandom.current().nextLong(1_000_000L);
      try {
        ownerJdbc.update(
            "INSERT INTO tenant (id, slug, name, status) VALUES (?, ?, ?, 'ACTIVE')",
            id,
            slugPrefix + "-" + id,
            slugPrefix + "-" + id);
        return id;
      } catch (DuplicateKeyException e) {
        last = e; // 다른 실행이 같은 id 를 먼저 넣었다 — 새 난수로 다시.
      }
    }
    throw new IllegalStateException("겹치지 않는 테넌트 id 를 " + TENANT_ID_ATTEMPTS + "번 안에 못 찾았다", last);
  }

  /** 소유자 계정의 JdbcTemplate(자동 커밋, 정책 우회) — 픽스처·정리 전용. 리포지토리 동작 확인에 쓰면 격리 시험이 공허해진다. */
  public JdbcTemplate jdbc() {
    return ownerJdbc;
  }

  /**
   * IDENTITY 컬럼 테이블에 한 행을 넣고 생성된 id 를 돌려준다(소유자 계정, 픽스처용). Tibero 에는 RETURNING 이 없어 JDBC 생성 키(컬럼 이름
   * "id" 지정)로 받는다 — 컬럼 이름 없이 RETURN_GENERATED_KEYS 로 받으면 ROWID 문자열이 온다(실측).
   */
  public long insertReturningId(String sql, Object... args) {
    GeneratedKeyHolder keys = new GeneratedKeyHolder();
    ownerJdbc.update(
        con -> {
          PreparedStatement ps = con.prepareStatement(sql, new String[] {"id"});
          for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
          return ps;
        },
        keys);
    Number key = keys.getKey();
    if (key == null) throw new IllegalStateException("생성 키 없음: " + sql);
    return key.longValue();
  }

  /** 런타임 계정 풀(크기 2, {@code CURRENT_SCHEMA = 소유자}). 원시 JDBC 로 빌린 커넥션은 반드시 닫는다. */
  public DataSource dataSource() {
    return runtimePool;
  }

  /**
   * 런타임 풀 위 {@link TenantAwareTransactionManager} 트랜잭션 템플릿. 테넌트는 <b>시작할 때</b> {@link TenantContext}
   * 에서 읽으므로 {@code TenantContext.runScopedGet(t, () -> tx().execute(...))} 순서로 쓴다(안쪽에서 세우면 테넌트 없는
   * 트랜잭션이 된다).
   */
  public TransactionTemplate tx() {
    return transactionTemplate;
  }

  /** 런타임 풀 팩토리에 묶인 매퍼(스프링 트랜잭션에 참여하는 SqlSessionTemplate 경유). */
  public <M> M mapper(Class<M> type) {
    return sqlSessionTemplate.getMapper(type);
  }

  /**
   * 구현 객체를 {@link TenantAwareTransactionManager} 로 감싼 인터페이스 프록시를 만든다.
   *
   * <p>앱 컨테이너에서 {@code @Transactional} 프록시가 하는 일을 그대로 흉내 낸다 — 같은 {@link
   * AnnotationTransactionAttributeSource} 가 구현 클래스의 클래스·메서드 애너테이션(REQUIRED, {@code NOT_SUPPORTED}
   * 등)을 읽으므로 전파 의미가 앱과 같고, 새 트랜잭션은 {@link TenantContext} 가 있으면 {@code set_tenant}, 끝나면 {@code
   * clear_tenant} 를 운영과 같은 매니저로 거친다.
   */
  public <T> T transactional(Class<T> iface, T target) {
    ProxyFactory factory = new ProxyFactory();
    factory.setTarget(target);
    factory.addInterface(iface);
    factory.addAdvice(
        new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
    return iface.cast(factory.getProxy(TiberoPlatformSchema.class.getClassLoader()));
  }
}
