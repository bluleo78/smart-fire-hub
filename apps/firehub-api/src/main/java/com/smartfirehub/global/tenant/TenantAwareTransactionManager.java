package com.smartfirehub.global.tenant;

import com.smartfirehub.global.platformdb.PlatformTenantSession;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.JdbcTransactionObjectSupport;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션 시작 직후 활성 테넌트를 RLS GUC {@code app.tenant_id} 로 주입하는 트랜잭션 매니저.
 *
 * <p>세 번째 인자 {@code true} 는 <b>트랜잭션-로컬</b>을 뜻한다. 커밋/롤백 시 값이 자동으로 사라지므로 HikariCP 커넥션이 풀에 반납된 뒤 다음
 * 요청이 이전 테넌트를 물려받는 사고가 원천적으로 불가능하다. 세션 스코프({@code false})로 심으면 그 사고가 실제로 발생한다.
 *
 * <p><b>세션 범위 값(Tibero 컨텍스트, WD-492)</b>의 계약은 {@link PlatformTenantSession} 에 있다. 이 매니저 쪽 몫은 {@link
 * #doCleanupAfterCompletion} 에서 반납 직전에 지우고, 실패하면 {@link PooledConnectionDiscard} 로 버리는 것이다.
 *
 * <p>{@link TenantContext} 가 비어 있으면 GUC 를 설정하지 않는다. 그 결과 RLS 정책의 비교가 NULL 이 되어 모든 행이 보이지
 * 않는다(fail-closed) — 테넌트를 모른 채 데이터가 노출되는 것보다 안전한 기본값이다. 이때 시작 시점에 {@link
 * PlatformTenantSession#clearConnection} 을 한 번 부른다(WD-492) — 앞선 대여자의 끝 지우기와 버리기가 모두 실패해 세션 값이 남은
 * 커넥션이 풀로 돌아왔더라도 테넌트 없는 트랜잭션이 그 값을 물려받지 않게 하려는 것이다. PG 는 빈 기본 구현이라 동작이 같다.
 *
 * <p><a id="repo-transactional"></a><b>리포지토리에 클래스 레벨 {@code @Transactional} 이 왜 필요한가 — 채널 도메인 리포지토리
 * 5개가 공유하는 근거.</b> 위에 적은 대로 GUC 는 {@code doBegin} 이 <b>트랜잭션이 열리는 순간에만</b> 주입한다. 그 리포지토리들을 부르는
 * 경로(스케줄러·LISTEN 스레드·메트릭 갱신 등)에는 앰비언트 트랜잭션이 없으므로, 리포지토리가 스스로 열지 않으면 GUC 가 비고 그 결과 <b>쓰기는 죽고</b>
 * <b>정책이 켜지면(V107) 읽기는 예외도 로그도 없이 0행</b>이 된다. 인터페이스가 아니라 impl 클래스에 붙이는 것은 프록시 대상 클래스에서 검사되기 때문이다.
 *
 * <p><b>"쓰기가 죽는" SQLState 는 테이블에 따라 다르다 — {@code 23502} 하나로 적어 두면 진단이 어긋난다.</b> 이전 서술이 {@code
 * 23502}(NOT NULL 위반)만 적고 있었으나 그것은 <b>정책이 그 INSERT 를 통과시키는 테이블에서만</b> 맞다. 대상 테이블에 {@code WITH
 * CHECK} 가 걸려 있으면 NOT NULL 검사보다 정책이 <b>먼저</b> 걸려 {@code 42501}(insufficient_privilege)이 난다 — P2-g
 * 에서 {@code slack_workspace} 로 실측했다({@code app_tenant} 롤 + GUC 미설정 → {@code 42501: new row violates
 * row-level security policy}). 즉 <b>{@code 42501} 을 보고 "권한 문제"라고 단정하지 말 것</b>: 컨텍스트 부재(배선 결함)와 타
 * 테넌트 행 침범(권한 거부)이 같은 SQLState 를 낸다. 둘을 SQLState 로는 가를 수 없으므로, 42501 을 4xx 로 바꾸는 코드는 반드시 "호출자가
 * 컨텍스트를 세웠다"는 전제 위에서만 좁게 써야 한다 ({@code SlackOAuthService.upsertOrDeny} 가 그 예이며 근거를 그 javadoc 에 적어
 * 두었다).
 *
 * <p><b>다만 그 애노테이션은 필요조건일 뿐 충분조건이 아니다.</b> {@code doBegin} 은 {@link TenantContext} 가 비어 있으면 GUC 를
 * <b>아예 심지 않는다</b>(세션 범위 값을 지우기만 하고 예외 없이 시작한다). 배경 스레드에는 그 컨텍스트가 없으므로 <b>컨텍스트 공급은 호출자(배경 경로) 책임이며
 * P2-f Task 3 이 배선한다.</b> 애노테이션이 붙었다고 배경 경로가 고쳐진 것이 아니다 — V107 이후 0행을 조사한다면 애노테이션이 아니라 호출자의 컨텍스트를
 * 먼저 볼 것.
 *
 * <p>이 문단이 여기 있는 이유: 같은 근거가 리포지토리 5개에 통째로 복붙돼 있었고, 그중 하나만 갱신되는 드리프트가 실제로 반복됐다. 각 impl 은 이 문단을
 * {@code {@link}} 로 가리키고 자기 지점에만 해당하는 사실만 따로 적는다.
 *
 * <p>{@code DataSourceTransactionManager} 가 아니라 {@link JdbcTransactionManager} 를 상속한다. Boot 3.4.1 의
 * {@code DataSourceTransactionManagerAutoConfiguration} 이 기본으로 등록하는 트랜잭션 매니저도 {@code
 * JdbcTransactionManager} 이며, 이 서브클래스는 커밋/롤백 시 {@code SQLException} 을 {@code DataAccessException}
 * 으로 변환하는 기능을 추가로 갖는다. 이 클래스가 {@code @Primary} 로 Boot 의 빈을 대체하는데, 그냥 {@code
 * DataSourceTransactionManager} 를 상속하면 그 변환 기능이 조용히 사라진다. {@code JdbcTransactionManager} 는 {@code
 * DataSourceTransactionManager} 의 서브클래스이므로 {@code doBegin}, {@code obtainDataSource()}, 생성자 시그니처는
 * 그대로 쓸 수 있다.
 */
public class TenantAwareTransactionManager extends JdbcTransactionManager {

  private static final Logger log = LoggerFactory.getLogger(TenantAwareTransactionManager.class);

  /** 테넌트를 DB 세션에 심는 포트 — DB 별 SQL 은 구현체가 안다(WD-486). */
  private final PlatformTenantSession tenantSession;

  /**
   * 커밋을 시도한 트랜잭션 객체(WD-727) — {@link #doRollback} 이 롤백 실패를 삼켜도 되는지 가르는 표시. 트랜잭션 객체마다 하나라 다른 트랜잭션으로
   * 새지 않는다. 정리({@link #doCleanupAfterCompletion})에서 지우고, 혹시 정리를 거치지 않아도 약한 참조라 쌓이지 않는다. 트랜잭션 객체는
   * equals 를 재정의하지 않아 동일성으로 비교된다.
   */
  private final Set<Object> commitAttempted =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

  public TenantAwareTransactionManager(DataSource dataSource, PlatformTenantSession tenantSession) {
    super(dataSource);
    this.tenantSession = tenantSession;
  }

  @Override
  protected void doBegin(Object transaction, TransactionDefinition definition) {
    super.doBegin(transaction, definition);

    Long tenantId = TenantContext.get();
    // super.doBegin 이 커넥션을 트랜잭션에 바인딩한 뒤이므로, 여기서 얻는 커넥션은 그 트랜잭션의 것이다.
    Connection connection = DataSourceUtils.getConnection(obtainDataSource());
    try {
      if (tenantId == null) {
        // 테넌트 없는 트랜잭션도 fail-closed 여야 한다 — 앞선 대여자의 끝 지우기·버리기가 모두 실패해 세션 값(Tibero 컨텍스트)이
        // 남은 커넥션이 왔을 수 있으므로 시작에 한 번 더 지운다(WD-492). PG 는 빈 기본 구현이라 아무 일도 하지 않는다.
        tenantSession.clearConnection(connection);
      } else {
        tenantSession.applyToConnection(connection, tenantId);
      }
    } catch (SQLException e) {
      // GUC 주입 실패를 조용히 넘기면 RLS 가 fail-closed 로 빈 결과를 주어 원인 추적이 어려워진다.
      // 다만 이 예외는 super.doBegin() 이 "성공적으로 끝난 뒤"에 발생한다 — 즉 커넥션 홀더가 이미
      // TransactionSynchronizationManager 에 스레드-바인딩된 상태다. AbstractPlatformTransactionManager
      // 는 doBegin 에서 던져진 예외를 doCleanupAfterCompletion 없이 그냥 전파하므로(그 훅은 커밋/롤백
      // 경로에만 연결되어 있다), 여기서 직접 정리하지 않으면 바인딩이 스레드에 남아 커넥션이 Hikari 로
      // 반납되지 않는다. 그러면 같은 스레드의 다음 @Transactional 호출은 "기존 트랜잭션에 참여"로
      // 오인되어 doBegin 이 다시 실행되지 않고, GUC 도 주입되지 않은 채 죽은 커넥션을 계속 쓰게 되어
      // 풀이 고갈된다. DataSourceTransactionManager 자신이 doBegin 실패 시 수행하는 정리와 동일한
      // 작업(언바인딩 + 커넥션 반납)을 doCleanupAfterCompletion 을 재사용해 수행한 뒤 예외를 던진다.
      // 그 재정의가 반납 전에 테넌트 값도 지우므로(실패하면 버림) 반쯤 심긴 세션 값도 풀로 돌아가지 않는다(WD-492).
      // 시작 지우기가 실패한 경우도 같은 길이다 — 값이 남았을 수 있는 커넥션으로 트랜잭션을 열지 않는다.
      doCleanupAfterCompletion(transaction);
      throw new CannotCreateTransactionException(
          tenantId == null ? "테넌트 세션 값 지우기 실패" : "app.tenant_id GUC 설정 실패", e);
    }
  }

  /** 커밋을 보내기 전에 이 트랜잭션에 「커밋 시도」 표시를 한다(WD-727 리뷰 I1) — {@link #doRollback} 참고. */
  @Override
  protected void doCommit(DefaultTransactionStatus status) {
    commitAttempted.add(status.getTransaction());
    super.doCommit(status);
  }

  /**
   * 롤백하되, 커밋에 이르지 못한 작업의 연결이 이미 닫혀 롤백하지 못한 경우는 경고만 남기고 넘어간다(WD-727).
   *
   * <p>왜: Tibero 드라이버는 queryTimeout 취소를 {@code SQLTimeoutException}(-12040)으로 보내고, HikariCP 5 는 이
   * 예외를 받은 연결을 고장으로 보고 닫는다(풀에서 빼고 실제 연결을 닫음). 그 뒤의 롤백은 「Connection is closed」로 실패하고, 스프링은 그 실패를
   * {@code TransactionSystemException} 으로 던지면서 원래 예외(시간 초과)를 {@code getApplicationException()} 에만
   * 남긴다. 원인 사슬만 보는 호출자(배경 작업·마감 판정·{@code PythonReadGrantBackend.isTransient} 등)와 오류 응답이 모두 원인을
   * 잃었다. 닫힌 연결에서는 어차피 롤백할 수 없고, 커밋되지 않은 작업은 연결이 닫히며 서버가 버린다(실서버 확인: TiberoQueryTimeoutLiveTest — 취소
   * 전에 바꾼 행이 커밋되지 않았다). 그래서 이 경우의 롤백 실패를 삼켜 원래 예외가 그대로 전파되게 한다.
   *
   * <p><b>롤백 경로에서만 삼킨다.</b> 커밋을 시도한 트랜잭션({@link #doCommit} 표시)은 커밋 실패 뒤의 롤백(스프링 {@code
   * doRollbackOnCommitException})이라도 삼키지 않는다 — 커밋 요청이 서버에 닿았는지 알 수 없으므로 스프링이 결과를 「알 수 없음」({@code
   * STATUS_UNKNOWN})으로 알리게 둬야 한다. 삼키면 「롤백됨」으로 알려져 afterCompletion 보상 처리(외부 계정·테이블 DROP)가 이미 커밋됐을 수
   * 있는 작업을 되돌릴 수 없게 지운다.
   *
   * <p>연결이 열려 있는데 롤백이 실패한 경우도 지금과 같다(예외 그대로) — 서버 상태를 모르는 실패라 숨기지 않는다. PG 는 문장 시간 초과가 SQLState
   * {@code 57014} 의 일반 예외라 풀이 연결을 닫지 않으므로 이 분기에 오지 않는다. 연결이 정말 끊긴 드문 경우(네트워크 단절 등)에는 PG 도 같은 규칙을
   * 따르며, 그때도 커밋되지 않은 작업은 서버가 버린다.
   */
  @Override
  protected void doRollback(DefaultTransactionStatus status) {
    try {
      super.doRollback(status);
    } catch (RuntimeException e) {
      if (commitAttempted.contains(status.getTransaction()) || !isConnectionClosed(status)) {
        throw e;
      }
      log.warn("커밋 전에 실패한 트랜잭션의 연결이 이미 닫혀 롤백을 보내지 못했다 — 커밋되지 않은 작업은 서버가 버렸으므로 원래 예외를 그대로 전파한다", e);
    }
  }

  /** 이 트랜잭션의 연결이 이미 닫혔는지. 확인 자체가 실패하면 닫히지 않은 것으로 본다(지금과 같은 예외 경로로 간다). */
  private static boolean isConnectionClosed(DefaultTransactionStatus status) {
    if (!(status.getTransaction() instanceof JdbcTransactionObjectSupport tx)
        || !tx.hasConnectionHolder()) {
      return false;
    }
    try {
      return tx.getConnectionHolder().getConnection().isClosed();
    } catch (SQLException | RuntimeException e) {
      return false;
    }
  }

  /**
   * 커넥션을 풀에 돌려주기 직전에 세션에 심은 테넌트 값을 지운다(WD-492).
   *
   * <p>왜: {@link PlatformTenantSession} 계약 — 세션 범위 값은 풀로 돌아가기 전에 사라져야 한다.
   *
   * <p>{@code AbstractPlatformTransactionManager} 는 이 메서드를 <b>새 트랜잭션</b>이 끝날 때만 부른다 — 바깥 트랜잭션에 참여한
   * 안쪽 트랜잭션(REQUIRED)은 부르지 않으므로 바깥 컨텍스트를 건드리지 않는다. {@code REQUIRES_NEW} 는 자기 커넥션을 따로 받으므로 자기 커넥션만
   * 지우고, 바깥 트랜잭션의 커넥션 컨텍스트는 그대로 남는다.
   *
   * <p>커넥션은 {@code super} 를 부르기 전에 {@link TransactionSynchronizationManager} 에 묶인 {@link
   * ConnectionHolder} 에서 꺼낸다 — {@code DataSourceTransactionManager} 의 트랜잭션 객체 클래스는 비공개이고, {@code
   * super} 가 그 묶음을 풀고 커넥션을 반납하기 때문이다.
   *
   * <p>지우기에 실패하면 경고를 남기고 그 커넥션을 풀에서 버린다({@link PooledConnectionDiscard}). 예외는 던지지 않는다 — 커밋은 이미
   * 끝났으므로 호출자에게 실패를 돌려주면 「커밋됐는데 예외」가 된다.
   */
  @Override
  protected void doCleanupAfterCompletion(Object transaction) {
    DataSource dataSource = obtainDataSource();
    Object resource = TransactionSynchronizationManager.getResource(dataSource);
    if (resource instanceof ConnectionHolder holder) {
      Connection connection = null;
      try {
        connection = holder.getConnection();
        tenantSession.clearConnection(connection);
      } catch (SQLException | RuntimeException e) {
        log.warn("트랜잭션 끝에 테넌트 세션 값을 지우지 못해 커넥션을 풀에서 버린다", e);
        if (connection != null) {
          PooledConnectionDiscard.discard(dataSource, connection);
        }
      }
    }
    commitAttempted.remove(transaction);
    super.doCleanupAfterCompletion(transaction);
  }
}
