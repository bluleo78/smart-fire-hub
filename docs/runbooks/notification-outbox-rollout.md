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

## 8. Stage 2 활성화 (KAKAO/SLACK outbound + `/settings/channels`)

Stage 1 Outbox 인프라가 카나리 1주간 안정된 뒤 Stage 2로 진행. Stage 2는 추가 채널(KAKAO/SLACK)과 사용자 연동 UX를 포함하며 외부 OAuth 자격 증명 없이는 활성화되지 않는다.

### 8.1 사전 준비 — 외부 앱 등록

**Kakao Developers:**
1. https://developers.kakao.com/ → 내 애플리케이션 → 앱 등록
2. 앱 설정 → 플랫폼 → Web → 사이트 도메인 등록
3. 카카오 로그인 → Redirect URI: `https://{domain}/api/v1/oauth/kakao/callback`
4. 동의 항목 → `talk_message`(카카오톡 메시지 전송) 활성화
5. REST API 키(client_id) + Client Secret(보안 탭에서 생성) 확보

**Slack App:**
1. https://api.slack.com/apps → Create New App → From scratch
2. OAuth & Permissions → Bot Token Scopes: `chat:write`, `im:write`, `im:history`, `users:read`, `reactions:write`, `app_mentions:read`
3. OAuth & Permissions → Redirect URLs: `https://{domain}/api/v1/oauth/slack/callback`
4. Basic Information → Signing Secret 확보 (Stage 3 inbound 서명 검증용 — Stage 2는 사용하지 않음)
5. 앱 설치 후 Client ID, Client Secret 확보

### 8.2 환경 변수

```bash
# Kakao
KAKAO_CLIENT_ID=<REST API 키>
KAKAO_CLIENT_SECRET=<Client Secret>
KAKAO_REDIRECT_URI=https://app.smartfirehub.com/api/v1/oauth/kakao/callback

# Slack
SLACK_CLIENT_ID=<Client ID>
SLACK_CLIENT_SECRET=<Client Secret>
SLACK_REDIRECT_URI=https://app.smartfirehub.com/api/v1/oauth/slack/callback
```

환경변수 주입 후 `ChannelSettingsService`가 OAuth start URL을 발급하게 된다. Kakao/Slack 미설정 시 `/settings/channels`에서 해당 카드는 "설정 필요" 상태로 표시(관리자 안내).

### 8.3 관리자 Slack 워크스페이스 설치 (1회)

1. ADMIN 사용자로 로그인 → `/api/v1/oauth/slack/start` 호출 (또는 `/settings/channels`에서 Slack 카드 → "워크스페이스 연결" 버튼)
2. Slack OAuth 승인 → callback → `slack_workspace` 테이블에 bot_token 암호화 저장
3. DB 확인:
   ```sql
   SELECT id, team_id, team_name, bot_user_id, installed_by_user_id FROM slack_workspace;
   ```

### 8.4 사용자별 연동 (각 수신자)

- Kakao: `/settings/channels` → 카카오 카드 → "연동하기" → Kakao OAuth → `user_channel_binding`(channel_type=KAKAO, access/refresh 암호화)
- Slack: `/settings/channels` → Slack 카드 → `POST /api/v1/oauth/slack/link-user` `{workspaceId, slackUserId}` → 봇이 DM ping 전송 → `user_channel_binding`(channel_type=SLACK, workspace_id, external_user_id) 생성

### 8.5 dev → stage → 운영 단계

- **dev 48h**: 개발자 본인 Kakao/Slack 연동 후 ProactiveJob 실행 → 카톡/슬랙 수신 확인. `notification_outbox`에 KAKAO/SLACK 행 기록 확인.
- **stage 1주**: 다수 사용자 연동 + ChannelRecipientEditor에서 4채널 선택 → deliver 성공률/TransientFailure/PermanentFailure 분포 관찰 (Task 13 메트릭).
- **운영**: Kakao 일일 발송량 제한(앱당 쿼터) 모니터링. Slack rate limit(TIER 3) 주의.

### 8.6 이상 시 회귀

- Kakao `access_token` 대량 만료 → `UserChannelBinding.status=TOKEN_EXPIRED` + `/settings/channels` "재인증 필요" 배지 → 사용자가 재연동. 채널 opt-out 또는 EMAIL/CHAT fallback으로 자동 유지.
- Slack invalid_auth (봇 토큰 취소) → `slack_workspace` 재설치 필요. 즉시 조치: `DELETE /api/v1/oauth/slack/revoke` 후 관리자가 재설치.
- 광범위 장애 시 특정 채널만 비활성화: `notification.channels.{kakao|slack}.enabled=false` flag 추가(향후) — 현재는 bindings DELETE + preference false.

## 9. Slack Event Subscriptions 활성화 (Stage 3 — Slack Inbound)

Stage 2 Slack outbound가 운영에서 안정된 뒤 Stage 3 inbound를 활성화한다.

### 9.1 사전 준비 — V55 마이그레이션 확인

```bash
docker exec smart-fire-hub-db-1 psql -U app -d smartfirehub -c \
  "SELECT version FROM flyway_schema_history WHERE version = '55'"
```

기대: `55` 1행. `ai_session`에 Slack 컨텍스트 컬럼(`channel_source`, `slack_team_id`, `slack_channel_id`, `slack_thread_ts`) 및 `uk_ai_session_slack_thread` UNIQUE INDEX가 존재해야 한다.

```bash
docker exec smart-fire-hub-db-1 psql -U app -d smartfirehub -c \
  "SELECT column_name FROM information_schema.columns WHERE table_name='ai_session' AND column_name LIKE 'slack%'"
```

### 9.2 Slack App — Event Subscriptions 설정

1. https://api.slack.com/apps → 앱 선택 → **Event Subscriptions** 메뉴
2. Enable Events: **ON**
3. Request URL: `https://{domain}/api/v1/channels/slack/events`
   - Slack이 즉시 `url_verification` challenge를 전송. 서버가 `{"challenge": "..."}` 응답하면 "Verified" 표시.
4. **Subscribe to bot events** 탭 → 이벤트 추가:
   - `message.im` — DM 메시지 수신
   - `app_mention` — 채널 내 @멘션 수신
5. Save Changes → **Reinstall App** (권한 변경 후 재설치 필요)

### 9.3 환경 변수 — Signing Secret 설정

```bash
# Basic Information → Signing Secret
SLACK_SIGNING_SECRET=<Signing Secret>

# (선택) Signing Secret 교체 시 그레이스 기간 지원
SLACK_PREVIOUS_SIGNING_SECRET=<이전 Signing Secret>
SLACK_PREVIOUS_SIGNING_SECRET_EXPIRES_AT=<Unix timestamp (epoch seconds)>
```

`application.yml` 매핑:
```yaml
notification:
  slack:
    signing_secret: ${SLACK_SIGNING_SECRET}
    previous_signing_secret: ${SLACK_PREVIOUS_SIGNING_SECRET:}
    previous_signing_secret_expires_at: ${SLACK_PREVIOUS_SIGNING_SECRET_EXPIRES_AT:0}
```

### 9.4 동작 확인

**URL Verification:**
```bash
# Slack이 challenge를 보낼 때 서버가 200 + challenge JSON을 반환하는지 확인
curl -s -X POST https://{domain}/api/v1/channels/slack/events \
  -H "Content-Type: application/json" \
  -d '{"type":"url_verification","challenge":"test-challenge-value"}'
# 기대: {"challenge":"test-challenge-value"}
```

**DM 테스트:**
1. Slack 워크스페이스에서 봇에게 DM 전송
2. API 로그에서 다음 항목 확인:
   ```
   slack inbound — 응답 완료 (team=T..., ts=..., sessionId=...)
   ```
3. Slack DM 스레드에 AI 응답이 도착하는지 확인
4. 미연동 사용자가 DM 전송 시 ephemeral 안내 메시지(`Smart Fire Hub 웹에서 먼저 계정 연동을 진행해주세요`) 수신 확인

**메트릭 확인:**
```bash
curl -s https://{domain}/actuator/prometheus | grep slack_inbound
# 기대 항목:
# slack_inbound_received_total
# slack_inbound_processing_duration_seconds_*
# slack_inbound_unmapped_user_total
```

### 9.5 slackInboundExecutor 풀 설정

기본값: core=3, max=5, queue=20, CallerRunsPolicy (queue 초과 시 HTTP 스레드에서 동기 실행).
AI 호출이 60초 blocking이므로 동시 요청이 많은 경우 풀을 확장할 것:

```yaml
notification:
  slack:
    inbound_executor:
      core_size: 5     # 기본 3
      max_size: 10     # 기본 5
      queue_capacity: 50  # 기본 20
```

> **참고:** `slack_inbound_processing_duration_seconds_max` 가 30초 이상 지속 상승하면 ai-agent 타임아웃 또는 풀 포화 신호. ai-agent 상태와 풀 크기를 동시에 점검할 것.

### 9.6 이상 시 조치

| 증상 | 원인 | 조치 |
|------|------|------|
| 401 응답 | Signing Secret 불일치 또는 타임스탬프 ±5분 초과 | `SLACK_SIGNING_SECRET` 확인, 서버 시각 동기화 (NTP) |
| 봇 무응답 | ai-agent 다운 또는 60초 타임아웃 | ai-agent 상태 확인, `:warning:` reaction + ephemeral 메시지가 사용자에게 표시되는지 확인 |
| 미연동 사용자 반복 증가 | `slack_inbound_unmapped_user_total` 급증 | `/settings/channels` 연동 안내, binding 누락 여부 DB 확인 |
| 세션 중복 충돌 | `uk_ai_session_slack_thread` UNIQUE 위반 | 동시 요청(Slack 재전송)으로 인한 경합 — idempotency key 보강 검토 |

## 관련 문서

- 설계: `docs/superpowers/specs/2026-04-18-channel-abstraction-design.md`
- Stage 1 계획: `docs/superpowers/plans/2026-04-18-channel-stage-1-outbox.md`
- Stage 2 계획: `docs/superpowers/plans/2026-04-18-channel-stage-2-external-outbound.md`
- Stage 3(Slack inbound): 후속 plan
