package com.smartfirehub.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PostgresTestContainer;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 로그인 IP 기준 제한(비밀번호 스프레이 차단) 통합 테스트(WD-578).
 *
 * <p>실제 HTTP 경로(MockMvc → 컨트롤러 → AuthService/PlatformAuthService → PostgreSQL)로 본다. 요청 IP 는 {@code
 * remoteAddr} 로 정한다 — 신뢰 프록시가 아닌 문서용 대역(198.51.100.0/24, 203.0.113.0/24)이라 {@code
 * ClientIpExtractor} 가 헤더를 보지 않고 그 주소를 쓴다. 테스트 프로필은 루프백(MockMvc 기본 127.0.0.1)을 제외 대역으로 두므로, 다른 테스트
 * 스위트의 실패 로그인은 이 제한에 쌓이지 않는다.
 *
 * <p>클래스 레벨 트랜잭션이 없다 — 운영처럼 로그인 트랜잭션이 커밋돼야 실패 기록이 다음 요청에 보인다. 테스트마다 새 IP·새 아이디를 쓰고 끝나면 지운다.
 */
@AutoConfigureMockMvc
class LoginIpLimitTest extends IntegrationTestBase {

  private static final String PASSWORD = "IpLimitPassw0rd!";
  private static final String TENANT_LOGIN = "/api/v1/auth/login";
  private static final String PLATFORM_LOGIN = "/api/platform/auth/login";

  /** 테스트마다 다른 마지막 옥텟을 주기 위한 순번(같은 JVM 안에서 IP 키가 겹치지 않게). */
  private static final AtomicInteger NEXT_OCTET = new AtomicInteger(1);

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate tx;

  /** 같은 IP 키 게이트(WD-630) — 잠금 대기 재현에서 나머지 요청이 줄에 선 것을 폴링으로 확인한다. */
  @Autowired private LoginIpGate loginIpGate;

  /** 이 테스트가 만든 사용자 id·쓴 IP 키 — 끝에 지운다(아이디는 모두 prefix 로 시작해 prefix 로 지운다). */
  private final List<Long> userIds = new ArrayList<>();

  private final List<String> ipKeys = new ArrayList<>();

  private String prefix;

  @BeforeEach
  void setUp() {
    prefix = "iplim-" + UUID.randomUUID().toString().substring(0, 8);
  }

  @AfterEach
  void tearDown() {
    TenantRlsTestSupport.runInTenantTransaction(
        tx, null, () -> dsl.execute("delete from audit_log where username like ? || '%'", prefix));
    for (Long id : userIds) {
      dsl.execute("delete from refresh_token where user_id = ?", id);
      dsl.execute("delete from platform_user_role where user_id = ?", id);
      TenantRlsTestSupport.deleteUser(dsl, id);
    }
    dsl.execute("delete from login_attempts where username like ? || '%'", prefix);
    for (String key : ipKeys) {
      dsl.execute("delete from login_ip_failures where ip_key = ?", key);
    }
    TenantContext.clear();
  }

  @Test
  @DisplayName("같은 IP 에서 서로 다른 계정 10개가 실패하면, 그 IP 의 다음 로그인은 맞는 비밀번호여도 429 — 다른 IP 는 통과")
  void tenantLogin_tenDistinctFailures_blocksIp() throws Exception {
    String user = createUser(false);
    String attacker = newIpv4();
    String bystander = newIpv4();

    for (int i = 0; i < LoginIpThrottleService.MAX_DISTINCT_ACCOUNTS; i++) {
      assertThat(status(TENANT_LOGIN, prefix + "-ghost" + i, "wrong", attacker)).isEqualTo(401);
    }

    assertThat(status(TENANT_LOGIN, user, PASSWORD, attacker)).isEqualTo(429);
    assertThat(status(TENANT_LOGIN, user, PASSWORD, bystander)).isEqualTo(200);
  }

  @Test
  @DisplayName("IP 거절은 LOGIN·FAILURE 감사 행(사유 IP_LIMITED)을 남긴다")
  void tenantLogin_ipLimited_isAudited() throws Exception {
    String attacker = newIpv4();
    for (int i = 0; i < LoginIpThrottleService.MAX_DISTINCT_ACCOUNTS; i++) {
      status(TENANT_LOGIN, prefix + "-ghost" + i, "wrong", attacker);
    }
    String victim = prefix + "-victim";
    assertThat(status(TENANT_LOGIN, victim, "wrong", attacker)).isEqualTo(429);

    Integer rows =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            null,
            (Supplier<Integer>)
                () ->
                    (Integer)
                        dsl.fetchValue(
                            "select count(*)::int from audit_log where username = ?"
                                + " and action_type = 'LOGIN' and result = 'FAILURE'"
                                + " and metadata ->> 'reason' = 'IP_LIMITED' and ip_address = ?",
                            victim,
                            attacker));
    assertThat(rows).isEqualTo(1);
  }

  @Test
  @DisplayName("한 계정만 반복 실패하면(계정 잠금은 따로) IP 는 막히지 않는다")
  void tenantLogin_sameAccountRepeated_doesNotBlockIp() throws Exception {
    String user = createUser(false);
    String ip = newIpv4();
    String target = prefix + "-target";

    for (int i = 0; i < 9; i++) {
      // 5회째부터 그 계정은 잠겨 429 — 계정 잠금이지 IP 제한이 아니다
      status(TENANT_LOGIN, target, "wrong", ip);
    }

    assertThat(status(TENANT_LOGIN, user, PASSWORD, ip)).isEqualTo(200);
  }

  @Test
  @DisplayName("성공 로그인은 IP 실패 기록을 지우지 않는다")
  void tenantLogin_successDoesNotResetIpCounter() throws Exception {
    String user = createUser(false);
    String ip = newIpv4();

    for (int i = 0; i < LoginIpThrottleService.MAX_DISTINCT_ACCOUNTS - 1; i++) {
      assertThat(status(TENANT_LOGIN, prefix + "-ghost" + i, "wrong", ip)).isEqualTo(401);
    }
    // 공격자가 자기 계정으로 로그인해도 카운터는 그대로다
    assertThat(status(TENANT_LOGIN, user, PASSWORD, ip)).isEqualTo(200);
    assertThat(status(TENANT_LOGIN, prefix + "-ghost-last", "wrong", ip)).isEqualTo(401);

    assertThat(status(TENANT_LOGIN, user, PASSWORD, ip)).isEqualTo(429);
  }

  @Test
  @DisplayName("IPv6 는 /64 로 묶어 센다 — 주소를 바꿔 가며 실패해도 같은 /64 면 막힌다")
  void tenantLogin_ipv6RotationWithinSlash64_isBlocked() throws Exception {
    String user = createUser(false);
    String net = "2001:db8:" + Integer.toHexString(NEXT_OCTET.getAndIncrement()) + ":7";
    ipKeys.add(net + "::/64");

    for (int i = 0; i < LoginIpThrottleService.MAX_DISTINCT_ACCOUNTS; i++) {
      String rotated = net + "::" + Integer.toHexString(i + 1);
      assertThat(status(TENANT_LOGIN, prefix + "-ghost" + i, "wrong", rotated)).isEqualTo(401);
    }

    assertThat(status(TENANT_LOGIN, user, PASSWORD, net + "::abcd")).isEqualTo(429);
  }

  @Test
  @DisplayName("신뢰 프록시가 아닌 곳에서 보낸 X-Forwarded-For 는 무시된다 — 헤더를 바꿔 가며 키를 피할 수 없다")
  void tenantLogin_spoofedForwardedFor_doesNotChangeKey() throws Exception {
    String user = createUser(false);
    String attacker = newIpv4();

    for (int i = 0; i < LoginIpThrottleService.MAX_DISTINCT_ACCOUNTS; i++) {
      String spoofed = "192.0.2." + (i + 1);
      assertThat(status(TENANT_LOGIN, prefix + "-ghost" + i, "wrong", attacker, spoofed))
          .isEqualTo(401);
    }

    assertThat(status(TENANT_LOGIN, user, PASSWORD, attacker)).isEqualTo(429);
  }

  @Test
  @DisplayName("신뢰 프록시 뒤에서 해석할 수 없는 X-Forwarded-For 를 바꿔 보내도 공용 키 하나로 모여 10개 계정 뒤 429")
  void tenantLogin_unparseableForwardedFor_sharesOneKey() throws Exception {
    // 10.0.0.5 는 기본 신뢰 프록시(10.0.0.0/8)라 ClientIpExtractor 가 헤더를 읽고, 해석 안 되는 값은 그대로 돌려준다.
    // 예전에는 그 값에 키가 없어 제한이 꺼졌다 — 이제 모든 쓰레기 값이 UNPARSED_KEY 한 버킷에 쌓인다.
    String proxy = "10.0.0.5";
    ipKeys.add(LoginIpThrottleService.UNPARSED_KEY);
    // 공용 키라 다른 테스트의 잔여 행이 있으면 경계가 흔들린다 — 시작 전에 비운다.
    dsl.execute(
        "delete from login_ip_failures where ip_key = ?", LoginIpThrottleService.UNPARSED_KEY);

    for (int i = 0; i < LoginIpThrottleService.MAX_DISTINCT_ACCOUNTS; i++) {
      assertThat(status(TENANT_LOGIN, prefix + "-ghost" + i, "wrong", proxy, "junk-" + i))
          .isEqualTo(401);
    }

    assertThat(status(TENANT_LOGIN, prefix + "-next", "wrong", proxy, "junk-next")).isEqualTo(429);
  }

  /**
   * 같은 IP 에서 동시에 들어온 실패 로그인도 한도를 넘겨 기록되지 않는다 — 검사·기록이 IP 키 잠금으로 직렬화된다.
   *
   * <p>잠금이 없으면 동시 요청이 모두 "아직 0개" 를 보고 검사를 통과해 한도(10)를 훌쩍 넘는 계정이 실패를 시도할 수 있었다. 잠금이 있으면 정확히 10개만
   * 401(실패 기록)이고 나머지는 429 다. 스레드 수는 커넥션 풀(테스트 2개)보다 많아도 된다 — 로그인 하나가 커넥션 1개만 쓰므로, 잠금을 쥔 요청이 커밋해
   * 커넥션을 돌려주면 다음 요청이 이어 받는다(교착이면 Hikari 대기 30초에 걸려 타임아웃으로 실패한다).
   */
  @Test
  @DisplayName("같은 IP 의 동시 실패 로그인 20개 중 정확히 10개만 실패로 기록되고 나머지는 429")
  void tenantLogin_concurrentFailuresFromOneIp_areCappedAtLimit() throws Exception {
    String attacker = newIpv4();
    int concurrency = 2 * LoginIpThrottleService.MAX_DISTINCT_ACCOUNTS;

    ExecutorService pool = Executors.newFixedThreadPool(concurrency);
    List<Integer> codes = new ArrayList<>();
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Integer>> futures = new ArrayList<>();
      for (int i = 0; i < concurrency; i++) {
        String username = prefix + "-race" + i;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return status(TENANT_LOGIN, username, "wrong", attacker);
                }));
      }
      start.countDown();
      for (Future<Integer> future : futures) {
        codes.add(future.get(60, TimeUnit.SECONDS));
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(codes.stream().filter(c -> c == 401).count())
        .as("실패로 처리된(검사를 통과한) 요청 수")
        .isEqualTo(LoginIpThrottleService.MAX_DISTINCT_ACCOUNTS);
    assertThat(codes.stream().filter(c -> c == 429).count())
        .isEqualTo(concurrency - LoginIpThrottleService.MAX_DISTINCT_ACCOUNTS);
    assertThat(
            dsl.fetchCount(dsl.selectOne().from("login_ip_failures").where("ip_key = ?", attacker)))
        .as("기록된 서로 다른 실패 계정 수")
        .isEqualTo(LoginIpThrottleService.MAX_DISTINCT_ACCOUNTS);
  }

  @Test
  @DisplayName("제외 대역(테스트 프로필의 루프백)은 서로 다른 계정이 10개를 넘게 실패해도 401 을 유지한다")
  void tenantLogin_exemptRange_isNotLimited() throws Exception {
    String user = createUser(false);

    for (int i = 0; i < LoginIpThrottleService.MAX_DISTINCT_ACCOUNTS + 2; i++) {
      assertThat(status(TENANT_LOGIN, prefix + "-ghost" + i, "wrong", "127.0.0.1")).isEqualTo(401);
    }

    assertThat(status(TENANT_LOGIN, user, PASSWORD, "127.0.0.1")).isEqualTo(200);
    assertThat(
            dsl.fetchCount(
                dsl.selectOne().from("login_ip_failures").where("username like ? || '%'", prefix)))
        .as("제외 대역은 실패 기록도 남기지 않는다")
        .isZero();
  }

  @Test
  @DisplayName("운영자 로그인도 같은 IP 에서 서로 다른 계정 10개가 실패하면 429")
  void platformLogin_tenDistinctFailures_blocksIp() throws Exception {
    String admin = createUser(true);
    String attacker = newIpv4();
    String bystander = newIpv4();

    for (int i = 0; i < LoginIpThrottleService.MAX_DISTINCT_ACCOUNTS; i++) {
      assertThat(status(PLATFORM_LOGIN, prefix + "-ghost" + i, "wrong", attacker)).isEqualTo(401);
    }

    assertThat(status(PLATFORM_LOGIN, admin, PASSWORD, attacker)).isEqualTo(429);
    assertThat(status(PLATFORM_LOGIN, admin, PASSWORD, bystander)).isEqualTo(200);
  }

  /**
   * 운영자 로그인에서 비밀번호는 맞았지만 플랫폼 롤이 없는 실패도 IP 실패로 센다(WD-630 리뷰) — 계정 카운터와 함께.
   *
   * <p>롤 없음의 401 은 열거 방지를 위해 비밀번호 불일치와 똑같다. 맞는 비밀번호만 IP 카운터에서 빠지면, 한도 직전에서 후보 비밀번호를 넣어 보고 다음 요청이
   * 429 인지로 "방금 비밀번호가 맞았다"를 알아내는 오라클이 생긴다. 그래서 맞는 비밀번호·틀린 비밀번호가 IP·계정 카운터에 똑같이 남아야 한다.
   */
  @Test
  @DisplayName("운영자 로그인 롤 없음(비밀번호 맞음)도 틀린 비밀번호와 똑같이 IP 실패·계정 카운터를 남긴다 — 비밀번호 오라클 방지")
  void platformLogin_noPlatformRole_recordsIpFailureLikeWrongPassword() throws Exception {
    String rightPassword = createUser(false);
    String wrongPassword = createUser(false);
    String ip = newIpv4();

    assertThat(status(PLATFORM_LOGIN, rightPassword, PASSWORD, ip)).isEqualTo(401);
    assertThat(status(PLATFORM_LOGIN, wrongPassword, "wrong", ip)).isEqualTo(401);

    assertThat(ipFailureRows(ip, rightPassword)).as("롤 없음(비밀번호 맞음)도 IP 실패").isEqualTo(1);
    assertThat(ipFailureRows(ip, wrongPassword)).as("잘못된 비밀번호는 IP 실패").isEqualTo(1);
    assertThat(loginAttempts(rightPassword)).as("계정 카운터").isEqualTo(1);
    assertThat(loginAttempts(wrongPassword)).isEqualTo(1);
  }

  /**
   * 같은 IP 키의 로그인이 풀 크기(테스트 2개)보다 많이 몰려 잠금을 기다려도, 기다리는 요청들이 커넥션을 쥐지 않아 다른 IP 의 로그인이 제때 끝난다(WD-630).
   *
   * <p>재현: 테스트가 풀 밖의 별도 커넥션으로 그 IP 키의 advisory 잠금을 쥔다(다른 API 인스턴스가 같은 키 로그인을 처리 중인 상황). 예전에는 같은 IP
   * 로그인이 저마다 커넥션을 빌린 뒤 그 잠금을 기다려 풀 2개가 모두 묶였고, 다른 IP 로그인은 커넥션을 못 얻어 멈췄다. 이제는 JVM 안 게이트가 커넥션을 빌리기 전에
   * 줄을 세우므로 DB 잠금을 기다리는(커넥션을 쥔) 같은 IP 로그인은 하나뿐이다.
   *
   * <p>줄 선 요청이 테스트가 잠금을 쥔 동안 게이트 기다림 상한에 걸려 429 가 되지 않도록, 테스트 프로필은 {@code gate-wait} 를 넉넉히(60초) 둔다
   * (application-test.yml) — 잠금이 풀리면 모두 401 이어야 한다.
   */
  @Test
  @DisplayName("같은 IP 로그인 3건이 잠금을 기다려도 커넥션은 하나만 묶이고 다른 IP 로그인은 제때 끝난다")
  void sameIpLoginsWaitingOnLock_doNotStarvePool() throws Exception {
    String user = createUser(false);
    String ip = newIpv4();
    String bystander = newIpv4();
    String lockKey = "login-ip-limit:" + ip;
    int sameIpRequests = 3;

    ExecutorService pool = Executors.newFixedThreadPool(sameIpRequests + 1);
    List<Future<Integer>> sameIp = new ArrayList<>();
    try (Connection holder =
        DriverManager.getConnection(
            PostgresTestContainer.INSTANCE.getJdbcUrl(), "app_tenant", "app_tenant")) {
      try (PreparedStatement lock =
          holder.prepareStatement("select pg_advisory_lock(hashtextextended(?, 0))")) {
        lock.setString(1, lockKey);
        lock.execute();
      }
      try {
        for (int i = 0; i < sameIpRequests; i++) {
          String username = prefix + "-wait" + i;
          sameIp.add(pool.submit(() -> status(TENANT_LOGIN, username, "wrong", ip)));
        }
        // 첫 요청이 DB 잠금 대기에 들어가고, 나머지가 게이트 줄에 설 때까지 기다린다(고정 sleep 대신 폴링).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (waitingOnAdvisoryLock(holder, lockKey) == 0 && System.nanoTime() < deadline) {
          Thread.sleep(50);
        }
        while (loginIpGate.queueLength(ip) < sameIpRequests - 1 && System.nanoTime() < deadline) {
          Thread.sleep(20);
        }
        assertThat(loginIpGate.queueLength(ip))
            .as("나머지 같은 IP 로그인은 커넥션 없이 게이트 줄에 선다")
            .isEqualTo(sameIpRequests - 1);
        int waitingWithConnection = waitingOnAdvisoryLock(holder, lockKey);

        Future<Integer> other = pool.submit(() -> status(TENANT_LOGIN, user, PASSWORD, bystander));
        Integer otherStatus;
        try {
          otherStatus = other.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
          otherStatus = null;
        }

        assertThat(waitingWithConnection).as("DB 잠금을 커넥션을 쥔 채 기다리는 같은 IP 로그인 수").isEqualTo(1);
        assertThat(otherStatus).as("다른 IP 로그인은 풀이 묶이지 않아 제때 200").isEqualTo(200);
      } finally {
        try (PreparedStatement unlock =
            holder.prepareStatement("select pg_advisory_unlock_all()")) {
          unlock.execute();
        }
      }
      for (Future<Integer> future : sameIp) {
        assertThat(future.get(60, TimeUnit.SECONDS))
            .as("잠금이 풀리면 같은 IP 로그인도 차례로 끝난다")
            .isEqualTo(401);
      }
    } finally {
      pool.shutdown();
      pool.awaitTermination(60, TimeUnit.SECONDS);
    }
  }

  /** 그 문자열 키의 advisory 잠금을 기다리는(granted=false) 세션 수 — 64비트 해시를 classid(상위)·objid(하위)로 나눠 맞춘다. */
  private static int waitingOnAdvisoryLock(Connection connection, String key) throws Exception {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "with h as (select hashtextextended(?, 0) as v) select count(*)::int from pg_locks l, h"
                + " where l.locktype = 'advisory' and not l.granted and l.objsubid = 1"
                + " and l.classid::bigint = ((h.v >> 32) & 4294967295)"
                + " and l.objid::bigint = (h.v & 4294967295)")) {
      ps.setString(1, key);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  /** 그 IP 키·계정의 IP 실패 기록 행 수. */
  private int ipFailureRows(String ipKey, String username) {
    return dsl.fetchCount(
        dsl.selectOne()
            .from("login_ip_failures")
            .where("ip_key = ? and username = ?", ipKey, username));
  }

  /** 그 계정의 로그인 실패 카운터(행이 없으면 0). */
  private int loginAttempts(String username) {
    return (Integer)
        dsl.fetchValue(
            "select coalesce(max(attempts), 0)::int from login_attempts where username = ?",
            username);
  }

  /** 문서용 대역(198.51.100.0/24)의 새 IPv4 를 하나 고르고 정리 목록에 넣는다. */
  private String newIpv4() {
    String ip = "198.51.100." + (NEXT_OCTET.getAndIncrement() % 250 + 1);
    ipKeys.add(ip);
    return ip;
  }

  /** 로그인 요청을 보내고 HTTP 상태 코드를 돌려준다. */
  private int status(String path, String username, String password, String remoteAddr)
      throws Exception {
    return mockMvc
        .perform(loginRequest(path, username, password, remoteAddr))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  /** {@code X-Forwarded-For} 헤더를 붙여 로그인 요청을 보내고 HTTP 상태 코드를 돌려준다(신뢰 프록시 판정 검증용). */
  private int status(
      String path, String username, String password, String remoteAddr, String forwardedFor)
      throws Exception {
    return mockMvc
        .perform(
            loginRequest(path, username, password, remoteAddr)
                .header("X-Forwarded-For", forwardedFor))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  /** 지정한 접속 주소(remoteAddr)로 보내는 로그인 요청. */
  private MockHttpServletRequestBuilder loginRequest(
      String path, String username, String password, String remoteAddr) {
    return post(path)
        .with(
            req -> {
              req.setRemoteAddr(remoteAddr);
              return req;
            })
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password));
  }

  /**
   * 로그인할 수 있는 사용자를 만든다(멤버십 없음 — 테넌트 로그인은 테넌트 미선택 토큰으로 200).
   *
   * @param platformAdmin true 면 운영자(SUPER_ADMIN) 롤을 준다
   */
  private String createUser(boolean platformAdmin) {
    String username = prefix + "-u" + userIds.size();
    long id =
        TenantRlsTestSupport.insertUserWithPassword(
            dsl, username, passwordEncoder.encode(PASSWORD));
    if (platformAdmin) {
      TenantRlsTestSupport.grantPlatformSuperAdmin(dsl, id);
    }
    userIds.add(id);
    return username;
  }
}
