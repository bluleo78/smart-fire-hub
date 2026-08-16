package com.smartfirehub.global.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;

/**
 * 트랜잭션 시작 직후 활성 테넌트를 RLS GUC {@code app.tenant_id} 로 주입하는 트랜잭션 매니저.
 *
 * <p>세 번째 인자 {@code true} 는 <b>트랜잭션-로컬</b>을 뜻한다. 커밋/롤백 시 값이 자동으로 사라지므로
 * HikariCP 커넥션이 풀에 반납된 뒤 다음 요청이 이전 테넌트를 물려받는 사고가 원천적으로 불가능하다.
 * 세션 스코프({@code false})로 심으면 그 사고가 실제로 발생한다.
 *
 * <p>{@link TenantContext} 가 비어 있으면 GUC 를 설정하지 않는다. 그 결과 RLS 정책의 비교가 NULL 이 되어
 * 모든 행이 보이지 않는다(fail-closed) — 테넌트를 모른 채 데이터가 노출되는 것보다 안전한 기본값이다.
 *
 * <p><a id="repo-transactional"></a><b>리포지토리에 클래스 레벨 {@code @Transactional} 이 왜
 * 필요한가 — 채널 도메인 리포지토리 5개가 공유하는 근거.</b> 위에 적은 대로 GUC 는 {@code doBegin}
 * 이 <b>트랜잭션이 열리는 순간에만</b> 주입한다. 그 리포지토리들을 부르는 경로(스케줄러·LISTEN
 * 스레드·메트릭 갱신 등)에는 앰비언트 트랜잭션이 없으므로, 리포지토리가 스스로 열지 않으면 GUC 가
 * 비고 그 결과 <b>쓰기는 죽고</b> <b>정책이 켜지면(V107) 읽기는 예외도 로그도 없이 0행</b>이 된다.
 * 인터페이스가 아니라 impl 클래스에 붙이는 것은 프록시 대상 클래스에서 검사되기 때문이다.
 *
 * <p><b>"쓰기가 죽는" SQLState 는 테이블에 따라 다르다 — {@code 23502} 하나로 적어 두면 진단이
 * 어긋난다.</b> 이전 서술이 {@code 23502}(NOT NULL 위반)만 적고 있었으나 그것은 <b>정책이 그 INSERT
 * 를 통과시키는 테이블에서만</b> 맞다. 대상 테이블에 {@code WITH CHECK} 가 걸려 있으면 NOT NULL
 * 검사보다 정책이 <b>먼저</b> 걸려 {@code 42501}(insufficient_privilege)이 난다 — P2-g 에서
 * {@code slack_workspace} 로 실측했다({@code app_tenant} 롤 + GUC 미설정 →
 * {@code 42501: new row violates row-level security policy}). 즉 <b>{@code 42501} 을 보고 "권한
 * 문제"라고 단정하지 말 것</b>: 컨텍스트 부재(배선 결함)와 타 테넌트 행 침범(권한 거부)이 같은
 * SQLState 를 낸다. 둘을 SQLState 로는 가를 수 없으므로, 42501 을 4xx 로 바꾸는 코드는 반드시
 * "호출자가 컨텍스트를 세웠다"는 전제 위에서만 좁게 써야 한다
 * ({@code SlackOAuthService.upsertOrDeny} 가 그 예이며 근거를 그 javadoc 에 적어 두었다).
 *
 * <p><b>다만 그 애노테이션은 필요조건일 뿐 충분조건이 아니다.</b> {@code doBegin} 은
 * {@link TenantContext} 가 비어 있으면 GUC 를 <b>아예 심지 않고 조용히 return</b> 한다. 배경
 * 스레드에는 그 컨텍스트가 없으므로 <b>컨텍스트 공급은 호출자(배경 경로) 책임이며 P2-f Task 3 이
 * 배선한다.</b> 애노테이션이 붙었다고 배경 경로가 고쳐진 것이 아니다 — V107 이후 0행을 조사한다면
 * 애노테이션이 아니라 호출자의 컨텍스트를 먼저 볼 것.
 *
 * <p>이 문단이 여기 있는 이유: 같은 근거가 리포지토리 5개에 통째로 복붙돼 있었고, 그중 하나만
 * 갱신되는 드리프트가 실제로 반복됐다. 각 impl 은 이 문단을 {@code {@link}} 로 가리키고 자기
 * 지점에만 해당하는 사실만 따로 적는다.
 *
 * <p>{@code DataSourceTransactionManager} 가 아니라 {@link JdbcTransactionManager} 를 상속한다.
 * Boot 3.4.1 의 {@code DataSourceTransactionManagerAutoConfiguration} 이 기본으로 등록하는 트랜잭션
 * 매니저도 {@code JdbcTransactionManager} 이며, 이 서브클래스는 커밋/롤백 시 {@code SQLException} 을
 * {@code DataAccessException} 으로 변환하는 기능을 추가로 갖는다. 이 클래스가 {@code @Primary} 로
 * Boot 의 빈을 대체하는데, 그냥 {@code DataSourceTransactionManager} 를 상속하면 그 변환 기능이
 * 조용히 사라진다. {@code JdbcTransactionManager} 는 {@code DataSourceTransactionManager} 의
 * 서브클래스이므로 {@code doBegin}, {@code obtainDataSource()}, 생성자 시그니처는 그대로 쓸 수 있다.
 */
public class TenantAwareTransactionManager extends JdbcTransactionManager {

  private static final String SET_TENANT_GUC = "SELECT set_config('app.tenant_id', ?, true)";

  public TenantAwareTransactionManager(DataSource dataSource) {
    super(dataSource);
  }

  @Override
  protected void doBegin(Object transaction, TransactionDefinition definition) {
    super.doBegin(transaction, definition);

    Long tenantId = TenantContext.get();
    if (tenantId == null) {
      return;
    }

    // super.doBegin 이 커넥션을 트랜잭션에 바인딩한 뒤이므로, 여기서 얻는 커넥션은 그 트랜잭션의 것이다.
    Connection connection = DataSourceUtils.getConnection(obtainDataSource());
    try (PreparedStatement ps = connection.prepareStatement(SET_TENANT_GUC)) {
      ps.setString(1, tenantId.toString());
      ps.execute();
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
      doCleanupAfterCompletion(transaction);
      throw new CannotCreateTransactionException("app.tenant_id GUC 설정 실패", e);
    }
  }
}
