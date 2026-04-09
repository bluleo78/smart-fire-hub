# 에이전트 팀 워크플로

업무 지시 시 `/task-start` 스킬을 실행한다. 워크플로 정의는 `.claude/agents/project-leader.md` 참조.

## 에이전트 구성

| 에이전트 | 역할 |
|---------|------|
| **Project Leader** | 업무 총괄 — 분석→설계→구현→검증→완료 전체 오케스트레이션 |
| **Analyst** | 업무 분석/조사/기획 — 요구사항 추출, 영향 범위, 수용 기준 |
| **Architect** | 아키텍처 설계 리드 — 앱 간 연동, API 스펙, 코드 리뷰 |
| **Backend Developer** | firehub-api 구현 — Java/Spring Boot/jOOQ |
| **Frontend Developer** | firehub-web 구현 — React/TypeScript/Playwright |
| **AI Agent Developer** | firehub-ai-agent 구현 — Node.js/Claude SDK/MCP |
| **UI/UX Designer** | 화면 설계 리드 — 디자인 시스템, UI/UX, 접근성 |
| **QA Tester** | 통합 검증 리드 — 빌드/테스트/회귀, 앱 간 연동 검증 |
| **Project Manager** | 로드맵 관리 — 상태 추적, 사용자 승인, 완료 기록 |

## 업무 흐름

```
사용자 지시 → Project Leader
  → Phase 1: 분석 (Analyst 리드)
  → Phase 2a: 아키텍처 설계 (Architect 리드)
  → Phase 2b: 화면 설계 (Designer 리드, 병렬)
  → Phase 3: 계획 (Project Leader 리드)
  → Phase 4: 구현 (각 Developer 리드, 병렬)
  → Phase 5: 검증 (QA Tester 리드)
  → Phase 6: 완료 (Project Leader → PM 로드맵 업데이트)
```

## 계획 수립 원칙

- **스킬 활용**: `/plan` 또는 `/ralplan` 스킬 사용
- **구체적으로**: 파일·함수 단위, 입출력 스펙, 에러 케이스까지 명시
- **검증 가능하게**: 모든 항목에 검증 기준(TC 목록, 수동 시나리오) 포함 필수
- **구현 후 검증**: backend → 통합 테스트, frontend → 빌드+타입체크+스크린샷, ai-agent → 단위 테스트
