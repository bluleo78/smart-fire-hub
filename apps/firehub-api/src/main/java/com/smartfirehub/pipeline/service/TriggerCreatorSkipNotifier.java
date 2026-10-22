package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.security.MemberEligibility;
import com.smartfirehub.notification.service.NotificationService;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.user.repository.UserRepository;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트리거를 만든 사람의 자격 문제(정지·제거·권한 회수)로 트리거를 건너뛸 때, 파이프라인 주인과 워크스페이스 관리자에게 <b>처음 한 번만</b> 알린다(WD-169).
 *
 * <p>왜: 건너뜀은 이벤트 로그에만 남아 아무도 모르는 채 트리거가 매 주기 조용히 쉬었다. 그렇다고 매 주기 알리면 예약 트리거(매분 등)가 알림을 쏟아낸다 — 그래서 그
 * 트리거의 직전 이벤트가 같은 사유의 건너뜀이면 이미 알린 것으로 보고 보내지 않는다. 정상 실행이나 다른 사유가 한 번 끼면 다음 건너뜀에서 다시 알린다. 별도 상태 컬럼
 * 없이 기존 trigger_event 이력으로 판단한다(마이그레이션 없음).
 *
 * <p>트리거는 끄지 않는다 — 다시 활성화·재초대·권한 복구하면 다음 주기부터 저절로 실행된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerCreatorSkipNotifier {

  private final TriggerEventRepository triggerEventRepository;
  private final PipelineRepository pipelineRepository;
  // 활성 관리자 선정 — 마지막 ADMIN 잠금 방지가 쓰는 「활성 ADMIN」 정의(countActiveAdmins)와 같은 정의다
  private final UserRepository userRepository;
  private final MemberEligibility memberEligibility;
  private final NotificationService notificationService;

  /**
   * 이번 건너뜀이 「처음」이면 알림을 커밋 뒤에 보내도록 예약한다. <b>이번 건너뜀 이벤트를 남기기 전에</b> 불러야 한다 — 남긴 뒤에 부르면 방금 남긴 이벤트가 직전
   * 이벤트로 읽혀 늘 「이미 알림」으로 판정된다.
   *
   * <p>DB 가 아닌 실패(이름 조합·전송 등)는 삼키고 로그만 남겨 건너뜀 기록(호출자의 이벤트 저장)을 막지 않는다. 단 조회 쿼리 자체가 실패하면 호출자 트랜잭션에
   * 합류한 리포지토리가 그 트랜잭션을 롤백 전용으로 만들어(PostgreSQL 도 트랜잭션을 중단한다) 건너뜀 기록까지 함께 되돌려진다 — 여기서 삼켜도 막을 수 없다.
   *
   * @param status 만든 사람의 자격 판정(INACTIVE 또는 NO_PERMISSION)
   * @param reason 이번에 남길 건너뜀 이벤트의 사유(직전 이벤트 사유와 비교한다)
   */
  public void notifyIfFirstSkip(
      TriggerResponse trigger, long tenantId, MemberEligibility.Status status, String reason) {
    try {
      // 직전 이벤트가 같은 사유의 건너뜀이면 이미 알렸다 — 다시 보내지 않는다
      boolean alreadyNotified =
          triggerEventRepository
              .findLatestByTrigger(trigger.id())
              .map(e -> "SKIPPED".equals(e.eventType()) && reason.equals(e.reason()))
              .orElse(false);
      if (alreadyNotified) {
        return;
      }

      Set<Long> recipients = recipients(trigger, tenantId);
      if (recipients.isEmpty()) {
        log.info("트리거 {} 건너뜀 알림 대상 없음 (pipeline={})", trigger.id(), trigger.pipelineId());
        return;
      }
      String pipelineName =
          pipelineRepository.findNameById(trigger.pipelineId()).orElse("#" + trigger.pipelineId());
      String cause =
          status == MemberEligibility.Status.INACTIVE
              ? "트리거를 만든 사람이 이 워크스페이스에서 정지·제거되었습니다"
              : "트리거를 만든 사람에게 「파이프라인 실행」 권한이 없습니다";

      // 보내는 일은 커밋 뒤에 한다 — 건너뜀 이벤트 저장이 롤백되면 알림만 나가고 이력에는 없는 상태가 되고, 그러면 다음 주기에 같은 알림이 또
      // 나간다. 트랜잭션 밖에서 불렸으면 바로 보낸다.
      Runnable send =
          () ->
              notificationService.notifyTriggerSkipped(
                  tenantId,
                  recipients,
                  trigger.pipelineId(),
                  trigger.id(),
                  trigger.name(),
                  pipelineName,
                  cause,
                  reason);
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCommit() {
                sendQuietly(send, trigger.id());
              }
            });
      } else {
        sendQuietly(send, trigger.id());
      }
    } catch (RuntimeException e) {
      log.warn("트리거 {} 건너뜀 알림 준비 실패 — 알리지 않음: {}", trigger.id(), e.getMessage());
    }
  }

  /**
   * 받을 사람: 파이프라인 주인(pipeline.created_by) + 이 워크스페이스의 활성 관리자. 둘 다 이 워크스페이스 ACTIVE 멤버(계정 활성 포함)만 남긴다
   * — 정지·제거된 사람에게 이 워크스페이스 소식을 보내지 않는다. 그래서 만든 사람 본인이 주인이고 정지·제거된 경우 그 사람은 저절로 빠진다(권한만 회수된 경우에는 여전히
   * 멤버라 주인이면 받는다 — 자기 권한이 없다는 사실을 알아야 한다).
   */
  private Set<Long> recipients(TriggerResponse trigger, long tenantId) {
    Set<Long> candidates = new LinkedHashSet<>();
    pipelineRepository.findCreatedByIdById(trigger.pipelineId()).ifPresent(candidates::add);
    candidates.addAll(userRepository.findActiveAdminIds(tenantId));
    Set<Long> recipients = new LinkedHashSet<>();
    for (Long userId : candidates) {
      if (memberEligibility.isActiveMember(userId, tenantId)) {
        recipients.add(userId);
      }
    }
    return recipients;
  }

  private void sendQuietly(Runnable send, Long triggerId) {
    try {
      send.run();
    } catch (RuntimeException e) {
      log.warn("트리거 {} 건너뜀 알림 전송 실패: {}", triggerId, e.getMessage());
    }
  }
}
