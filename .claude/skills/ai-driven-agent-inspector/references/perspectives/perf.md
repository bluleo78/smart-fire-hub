# Perspective: perf (성능 — 지연/토큰)

응답 지연, 토큰 사용량, maxTurns 도달, 도구 반복 호출을 본다.

## 측정 지표 (시나리오마다 모두 수집)

- **총 지연**: SSE 첫 init 이벤트 ~ done 이벤트 사이 (ms)
- **첫 텍스트 토큰까지의 시간 (TTFT)**: init ~ 첫 text delta
- **토큰**: done 이벤트의 `usage.input_tokens`, `output_tokens`, `cache_read_input_tokens`
- **턴 수**: turn 이벤트 카운트
- **도구 호출 수**: tool_use 이벤트 카운트
- **동일 도구 반복 수**: 같은 name 연속 호출

## 임계치 (초안 — 실측 후 조정)

| 지표 | 정상 | Warn | Critical |
|------|------|------|----------|
| 단순 조회 총 지연 | < 8s | 8~20s | > 20s |
| 복합 작업 총 지연 | < 30s | 30~60s | > 60s |
| TTFT | < 3s | 3~8s | > 8s |
| output tokens (단순) | < 800 | 800~2000 | > 2000 |
| 턴 수 | < 4 | 4~7 | maxTurns 도달 |
| 동일 도구 반복 | 0~1 | 2 | 3+ |

## 시나리오 템플릿

각 subagent의 핵심 use case 12개를 perf 시각으로 점검:

- 단순 조회 (list, get) × 2
- 복합 변경 (create + import) × 2
- 멀티턴 대화 (3턴 이상) × 2
- 도구 결과가 큰 경우 (CSV preview 100+행) × 1
- maxTurns 경계 (복잡한 multi-step) × 2
- cache 효과 검증 (같은 system prompt로 연속 호출) × 1
- 에러 후 재시도 흐름 × 2

## 검증 방법

```bash
# 지연
START=$(grep -m1 "^event: init$" -A1 trace.sse | tail -1 | jq -r '.timestamp')
END=$(grep "^event: done$" -A1 trace.sse | tail -1 | jq -r '.timestamp')

# 토큰
grep "^event: done$" -A1 trace.sse | tail -1 | jq '.usage'

# 도구 호출 카운트
grep -c "^event: tool_use$" trace.sse
```

## 결함 등급

- **Critical**: maxTurns 도달로 작업 미완료, 동일 도구 5+ 반복 (loop)
- **Major**: 임계치 Critical 구간 도달
- **Minor**: Warn 구간 지속, 명백한 redundant call
