package com.smartfirehub.dataset.storage.tibero;

import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections;
import org.springframework.stereotype.Component;

/**
 * 테넌트 OWNER 계정의 트리거 생성 권한 확인과 안내 문장(WD-77).
 *
 * <p>권한은 반드시 <b>OWNER 세션</b>({@link ExternalStoreConnections#withDdl} 이 여는 테넌트 자신의 접속)에서 {@code
 * SESSION_PRIVS} 로 본다. DBA 로 보면 DBA 의 권한이 나와 실제 OWNER 의 권한을 알 수 없다.
 */
@Component
public class TiberoTriggerPrivilege {

  /** 권한이 없을 때 사용자에게 보일 안내. {@code {id}} 는 테넌트 id 로 치환한다 — {@link #missingPrivilegeMessage}. */
  public static final String MISSING_PRIVILEGE_MESSAGE =
      "이 워크스페이스의 Tibero 계정에 트리거 생성 권한이 없어 이 단계를 실행할 수 없습니다. "
          + "운영자에게 다음 문장 실행을 요청해 주세요: GRANT CREATE TRIGGER TO \"GENIA_T{id}\"";

  private final ExternalStoreConnections connections;

  public TiberoTriggerPrivilege(ExternalStoreConnections connections) {
    this.connections = connections;
  }

  /** OWNER 세션에 CREATE TRIGGER(또는 CREATE ANY TRIGGER) 권한이 있는가. */
  public boolean hasCreateTrigger(long tenantId) {
    return connections.withDdl(
        tenantId,
        conn -> {
          try (var st = conn.createStatement();
              var rs =
                  st.executeQuery(
                      "SELECT COUNT(*) FROM SESSION_PRIVS WHERE PRIVILEGE IN"
                          + " ('CREATE TRIGGER', 'CREATE ANY TRIGGER')")) {
            return rs.next() && rs.getInt(1) > 0;
          }
        });
  }

  /** 운영자가 실행할 GRANT 문장. */
  public String grantStatement(long tenantId) {
    return "GRANT CREATE TRIGGER TO \"" + DataStoreAccountRole.OWNER.usernameFor(tenantId) + "\"";
  }

  /** {@link #MISSING_PRIVILEGE_MESSAGE} 의 {id} 를 테넌트 id 로 채운 문장. */
  public static String missingPrivilegeMessage(long tenantId) {
    return MISSING_PRIVILEGE_MESSAGE.replace("{id}", Long.toString(tenantId));
  }
}
