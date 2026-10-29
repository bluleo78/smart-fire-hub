package com.smartfirehub.notification.admin;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.notification.repository.NotificationOutboxRepository.NotificationOutboxRow;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 전용 Outbox 관측·조작 API.
 *
 * <p>/admin/notifications/stuck — pending이 오래 묶인 행 조회. /admin/notifications/{id}/retry — 영구
 * 실패·stuck 행 수동 재투입.
 *
 * <p>권한은 {@code notification:admin}(V151, WD-433). 조회·재투입은 리포지토리 트랜잭션이 심는 테넌트 GUC(RLS)로 요청자의 워크스페이스
 * 행만 다룬다.
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
@RequirePermission("notification:admin")
public class NotificationAdminController {

  private final NotificationOutboxRepository outboxRepo;

  public NotificationAdminController(NotificationOutboxRepository outboxRepo) {
    this.outboxRepo = outboxRepo;
  }

  /**
   * stuck pending 행 조회.
   *
   * @param olderThan 기간(기본 PT5M = 5분). PT30M, PT1H 등. 형식이 틀리면 공용 타입 불일치 처리기가 400 으로 돌려준다(예전에는 직접
   *     파싱하다 500).
   */
  @GetMapping("/stuck")
  public List<NotificationOutboxRow> stuck(
      @RequestParam(name = "olderThan", defaultValue = "PT5M") Duration olderThan) {
    return outboxRepo.findStuckPending(cutoff(olderThan));
  }

  /**
   * 특정 outbox 행을 PENDING으로 되돌리고 즉시 재시도.
   *
   * <p>영구 실패·묶인 대기 행만 되돌린다. 없는 id 와 이미 보냈거나 취소·발송 중인 알림은 404 로 알린다 — 같은 알림이 두 번 나가지 않게 하는 조건을 리포지토리
   * UPDATE 한 번에 걸어, 상태를 따로 읽고 판정하는 사이에 상태가 바뀌는 틈을 두지 않는다.
   */
  @PostMapping("/{id}/retry")
  public ResponseEntity<Void> retry(@PathVariable("id") long id) {
    if (outboxRepo.requeueForRetry(id) == 0) {
      throw new CodedApiException(
          HttpStatus.NOT_FOUND,
          "NOTIFICATION_NOT_RETRYABLE",
          "다시 보낼 수 있는 알림이 없습니다. 없는 알림이거나 이미 보냈거나 취소된 알림입니다.");
    }
    return ResponseEntity.noContent().build();
  }

  /** olderThan 을 「이보다 먼저 만든 행」 기준 시각으로 바꾼다. 음수(미래 기준 목록)와 시각 범위를 넘는 큰 값은 400 으로 거절한다. */
  private static Instant cutoff(Duration olderThan) {
    if (olderThan.isNegative()) {
      throw new IllegalArgumentException("olderThan 은 0 이상이어야 합니다: " + olderThan);
    }
    try {
      return Instant.now().minus(olderThan);
    } catch (DateTimeException | ArithmeticException e) {
      throw new IllegalArgumentException("olderThan 이 너무 큽니다: " + olderThan);
    }
  }
}
