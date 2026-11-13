package com.smartfirehub.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.platformdb.tibero.TiberoPlatformDbErrors;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.repository.mybatis.MyBatisOAuthStateRepository;
import com.smartfirehub.notification.repository.mybatis.MyBatisSlackWorkspaceRepository;
import com.smartfirehub.notification.repository.mybatis.MyBatisUserChannelBindingRepository;
import com.smartfirehub.notification.repository.mybatis.MyBatisUserChannelPreferenceRepository;
import com.smartfirehub.notification.repository.mybatis.OAuthStateMapper;
import com.smartfirehub.notification.repository.mybatis.OAuthStateMapper.StateRow;
import com.smartfirehub.notification.repository.mybatis.SlackWorkspaceMapper;
import com.smartfirehub.notification.repository.mybatis.UserChannelBindingMapper;
import com.smartfirehub.notification.repository.mybatis.UserChannelPreferenceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 묶음 ④ Task 2·3 MyBatis 구현의 DB 로 만들 수 없는 분기를 목 매퍼로 고정한다(WD-709): tenant_id 가 NULL 인 oauth_state
 * 거절(열이 NOT NULL 이라 DB 로는 못 만든다), 소비 갱신 0행 거절, CHAT 무조회, 채널 선호·슬랙 워크스페이스 upsert 의 중복 키 1회 재시도, 사용자
 * 채널 binding upsert 의 중복 키 1회 재시도와 workspace 가 NULL 이면 upsert 없이 삽입(insertWithoutWorkspace)하는 분기,
 * signing secret NULL → 빈 문자열 정규화, 클래스 단위 트랜잭션.
 */
class MyBatisNotificationBundle4UnitTest {

  @Test
  @DisplayName("tenant_id 가 NULL 인 state 는 소비하지 않고 empty 로 거절한다(fail-closed)")
  void consume_nullTenantIsRejected() {
    OAuthStateMapper mapper = mock(OAuthStateMapper.class);
    when(mapper.selectConsumableForUpdate(any(), any()))
        .thenReturn(new StateRow(7L, "SLACK", null));

    assertThat(new MyBatisOAuthStateRepository(mapper).consume("s-null-tenant")).isEmpty();

    verify(mapper, never()).markConsumed(any(), any());
  }

  @Test
  @DisplayName("소비 갱신이 0행이면(다른 트랜잭션이 먼저 소비) empty 이다")
  void consume_zeroUpdatedRowsIsEmpty() {
    OAuthStateMapper mapper = mock(OAuthStateMapper.class);
    when(mapper.selectConsumableForUpdate(any(), any())).thenReturn(new StateRow(7L, "SLACK", 3L));
    when(mapper.markConsumed(any(), any())).thenReturn(0);

    assertThat(new MyBatisOAuthStateRepository(mapper).consume("s")).isEmpty();
  }

  @Test
  @DisplayName("CHAT 의 isEnabled 는 DB 를 조회하지 않고 true 이다")
  void isEnabled_chatSkipsLookup() {
    UserChannelPreferenceMapper mapper = mock(UserChannelPreferenceMapper.class);

    assertThat(new MyBatisUserChannelPreferenceRepository(mapper).isEnabled(1L, ChannelType.CHAT))
        .isTrue();

    verifyNoInteractions(mapper);
  }

  @Test
  @DisplayName("채널 선호 upsert 는 중복 키 실패 뒤 한 번만 다시 시도한다")
  void setEnabled_retriesOnceAfterDuplicateKey() {
    UserChannelPreferenceMapper mapper = mock(UserChannelPreferenceMapper.class);
    when(mapper.upsert(anyLong(), any(), anyBoolean()))
        .thenThrow(new DuplicateKeyException("dup"))
        .thenReturn(1);

    new MyBatisUserChannelPreferenceRepository(mapper).setEnabled(5L, ChannelType.EMAIL, false);

    verify(mapper, times(2)).upsert(5L, "EMAIL", false);
  }

  @Test
  @DisplayName("슬랙 워크스페이스 upsert 는 중복 키 실패 뒤 한 번만 다시 시도하고 id 를 한 번 읽는다")
  void upsertFromOAuth_retriesOnceAfterDuplicateKey() {
    SlackWorkspaceMapper mapper = mock(SlackWorkspaceMapper.class);
    when(mapper.upsertFromOAuth(any(), any(), any(), any(), any()))
        .thenThrow(new DuplicateKeyException("dup"))
        .thenReturn(1);
    when(mapper.selectIdByTeamId("T1")).thenReturn(42L);

    long id =
        new MyBatisSlackWorkspaceRepository(mapper, new TiberoPlatformDbErrors())
            .upsertFromOAuth("T1", "n", "b", "e", 1L);

    assertThat(id).isEqualTo(42L);
    verify(mapper, times(2)).upsertFromOAuth("T1", "n", "b", "e", 1L);
    verify(mapper, times(1)).selectIdByTeamId("T1");
  }

  /** Tibero 유일 위반 모양 — 메시지의 ('스키마'.'제약') 에서 제약 이름을 꺼낸다. */
  private static DuplicateKeyException tiberoDuplicate(String constraint) {
    return new DuplicateKeyException(
        "insert",
        new java.sql.SQLIntegrityConstraintViolationException(
            "JDBC-10007:UNIQUE constraint violation ('FHT_X'.'" + constraint + "').",
            "23000",
            -10007));
  }

  @Test
  @DisplayName("재시도 뒤에도 남은 team_id 키 중복은 다른 테넌트 소유 신호로 바뀐다")
  void upsertFromOAuth_teamIdKeyDuplicateAfterRetry_isOwnedByAnotherTenant() {
    SlackWorkspaceMapper mapper = mock(SlackWorkspaceMapper.class);
    when(mapper.upsertFromOAuth(any(), any(), any(), any(), any()))
        .thenThrow(tiberoDuplicate("SLACK_WORKSPACE_TEAM_ID_KEY"));
    var repo = new MyBatisSlackWorkspaceRepository(mapper, new TiberoPlatformDbErrors());

    var thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> repo.upsertFromOAuth("T1", "n", "b", "e", 1L));

    assertThat(thrown).isInstanceOf(SlackTeamOwnedByAnotherTenantException.class);
    assertThat(thrown.getCause()).isInstanceOf(DuplicateKeyException.class);
    verify(mapper, times(2)).upsertFromOAuth("T1", "n", "b", "e", 1L);
  }

  @Test
  @DisplayName("team_id 가 아닌 다른 제약의 중복은 변환하지 않고 그대로 올린다")
  void upsertFromOAuth_otherConstraintDuplicate_propagatesUnchanged() {
    SlackWorkspaceMapper mapper = mock(SlackWorkspaceMapper.class);
    DuplicateKeyException other = tiberoDuplicate("SLACK_WORKSPACE_PKEY");
    when(mapper.upsertFromOAuth(any(), any(), any(), any(), any())).thenThrow(other);
    var repo = new MyBatisSlackWorkspaceRepository(mapper, new TiberoPlatformDbErrors());

    var thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> repo.upsertFromOAuth("T1", "n", "b", "e", 1L));

    assertThat(thrown).isInstanceOf(DuplicateKeyException.class).isSameAs(other);
  }

  @Test
  @DisplayName(
      "binding upsert(workspace 있음)는 중복 키 실패 뒤 한 번만 다시 시도하고, workspace NULL 이면 upsert 없이 삽입한다")
  void bindingUpsert_retriesOnceAndNullWorkspaceInserts() {
    UserChannelBindingMapper mapper = mock(UserChannelBindingMapper.class);
    when(mapper.upsert(
            anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any()))
        .thenThrow(new DuplicateKeyException("dup"))
        .thenReturn(1);
    var repo = new MyBatisUserChannelBindingRepository(mapper);
    java.time.Instant t = java.time.Instant.now();

    repo.upsert(
        new UserChannelBinding(
            null, 1L, ChannelType.SLACK, 7L, "U", "a", "t", null, null, "ACTIVE", null, t, t));
    verify(mapper, times(2))
        .upsert(
            anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any());
    verify(mapper, never())
        .insertWithoutWorkspace(
            anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

    repo.upsert(
        new UserChannelBinding(
            null, 1L, ChannelType.EMAIL, null, "U", "a", "t", null, null, "ACTIVE", null, t, t));
    verify(mapper, times(1))
        .insertWithoutWorkspace(
            anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(mapper, times(2))
        .upsert(
            anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any());
  }

  @Test
  @DisplayName("signing secret 이 NULL 로 저장된 행은 빈 문자열로 읽힌다 — 다른 열은 그대로")
  void findById_nullSigningSecretReadsEmpty() {
    SlackWorkspaceMapper mapper = mock(SlackWorkspaceMapper.class);
    var raw =
        new SlackWorkspaceRepository.SlackWorkspace(
            9L, "T1", null, "UB", "tok", null, null, null, null);
    when(mapper.selectById(9L)).thenReturn(raw);

    var w =
        new MyBatisSlackWorkspaceRepository(mapper, new TiberoPlatformDbErrors())
            .findById(9L)
            .orElseThrow();

    assertThat(w.signingSecretEnc()).isEqualTo("");
    assertThat(w.teamName()).isNull();
    assertThat(w.previousSigningSecretEnc()).isNull();
    assertThat(w.botTokenEnc()).isEqualTo("tok");
  }

  @Test
  @DisplayName("세 구현 모두 클래스 단위 @Transactional 이 있다 — jOOQ 구현과 같다")
  void classesAreTransactional() {
    assertThat(MyBatisOAuthStateRepository.class.getAnnotation(Transactional.class)).isNotNull();
    assertThat(MyBatisUserChannelPreferenceRepository.class.getAnnotation(Transactional.class))
        .isNotNull();
    assertThat(MyBatisSlackWorkspaceRepository.class.getAnnotation(Transactional.class))
        .isNotNull();
  }
}
