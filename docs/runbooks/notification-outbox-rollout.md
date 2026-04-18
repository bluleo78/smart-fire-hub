# Notification Outbox Rollout Runbook

Stage 1 Outbox 인프라 활성화 절차. Dispatcher + Worker + Channel SPI가 이미 코드에 포함돼 있으나 기본값 `notification.outbox.enabled=false`라 직접 호출 경로를 유지 중. 아래 단계로 신중히 전환한다.

## 0. 사전 점검

- DB 마이그레이션 V50~V54 적용 확인:
  ```bash
  docker exec smart-fire-hub-db-1 psql -U app -d smartfirehub -c \
    "SELECT version FROM flyway_schema_history WHERE version::int >= 50 ORDER BY version"
  ```
  기대: 50, 51, 52, 53, 54 모두 표시.

- 관련 테이블 존재 확인:
  ```bash
  docker exec smart-fire-hub-db-1 psql -U app -d smartfirehub -c \
    "\dt notification_outbox user_channel_binding user_channel_preference slack_workspace oauth_state"
  ```

## 1. 로컬/dev 환경 활성화 (24시간)

환경 변수 설정:
```yaml
# application-local.yml 또는 환경변수
notification:
  outbox:
    enabled: true          # true = Dispatcher 경로 사용
  worker:
    poll_interval_ms: 30000
    batch_size: 20
    listen_notify: true
    zombie_age_minutes: 5
```

또는 `NOTIFICATION_OUTBOX_ENABLED=true` 환경변수.

API 기동 후 확인:
- `ProactiveJob` 실행 시 `notification_outbox`에 행이 INSERT 되는지 SQL로 확인.
- SSE 이벤트 `PROACTIVE_MESSAGE`가 클라이언트에 정상 도달하는지 브라우저 확인.
- 이메일 SMTP 설정이 있으면 실제 수신 확인. 없으면 SMTP 미설정 PermanentFailure가 기록되는지 확인.

## 2. Stage 환경 (72시간)

dev에서 이상 없으면 stage에서 동일 flag 적용. 다음 메트릭(현재는 로그 기반) 관찰:
- pending 상태가 5분 이상 남은 행: `SELECT count(*) FROM notification_outbox WHERE status='PENDING' AND next_attempt_at < now() - interval '5 min'`
- permanent_failure 증가율
- 좀비 회복(`OutboxSweeper recovered N zombie rows` 로그)

## 3. 운영 단일 인스턴스 카나리 (1주)

운영 인스턴스가 다중일 경우 한 대만 flag ON. 지표 비교로 회귀 감지. 이상 시 즉시 `NOTIFICATION_OUTBOX_ENABLED=false`로 회귀.

## 4. 운영 전체 활성화

1주 카나리 안정화 후 전체 인스턴스에서 flag ON. 한 주 더 관찰 후 다음 PR에서 구 `DeliveryChannel` 경로 제거(별도 작업).

## 5. 이상 시 즉시 회귀

```bash
# 특정 인스턴스
export NOTIFICATION_OUTBOX_ENABLED=false
# 재시작
docker compose restart api
```

flag OFF가 되면 즉시 기존 `List<DeliveryChannel>` 직접 호출 경로로 복귀하며 기존 동작과 100% 동등.

## 6. Stuck 행 수동 처리

관리자가 stuck pending을 찾는 SQL:
```sql
SELECT id, channel_type, recipient_user_id, attempt_count, last_error, next_attempt_at, created_at
FROM notification_outbox
WHERE status='PENDING' AND next_attempt_at < now() - interval '5 min'
ORDER BY created_at;
```

강제 재시도(attempt_count=0으로 리셋):
```sql
UPDATE notification_outbox
SET status='PENDING', attempt_count=0, next_attempt_at=now(),
    claimed_at=NULL, claimed_by=NULL, last_error=NULL, last_error_at=NULL
WHERE id = <id>;
```

> Task 13에서 이 작업을 `POST /admin/notifications/{id}/retry` 엔드포인트로 수동 GUI화 예정.

## 7. 체크리스트

| 단계 | 완료 조건 |
|---|---|
| dev 24h | 신규 회귀 0, pending stuck 0 |
| stage 72h | 위 조건 + 이메일 실제 수신 검증 |
| 운영 카나리 1주 | 비교 지표 차이 없음 |
| 운영 전체 | 1주 추가 안정 관찰 후 구 경로 제거 PR |

## 관련 문서

- 설계: `docs/superpowers/specs/2026-04-18-channel-abstraction-design.md`
- 구현 계획: `docs/superpowers/plans/2026-04-18-channel-stage-1-outbox.md`
- Stage 2(KAKAO/SLACK outbound + `/settings/channels`): 후속 plan
- Stage 3(Slack inbound): 후속 plan
