package com.smartfirehub.dataset.storage.tibero;

import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStorePasswordGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 테넌트 한 개의 Tibero 전용 계정(소유·읽기 전용·Python 슬롯) 생성 계획(WD-73).
 *
 * <p>DB 접속 없이 비밀번호·프로비저닝 SQL·DBA 실행용 스크립트만 만드는 순수 객체다. 실행은 별도 컴포넌트가 한다.
 *
 * <p>Tibero 문법({@code QUOTA UNLIMITED ON}, {@code CREATE SESSION} 등)은 Oracle 호환을 가정한 것이며 실서버 확인은
 * WD-71 에서 한다. 식별자는 대문자 이름이라 인용해도 의미가 같으므로, 예약어 충돌과 주입을 막기 위해 항상 큰따옴표로 인용한다.
 */
public final class TiberoAccountPlan {

  /** 테이블스페이스 이름 규칙. DDL 에 직접 들어가므로 이 밖의 값은 거부한다. */
  private static final Pattern TABLESPACE = Pattern.compile("^[A-Za-z][A-Za-z0-9_$#]{0,29}$");

  /** 비밀번호 규칙. {@link #q} 가 이스케이프 없이 큰따옴표로 감싸므로 영문·숫자 밖의 문자는 거부한다. */
  private static final Pattern PASSWORD = Pattern.compile("^[A-Za-z0-9]+$");

  private static final String DEFAULT_TABLESPACE = "USR";

  private final long tenantId;
  private final String tablespace;
  private final List<PlannedAccount> accounts;

  private TiberoAccountPlan(long tenantId, String tablespace, List<PlannedAccount> accounts) {
    this.tenantId = tenantId;
    this.tablespace = tablespace;
    this.accounts = List.copyOf(accounts);
  }

  /**
   * 주어진 계정으로 계획을 만든다.
   *
   * <p>테이블스페이스가 비면 {@code USR}. 계정 목록이 {@link DataStoreAccountRole#all()} 순서·이름과 다르면 잘못 조립된 계획이므로
   * 거부한다. 비밀번호는 영문·숫자만 허용한다.
   *
   * @throws IllegalArgumentException 테이블스페이스·비밀번호 규칙 위반, null 계정 또는 계정 목록 불일치
   */
  public static TiberoAccountPlan of(
      long tenantId, String tablespace, List<PlannedAccount> accounts) {
    String ts = (tablespace == null || tablespace.isBlank()) ? DEFAULT_TABLESPACE : tablespace;
    if (!TABLESPACE.matcher(ts).matches()) {
      throw new IllegalArgumentException("테이블스페이스 이름 형식이 올바르지 않습니다");
    }
    // 큰따옴표로 인용한 식별자는 대소문자를 구분한다. "users" 를 그대로 인용하면 실제 USERS 테이블스페이스가 아니라
    // 존재하지 않는 소문자 이름을 가리키므로, 인용하지 않았을 때(Oracle·Tibero 는 대문자로 접는다)와 같은 뜻이 되게 대문자로 맞춘다.
    ts = ts.toUpperCase(Locale.ROOT);
    List<DataStoreAccountRole> roles = DataStoreAccountRole.all();
    if (accounts == null || accounts.size() != roles.size()) {
      throw new IllegalArgumentException("계정 목록이 역할 구성과 일치하지 않습니다");
    }
    for (int i = 0; i < roles.size(); i++) {
      PlannedAccount a = accounts.get(i);
      if (a == null) {
        throw new IllegalArgumentException("계정 목록에 빈 항목이 있습니다");
      }
      if (a.role() != roles.get(i) || !roles.get(i).usernameFor(tenantId).equals(a.username())) {
        throw new IllegalArgumentException("계정 목록이 역할 구성과 일치하지 않습니다");
      }
      // 메시지에 비밀번호 값은 넣지 않는다.
      if (a.password() == null || !PASSWORD.matcher(a.password()).matches()) {
        throw new IllegalArgumentException("비밀번호는 영문·숫자만 사용할 수 있습니다: " + a.username());
      }
    }
    return new TiberoAccountPlan(tenantId, ts, accounts);
  }

  /** 모든 역할의 비밀번호를 새로 생성해 계획을 만든다. */
  public static TiberoAccountPlan generate(
      long tenantId, String tablespace, DataStorePasswordGenerator gen) {
    List<PlannedAccount> list = new ArrayList<>();
    for (DataStoreAccountRole role : DataStoreAccountRole.all()) {
      list.add(new PlannedAccount(role, role.usernameFor(tenantId), gen.generate()));
    }
    return of(tenantId, tablespace, list);
  }

  /** 데이터 스키마 이름 = 소유 계정 이름. */
  public String schemaName() {
    return DataStoreAccountRole.OWNER.usernameFor(tenantId);
  }

  public String tablespace() {
    return tablespace;
  }

  public List<PlannedAccount> accounts() {
    return accounts;
  }

  /**
   * 실행 순서대로의 프로비저닝 단계: 소유 계정(생성·쿼터·권한), 이어서 RO 와 슬롯(각각 생성·CREATE SESSION).
   *
   * <p>RO·슬롯은 테이블 소유 없이 접속만 가능하며, 테이블 조회 권한은 이후 소유 계정이 개별로 부여한다.
   */
  public List<ProvisioningStep> provisioningSteps() {
    List<ProvisioningStep> steps = new ArrayList<>();
    for (PlannedAccount a : accounts) {
      String u = a.username();
      boolean owner = a.role() == DataStoreAccountRole.OWNER;
      String create = "CREATE USER " + q(u) + " IDENTIFIED BY " + q(a.password());
      if (owner) {
        create += " DEFAULT TABLESPACE " + q(tablespace);
      }
      steps.add(new ProvisioningStep(u + " 계정 생성", create, u));
      if (owner) {
        steps.add(
            new ProvisioningStep(
                u + " 테이블스페이스 할당",
                "ALTER USER " + q(u) + " QUOTA UNLIMITED ON " + q(tablespace),
                null));
        steps.add(
            new ProvisioningStep(
                u + " 권한 부여",
                "GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE SEQUENCE TO " + q(u),
                null));
      } else {
        steps.add(new ProvisioningStep(u + " 권한 부여", "GRANT CREATE SESSION TO " + q(u), null));
      }
    }
    return steps;
  }

  /**
   * 계정 하나를 지우는 보상 SQL. 여러 계정을 지울 때 순서(나중에 만든 것부터)는 호출자가 정한다 — 문장과 계정 이름을 한 쌍으로 다뤄야 실패 로그가 정확한 계정을
   * 가리킨다.
   */
  public String dropStatement(String username) {
    return "DROP USER " + q(username) + " CASCADE";
  }

  /**
   * DBA 가 직접 실행할 전체 스크립트. 상단에 재실행용 DROP 을 주석으로 둔다(이미 계정이 있을 때 풀어서 먼저 실행).
   *
   * <p>비밀번호가 들어 있으므로 호출자는 응답 1회 외에 로그·저장을 하지 않아야 한다.
   */
  public String script() {
    StringBuilder sb = new StringBuilder();
    sb.append("-- Gen:iA Data 테넌트 ").append(tenantId).append(" Tibero 계정 생성 스크립트\n");
    sb.append("-- DBA 계정으로 실행하세요.\n");
    sb.append("-- 아래 DROP 주석은 이 테넌트에 대해 이 스크립트를 이전에 실행해 만들어진 계정일 때만 푸세요.\n");
    sb.append("-- CASCADE 는 해당 스키마의 모든 테이블을 삭제합니다.\n");
    sb.append("-- 다른 용도의 계정(예: 이 Tibero 를 공유하는 다른 환경)이면 지우지 말고 플랫폼 운영자에게 문의하세요.\n");
    // 나중에 만든 계정부터 지운다(생성의 역순).
    for (PlannedAccount a : accounts.reversed()) {
      sb.append("-- ").append(dropStatement(a.username())).append(";\n");
    }
    sb.append('\n');
    for (ProvisioningStep step : provisioningSteps()) {
      sb.append(step.sql()).append(";\n");
    }
    return sb.toString();
  }

  /** 식별자·비밀번호를 큰따옴표로 인용한다(입력은 {@link #of} 에서 이미 검증된 문자만 들어온다). */
  private static String q(String value) {
    return "\"" + value + "\"";
  }
}
