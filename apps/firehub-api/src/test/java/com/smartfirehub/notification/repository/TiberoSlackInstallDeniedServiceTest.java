package com.smartfirehub.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.global.platformdb.tibero.TiberoPlatformDbErrors;
import com.smartfirehub.notification.auth.SlackOAuthService;
import com.smartfirehub.notification.auth.exception.SlackWorkspaceInstallDeniedException;
import com.smartfirehub.notification.channels.slack.SlackApiClient;
import com.smartfirehub.notification.repository.mybatis.MyBatisSlackWorkspaceRepository;
import com.smartfirehub.notification.repository.mybatis.SlackWorkspaceMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TenantPairContractBase;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DuplicateKeyException;

/**
 * WD-730 — Tibero 에서 SlackOAuthService 가 실제 MyBatis 리포지토리(런타임 계정 + 행 격리)에 묶였을 때, 다른 테넌트가 가진 team
 * 설치는 PG 와 같은 {@link SlackWorkspaceInstallDeniedException} 이 되고 같은 테넌트 재설치는 같은 id 로 갱신된다. PG 쪽은
 * {@code SlackReinstallCrossTenantTest} 가 맡는다. Slack API·암호화만 가짜다(암호문은 더미).
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoSlackInstallDeniedServiceTest extends TenantPairContractBase {

  private TiberoPlatformSchema poc;
  private SlackWorkspaceRepository repository;
  private long user;
  private String team;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            SlackWorkspaceRepository.class,
            new MyBatisSlackWorkspaceRepository(
                poc.mapper(SlackWorkspaceMapper.class), new TiberoPlatformDbErrors()));
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }

  @BeforeEach
  void setUpUser() {
    user = fx.createUser(prefix + "-u", null);
    team = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  private SlackOAuthService service() {
    SlackApiClient api = mock(SlackApiClient.class);
    ObjectNode resp = new ObjectMapper().createObjectNode();
    resp.put("ok", true);
    resp.put("access_token", "xoxb-dummy");
    resp.put("bot_user_id", "UBOT1");
    resp.putObject("team").put("id", team).put("name", "Acme");
    when(api.oauthV2Access(any(), any(), any(), any())).thenReturn(resp);
    EncryptionService enc = mock(EncryptionService.class);
    when(enc.encrypt(anyString())).thenReturn("enc-bot-svc");
    return new SlackOAuthService(api, repository, null, enc, new TiberoPlatformDbErrors());
  }

  @Test
  @DisplayName("다른 테넌트가 가진 team_id 설치는 설치 거절 예외이고 원인에 team_id 키 중복이 있다")
  void installOfTeamOwnedByOtherTenant_isDenied() {
    long id = inA(() -> repository.upsertFromOAuth(team, "Acme", "UBOT1", "enc-bot-1", user));
    SlackOAuthService service = service();

    Throwable thrown = catchThrowable(() -> inB(() -> service.completeAuthorization("c", user)));

    assertThat(thrown).isInstanceOf(SlackWorkspaceInstallDeniedException.class);
    assertThat(thrown.getCause()).isInstanceOf(SlackTeamOwnedByAnotherTenantException.class);
    assertThat(thrown.getCause().getCause()).isInstanceOf(DuplicateKeyException.class);
    assertThat(inA(() -> repository.findByTeamId(team)).orElseThrow().id()).isEqualTo(id);
  }

  @Test
  @DisplayName("같은 테넌트가 같은 team_id 를 다시 설치하면 거절이 아니라 같은 id 로 갱신되고 revoked 가 풀린다")
  void reinstallBySameTenant_updatesSameId() {
    long id = inA(() -> repository.upsertFromOAuth(team, "Acme", "UBOT1", "enc-bot-1", user));
    runA(() -> repository.revoke(team));
    SlackOAuthService service = service();

    var installed = inA(() -> service.completeAuthorization("c", user));

    assertThat(installed.workspaceId()).isEqualTo(id);
    assertThat(fx.slackWorkspaceStored(id).revoked()).isFalse();
    assertThat(inA(() -> repository.findByTeamId(team)).orElseThrow().botTokenEnc())
        .isEqualTo("enc-bot-svc");
  }
}
