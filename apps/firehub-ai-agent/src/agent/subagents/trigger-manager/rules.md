# trigger-manager — 규칙 참조

## 트리거 유형별 config 스키마

### SCHEDULE

```json
{
  "cronExpression": "0 2 * * *"
}
```

- `cronExpression`: 표준 5필드 cron (`분 시 일 월 요일`). 6필드(초 포함) 금지.
- 사용자가 자연어("매일 오전 2시")로 말하면 cron으로 변환해준다.

**자주 쓰는 cron 표현:**

| 자연어 | cronExpression |
|--------|---------------|
| 매일 오전 2시 | `0 2 * * *` |
| 매주 월요일 오전 9시 | `0 9 * * 1` |
| 매시간 정각 | `0 * * * *` |
| 매일 자정 | `0 0 * * *` |
| 매주 평일 오전 8시 | `0 8 * * 1-5` |
| 15분마다 | `*/15 * * * *` |

### API

```json
{}
```

- config는 빈 객체. 서버가 토큰을 자동 생성한다.
- 생성 후 토큰은 대화에 노출하지 않는다. 파이프라인 상세 화면에서 확인 안내.

### PIPELINE_CHAIN

```json
{
  "upstreamPipelineId": 42,
  "condition": "SUCCESS"
}
```

- `upstreamPipelineId`: 상위 파이프라인 ID (필수)
- `condition`: `SUCCESS` | `FAILURE` | `ANY` (기본값: `SUCCESS`)
- 순환 참조 방지: 동일 파이프라인을 자기 자신의 상위로 설정 불가. 최대 체인 깊이: 10.

### WEBHOOK

```json
{
  "secret": "선택적-시크릿-값"
}
```

- `secret`: 선택 필드. 제공 시 서버가 AES-256-GCM으로 암호화.
- 웹훅 URL과 시크릿은 파이프라인 상세 화면에서 확인 안내.

### DATASET_CHANGE

```json
{
  "datasetId": 7
}
```

- `datasetId`: 모니터링할 데이터셋 ID (필수)
- 서버가 30초마다 행 수 변화를 폴링한다.

## 보안 규칙 요약

| 항목 | 규칙 |
|------|------|
| API 트리거 토큰 | 생성 응답에 포함되더라도 대화에 출력 금지 |
| WEBHOOK 시크릿 | 입력받아 config에 전달만. 확인 메시지에 포함 금지 |
| 삭제 전 확인 | 이름 명시 + 명시적 사용자 확인 필수 |
| ID 단독 삭제 | 금지. 항상 이름을 함께 표시 |

## update_trigger — 변경 가능 필드

| 필드 | 타입 | 설명 |
|------|------|------|
| `name` | string | 트리거 이름 변경 |
| `isEnabled` | boolean | 활성화(true) / 비활성화(false) |
| `description` | string | 설명 변경 |
| `config` | object | 유형별 config 전체 교체 |

**주의**: `triggerType`은 수정 불가. 유형 변경이 필요하면 삭제 후 재생성.
