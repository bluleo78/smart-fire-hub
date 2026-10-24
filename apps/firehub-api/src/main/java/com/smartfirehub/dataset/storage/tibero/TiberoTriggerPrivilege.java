package com.smartfirehub.dataset.storage.tibero;

import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections;
import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.stereotype.Component;

/**
 * 테넌트 OWNER 계정의 트리거 생성 권한 확인과 안내 문장(WD-77).
 *
 * <p>권한은 반드시 <b>OWNER 세션</b>({@link ExternalStoreConnections#withDdl} 이 여는 테넌트 자신의 접속)에서 {@code
 * SESSION_PRIVS} 로 본다({@link TiberoCatalog#hasCreateTriggerPrivilege}). DBA 로 보면 DBA 의 권한이 나와 실제
 * OWNER 의 권한을 알 수 없다. 호출자가 이미 연 DDL 커넥션을 받아 트리거 상태 확인·생성과 같은 접속에서 확인한다. 저장소 단위 테스트가 이 확인만 가짜로 바꿀 수
 * 있도록 빈으로 둔다.
 */
@Component
public class TiberoTriggerPrivilege {

  /** 권한이 없을 때 사용자에게 보일 안내의 앞부분 — 뒤에 운영자가 실행할 GRANT 문장({@link #grantStatement})이 붙는다. */
  private static final String MISSING_PRIVILEGE_PREFIX =
      "이 워크스페이스의 Tibero 계정에 트리거 생성 권한이 없어 이 단계를 실행할 수 없습니다. " + "운영자에게 다음 문장 실행을 요청해 주세요: ";

  /** 주어진 OWNER 세션 커넥션에 CREATE TRIGGER(또는 CREATE ANY TRIGGER) 권한이 있는가. */
  public boolean hasCreateTrigger(Connection ownerConnection) throws SQLException {
    return TiberoCatalog.hasCreateTriggerPrivilege(ownerConnection);
  }

  /**
   * 운영자가 실행할 GRANT 문장 — OWNER 계정 이름은 {@link DataStoreAccountRole#usernameFor} 규칙({@code
   * GENIA_T{id}}).
   */
  public static String grantStatement(long tenantId) {
    return "GRANT CREATE TRIGGER TO \"" + DataStoreAccountRole.OWNER.usernameFor(tenantId) + "\"";
  }

  /** 권한이 없을 때 사용자에게 보일 안내(GRANT 문장 포함). */
  public static String missingPrivilegeMessage(long tenantId) {
    return MISSING_PRIVILEGE_PREFIX + grantStatement(tenantId);
  }
}
