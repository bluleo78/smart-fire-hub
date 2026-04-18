package com.smartfirehub.notification.admin;

import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.notification.repository.NotificationOutboxRepository.NotificationOutboxRow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 전용 Outbox 관측·조작 API.
 *
 * <p>/admin/notifications/stuck — pending이 오래 묶인 행 조회.
 * /admin/notifications/{id}/retry — 영구 실패·stuck 행 수동 재투입.
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
@PreAuthorize("hasRole('ADMIN')")
public class NotificationAdminController {

    private final NotificationOutboxRepository outboxRepo;

    public NotificationAdminController(NotificationOutboxRepository outboxRepo) {
        this.outboxRepo = outboxRepo;
    }

    /**
     * stuck pending 행 조회.
     *
     * @param olderThan ISO-8601 Duration (기본 PT5M = 5분). PT30M, PT1H 등.
     */
    @GetMapping("/stuck")
    public List<NotificationOutboxRow> stuck(
            @RequestParam(name = "olderThan", defaultValue = "PT5M") String olderThan) {
        Duration d = Duration.parse(olderThan);
        return outboxRepo.findStuckPending(Instant.now().minus(d));
    }

    /** 특정 outbox 행을 PENDING으로 되돌리고 즉시 재시도. */
    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable("id") long id) {
        outboxRepo.requeueForRetry(id);
        return ResponseEntity.noContent().build();
    }
}
