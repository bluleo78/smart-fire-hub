# FireHub AI Agent Service

Smart Fire Hub의 AI 어시스턴트 서비스입니다. Claude CLI와 MCP(Model Context Protocol)를 사용하여 사용자 요청을 처리합니다.

## 기능

- Claude CLI 래퍼를 통한 AI 대화 관리
- FireHub API와 연동되는 MCP 서버
- SSE(Server-Sent Events)를 통한 실시간 스트리밍 응답
- 세션 관리 및 대화 이어가기
- 내부 서비스 인증

## 설치

```bash
pnpm install
```

## 환경 설정

`.env` 파일을 생성하고 다음 변수를 설정하세요:

```env
PORT=3001
INTERNAL_SERVICE_TOKEN=your-internal-service-token
API_BASE_URL=http://localhost:8080/api/v1
CLAUDE_CLI_TIMEOUT=300000
MAX_TURNS=10
```

## 개발

```bash
# 개발 서버 실행 (hot reload)
pnpm dev

# 빌드
pnpm build

# 프로덕션 실행
pnpm start

# 타입 체크
pnpm typecheck
```

## API 엔드포인트

### Health Check
```
GET /agent/health
```

### Chat (SSE)
```
POST /agent/chat
Content-Type: application/json
Authorization: Internal {INTERNAL_SERVICE_TOKEN}

{
  "message": "사용자 메시지",
  "sessionId": "optional-session-id",
  "userToken": "user-jwt-token"
}
```

응답: SSE 스트림
- `event: text_delta` - AI 응답 텍스트 조각
- `event: tool_use` - 도구 사용
- `event: tool_result` - 도구 실행 결과
- `event: done` - 완료 (sessionId 포함)
- `event: error` - 오류

## MCP 도구

FireHub MCP 서버는 다음 10개 도구를 제공합니다:

1. `list_datasets` - 데이터셋 목록 조회
2. `get_dataset` - 데이터셋 상세 조회
3. `query_dataset_data` - 데이터셋 데이터 조회
4. `get_dataset_columns` - 데이터셋 컬럼 정보
5. `list_pipelines` - 파이프라인 목록
6. `get_pipeline` - 파이프라인 상세
7. `execute_pipeline` - 파이프라인 실행
8. `get_execution_status` - 실행 상태 조회
9. `list_imports` - 임포트 이력
10. `get_dashboard` - 대시보드 통계

## 아키텍처

- `src/index.ts` - Express 서버 진입점
- `src/routes/chat.ts` - SSE 채팅 엔드포인트
- `src/agent/claude-cli.ts` - Claude CLI 래퍼
- `src/agent/stream-parser.ts` - stream-json 파서
- `src/agent/system-prompt.ts` - AI 시스템 프롬프트
- `src/mcp/firehub-mcp-server.ts` - MCP 서버 구현
- `src/mcp/api-client.ts` - FireHub API 클라이언트
- `src/middleware/auth.ts` - 내부 서비스 인증
- `mcp-config.json` - MCP 서버 설정 템플릿

## 요구사항

- Node.js 18+
- Claude CLI (`npm install -g @anthropic-ai/claude-cli`)
- FireHub API 서버 실행 중
