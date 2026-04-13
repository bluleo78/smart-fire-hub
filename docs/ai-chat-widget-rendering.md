# AI 채팅 위젯 렌더링 가이드

> firehub-web의 AI 채팅 화면에서 텍스트 외에 차트, 테이블, 데이터셋 카드 등 **위젯을 렌더링하는 전체 구조와 흐름**을 설명하는 문서.
> Claude API → Agent SDK → AI Agent(Node.js) → SSE → Frontend(React) 각 레이어에서 어떤 역할을 담당하는지 포함.

---

## 목차

1. [개요 — 왜 위젯이 필요한가](#1-개요--왜-위젯이-필요한가)
2. [레이어별 역할 분담](#2-레이어별-역할-분담)
3. [전체 데이터 흐름](#3-전체-데이터-흐름)
4. [SSE 스트림 이벤트 프로토콜](#4-sse-스트림-이벤트-프로토콜)
5. [ContentBlocks — 텍스트와 위젯의 순서 보장](#5-contentblocks--텍스트와-위젯의-순서-보장)
6. [위젯 레지스트리](#6-위젯-레지스트리)
7. [메시지 렌더링 로직](#7-메시지-렌더링-로직)
8. [위젯 컴포넌트 구조](#8-위젯-컴포넌트-구조)
9. [표시 모드별 동작](#9-표시-모드별-동작)
10. [로딩 · 에러 처리](#10-로딩--에러-처리)
11. [새 위젯 추가하는 법](#11-새-위젯-추가하는-법)

---

## 1. 개요 — 왜 위젯이 필요한가

AI 에이전트는 도구(Tool)를 호출하여 데이터를 가져온다. 단순히 텍스트로 "10개 항목이 있습니다"라고 말하는 것보다, 실제 테이블이나 차트로 보여주는 것이 훨씬 유용하다.

AI 에이전트가 `show_chart`, `show_table` 같은 **표시 전용 도구(display tool)**를 호출하면, 프론트엔드가 그 입력 데이터를 받아 인터랙티브 위젯으로 렌더링한다.

```
AI 에이전트: "show_table 도구를 호출하고 데이터를 전달합니다"
                              ↓
프론트엔드: "이 도구는 TableWidget에 매핑됩니다 → 테이블 컴포넌트 렌더링"
```

---

## 2. 레이어별 역할 분담

위젯 하나가 화면에 나타나기까지 **4개 레이어**를 통과한다. 각 레이어가 무엇을 담당하는지 명확히 구분하는 것이 전체 구조를 이해하는 핵심이다.

```
┌─────────────────────────────────────────────────────────────────────┐
│  Layer 1: Claude API (Anthropic)                                    │
│  역할: 텍스트 생성 + 도구 호출 결정 + 스트리밍 응답                      │
└─────────────────────────────────────────────────────────────────────┘
                              ↓ SDK 메시지 (assistant/tool_use/tool_result)
┌─────────────────────────────────────────────────────────────────────┐
│  Layer 2: Agent SDK (claude-agent-sdk)                              │
│  역할: 멀티턴 루프 자동 관리, MCP 서버 연결, 세션 재개                   │
└─────────────────────────────────────────────────────────────────────┘
                              ↓ AsyncGenerator<SDKMessage>
┌─────────────────────────────────────────────────────────────────────┐
│  Layer 3: firehub-ai-agent (Node.js / Express)                      │
│  역할: SDK 메시지 → SSE 이벤트 변환, MCP 도구 실행, firehub-api 호출   │
└─────────────────────────────────────────────────────────────────────┘
                              ↓ SSE (text / tool_use / tool_result / done)
┌─────────────────────────────────────────────────────────────────────┐
│  Layer 4: firehub-web (React)                                       │
│  역할: 이벤트 누적, contentBlocks 정렬, 위젯 레지스트리로 컴포넌트 렌더링  │
└─────────────────────────────────────────────────────────────────────┘
```

### Layer 1 — Claude API

- **무엇을 하는가**: 사용자 메시지를 받아 텍스트 토큰을 스트리밍하고, 도구를 호출해야 할 때 `tool_use` 블록을 생성한다.
- **위젯과의 관계**: Claude는 `show_table`, `show_chart` 같은 도구가 "시각화 도구"임을 인식하고 적절한 입력(rows, columns, chartType 등)을 구성하여 호출한다. 도구 스키마(JSON Schema)가 Claude에게 어떤 데이터를 준비해야 하는지 알려준다.
- **직접 접근 불가**: firehub-ai-agent가 중간에서 SDK를 통해 호출하며, 프론트엔드는 Claude API에 직접 접근하지 않는다.

### Layer 2 — Agent SDK (`@anthropic-ai/claude-agent-sdk`)

- **무엇을 하는가**: `query()` 함수 하나로 멀티턴 대화 루프 전체를 관리한다. Claude가 도구를 호출하면 MCP 서버에서 실행 결과를 가져와 다시 Claude에게 전달하는 사이클을 자동으로 반복한다.
- **핵심 기능**:
  - `mcpServers: { firehub: firehubServer }` — 인메모리 MCP 서버 연결
  - `permissionMode: 'bypassPermissions'` — 도구 호출 자동 승인 (사용자 확인 없음)
  - `resume: sessionId` — 이전 세션 컨텍스트 재개
  - `allowedTools: ['mcp__firehub__*']` — 허용 도구 목록 제한
- **위젯과의 관계**: SDK가 `show_table` 도구 호출을 MCP 서버로 라우팅하고, MCP 서버의 응답(`{ displayed: true, rowCount: N }`)을 Claude에게 전달한다. SDK 레벨에서 위젯 렌더링 여부는 판단하지 않는다 — 단순히 도구를 실행하고 결과를 돌려줄 뿐이다.

```typescript
// agent-sdk.ts — SDK 호출 핵심 코드
const agentQuery = query({
  prompt: enhancedMessage,
  options: {
    model: 'claude-3-5-sonnet-20241022',
    systemPrompt: SYSTEM_PROMPT,
    maxTurns: 10,
    mcpServers: { firehub: firehubMcpServer },     // 인메모리 MCP 서버
    allowedTools: ['mcp__firehub__*'],
    permissionMode: 'bypassPermissions',           // 자동 승인
    resume: sessionId,                             // 세션 재개
  },
});

// SDK가 반환하는 AsyncGenerator를 순회하여 SSE 이벤트로 변환
for await (const msg of agentQuery) {
  const events = processMessage(msg);   // SDK 메시지 → SSEEvent[]
  for (const event of events) {
    yield event;                        // 상위로 이벤트 전달
  }
}
```

### Layer 3 — firehub-ai-agent (Node.js)

이 레이어가 가장 많은 일을 한다. 크게 3가지 역할이 있다.

#### 3-A. SDK 메시지 → SSE 이벤트 변환 (`process-message.ts`)

SDK가 내보내는 메시지 타입을 프론트엔드가 이해하는 SSE 이벤트로 변환한다.

| SDK 메시지 타입 | 변환 결과 SSE 이벤트 |
|----------------|---------------------|
| `system` (첫 메시지) | `{ type: 'init', sessionId }` |
| `stream_event` (text_delta) | `{ type: 'text', content: '...' }` |
| `assistant` (tool_use 블록) | `{ type: 'tool_use', toolName, input }` |
| `user` (tool_result 블록) | `{ type: 'tool_result', toolName, result }` |
| `user` (마지막) | `{ type: 'turn' }` |
| `result` (성공) | `{ type: 'done', sessionId, inputTokens }` |
| `result` (에러) | `{ type: 'error', message }` |

#### 3-B. MCP 도구 실행 (`mcp/tools/`)

36개 도구가 두 종류로 나뉜다:

**실행 도구** — firehub-api를 호출하여 실제 데이터를 가져온다.
```typescript
// analytics-tools.ts
safeTool('execute_analytics_query', '...', { sql, maxRows }, async (args) => {
  const result = await apiClient.executeAnalyticsQuery(args.sql, args.maxRows);
  return jsonResult(result);  // { columns: [...], rows: [...], totalRows: N }
});
```

**표시 도구** — 위젯 렌더링 지시만 한다. 실제 API 호출 없음.
```typescript
// ui-tools.ts
safeTool('show_table', '...', { title, sql, columns, rows, canvas }, async (args) => {
  return jsonResult({ displayed: true, rowCount: args.rows.length });
  // ↑ tool_result로 Claude에게 "표시 완료" 알림
  // ↑ input(rows, columns)은 SSE tool_use 이벤트로 프론트엔드에 전달됨
});
```

#### 3-C. firehub-api 인증 (`api-client.ts`)

```typescript
// 모든 API 요청에 두 가지 헤더 자동 첨부
Authorization: Internal {INTERNAL_SERVICE_TOKEN}   // 서비스 간 신뢰 증명
X-On-Behalf-Of: {userId}                          // 사용자 권한 위임 (행 수준 보안)
```

#### 3-D. Express SSE 라우터 (`routes/chat.ts`)

```typescript
router.post('/chat', internalAuth, async (req, res) => {
  res.setHeader('Content-Type', 'text/event-stream');
  res.write(':ok\n\n');  // 핸드셰이크

  const events = provider.execute({ message, sessionId, userId, ... });

  for await (const event of events) {
    res.write(`event: ${event.type}\n`);
    res.write(`data: ${JSON.stringify(event)}\n\n`);
  }

  // 30초마다 ping (프록시 타임아웃 방지)
  setInterval(() => res.write('event: ping\ndata: {}\n\n'), 30_000);
});
```

### Layer 4 — firehub-web (React)

- **무엇을 하는가**: SSE 이벤트 스트림을 수신하여 상태로 누적하고, 위젯 레지스트리를 통해 올바른 React 컴포넌트로 렌더링한다.
- **핵심 판단 지점**: `RenderToolCall`에서 `getWidget(toolName)`으로 레지스트리를 조회한다. 매핑된 컴포넌트가 있으면 위젯으로, 없으면 `ToolCallDisplay`(한 줄 요약)로 렌더링한다.
- **데이터 수신 방법**: `src/api/ai.ts`의 `streamAIChat()`이 raw `fetch()`로 SSE 스트림을 읽는다 (Axios 미사용 — SSE는 단방향 스트림이므로).

---

## 3. 전체 데이터 흐름

```
firehub-ai-agent (Node.js)
  Claude API 호출 → 도구 실행 → 결과를 SSE 스트림으로 전송
        │
        │ SSE events (text / tool_use / tool_result / done)
        ▼
src/api/ai.ts — streamAIChat()
  ReadableStream 파싱 → onEvent(AIStreamEvent) 콜백
        │
        ▼
src/hooks/queries/useAIChat.ts
  스트림 이벤트 누적:
    - text 이벤트     → streamingMessage.content 에 append
    - tool_use 이벤트 → streamingMessage.toolCalls[] 에 추가
    - contentBlocks   → 도착 순서 기록 (text | tool_use)
    - tool_result     → toolCalls[n].result 에 저장
        │
        ▼
src/components/ai/AIProvider.tsx
  streamingMessage를 Context로 공급
  native 모드일 때 → 위젯을 캔버스에 배치
        │
        ▼
src/components/ai/MessageBubble.tsx — AssistantContent
  contentBlocks를 순서대로 순회:
    { type: 'text' }                → MarkdownContent 렌더링
    { type: 'tool_use', index: n }  → RenderToolCall(toolCalls[n])
        │
        ▼
  RenderToolCall
    getWidget(toolName) 로 레지스트리 조회
    ├─ 위젯 있음 → <WidgetComponent input={tc.input} />
    └─ 위젯 없음 → <ToolCallDisplay /> (아이콘 + 레이블 + 요약)
```

---

## 4. SSE 스트림 이벤트 프로토콜

`src/types/ai.ts`의 `AIStreamEvent` 타입:

```typescript
export interface AIStreamEvent {
  type: 'init' | 'text' | 'tool_use' | 'tool_result' | 'turn' | 'done' | 'error' | 'compaction';

  // init
  sessionId?: string;

  // text — 스트리밍 텍스트 청크
  content?: string;

  // tool_use — AI가 도구 호출 시작
  toolName?: string;
  input?: Record<string, unknown>;   // 도구 입력 (위젯 데이터)

  // tool_result — 도구 실행 완료
  result?: string;                   // JSON 문자열

  // error
  message?: string;

  // 토큰 정보
  inputTokens?: number;
  preTokens?: number;

  // compaction
  status?: 'started' | 'completed';
}
```

### 이벤트 처리 흐름 (`useAIChat.ts`)

| 이벤트 | 처리 |
|--------|------|
| `init` | `currentSessionId` 설정, 신규 세션 생성 |
| `text` | `streamingMessage.content`에 append, `contentBlocks`에 `{ type: 'text' }` 추가 (중복 방지) |
| `tool_use` | `toolCalls[]`에 `{ name, input }` 추가, `contentBlocks`에 `{ type: 'tool_use', toolCallIndex: n }` 추가 |
| `tool_result` | `toolCalls[n].result` 에 저장, TanStack Query 캐시 무효화 |
| `turn` | 현재 메시지를 히스토리에 커밋, 다음 메시지 스트리밍 시작 |
| `done` | 스트리밍 완료, 최종 메시지 커밋, 토큰 수 업데이트 |
| `error` | 채팅에 에러 메시지 표시 |
| `compaction` | 컨텍스트 윈도우 자동 압축 알림 |

---

## 5. ContentBlocks — 텍스트와 위젯의 순서 보장

AI는 텍스트를 쓰다가 도구를 호출하고, 다시 텍스트를 쓸 수 있다. 이 순서를 보존하기 위해 `contentBlocks` 배열을 사용한다.

### 타입 정의

```typescript
// src/types/ai.ts
export type ContentBlock =
  | { type: 'text' }
  | { type: 'tool_use'; toolCallIndex: number };

export interface AIMessage {
  content: string;                    // 누적된 텍스트 전체 (하나의 문자열)
  toolCalls?: AIToolCall[];           // 도구 호출 배열
  contentBlocks?: ContentBlock[];     // 텍스트/도구 도착 순서 기록
  // ...
}
```

### 스트리밍 중 contentBlocks가 만들어지는 과정

```
이벤트 도착 순서:
  text("안녕하세요, 데이터를 조회합니다.")
  tool_use("show_table", { rows: [...] })
  text("위 표를 참고해 주세요.")

결과 contentBlocks:
  [
    { type: 'text' },                           // "안녕하세요, 데이터를 조회합니다."
    { type: 'tool_use', toolCallIndex: 0 },     // show_table 위젯
    { type: 'text' },                           // "위 표를 참고해 주세요."
  ]

주의: content는 모든 텍스트를 하나의 문자열로 보관한다.
      contentBlocks는 "순서"만 기록하고, 실제 텍스트는 content에서 가져온다.
      → 현재 구현에서 text 블록이 여러 개이면 같은 content를 공유한다.
```

### 렌더링 코드 (`MessageBubble.tsx`)

```typescript
// contentBlocks가 있으면 도착 순서대로 렌더링
if (hasBlocks) {
  return (
    <>
      {message.contentBlocks!.map((block, idx) => {
        if (block.type === 'text') {
          return <MarkdownContent key={`block-${idx}`} content={message.content!} hasNeighbor={idx > 0} />;
        }
        if (block.type === 'tool_use') {
          const tc = message.toolCalls![block.toolCallIndex];
          return (
            <div key={`block-${idx}`} className={cn(idx > 0 && 'mt-1')}>
              <RenderToolCall tc={tc} />
            </div>
          );
        }
      })}
    </>
  );
}

// contentBlocks가 없으면 (히스토리 로드 시) — 도구 먼저, 텍스트 나중
return (
  <>
    {hasToolCalls && message.toolCalls!.map((tc, i) => <RenderToolCall key={i} tc={tc} />)}
    {hasContent && <MarkdownContent content={message.content!} hasNeighbor={hasToolCalls} />}
  </>
);
```

---

## 6. 위젯 레지스트리

### 등록된 10가지 위젯 (`WidgetRegistry.ts`)

```typescript
const WIDGET_REGISTRY: Record<string, WidgetEntry> = {
  show_chart:           { component: lazy(() => import('./ChartWidgetAdapter')),    label: '차트 표시',       icon: '📊' },
  show_dataset:         { component: lazy(() => import('./DatasetWidget')),         label: '데이터셋 표시',   icon: '📦' },
  show_table:           { component: lazy(() => import('./TableWidget')),           label: '테이블 표시',     icon: '📋' },
  navigate_to:          { component: lazy(() => import('./NavigateToWidget')),      label: '페이지 이동',     icon: '🔗' },
  show_pipeline:        { component: lazy(() => import('./PipelineStatusWidget')),  label: '파이프라인 상태', icon: '⚙️' },
  show_dataset_list:    { component: lazy(() => import('./DatasetListWidget')),     label: '데이터셋 목록',   icon: '📦' },
  show_pipeline_list:   { component: lazy(() => import('./PipelineListWidget')),    label: '파이프라인 목록', icon: '⚙️' },
  show_dashboard_summary: { component: lazy(() => import('./DashboardWidget')),    label: '대시보드 현황',   icon: '📈' },
  show_activity:        { component: lazy(() => import('./ActivityWidget')),        label: '최근 활동',       icon: '🕐' },
  generate_report:      { component: lazy(() => import('./ReportBuilderWidget')),  label: 'AI 리포트',       icon: '📝' },
};
```

### 도구 이름 → 레지스트리 키 매핑

AI 에이전트 MCP 도구 이름에는 `mcp__firehub__` 접두사가 붙을 수 있다. 레지스트리 조회 시 자동으로 제거한다.

```typescript
export function getWidget(toolName: string): WidgetEntry | undefined {
  const cleanName = toolName.replace(/^mcp__firehub__/, '');
  return WIDGET_REGISTRY[cleanName];
}

// 예: 'mcp__firehub__show_table' → 'show_table' → TableWidget
```

---

## 7. 메시지 렌더링 로직

### RenderToolCall 분기

```
RenderToolCall({ tc: AIToolCall })
  │
  ├─ getWidget(tc.name) 조회
  │
  ├─ [위젯 있음 + tc.input 있음]
  │   ├─ native 모드? → CanvasPlaceholderCard 표시 (실제 위젯은 캔버스에 배치)
  │   └─ 그 외 모드  → <Suspense> + <WidgetErrorBoundary>
  │                         └─ <WidgetComponent input={tc.input} onNavigate={...} displayMode={mode} />
  │
  └─ [위젯 없음] → ToolCallDisplay
                    [아이콘] 레이블 · 상세 · ✓ 완료 / 실행 중...
```

### ToolCallDisplay (위젯 없는 도구의 표시)

레지스트리에 없는 80여 개의 내부 실행 도구(`list_categories`, `execute_sql_query` 등)는 최소화된 한 줄 UI로 표시된다.

```
[💾] SQL 쿼리 실행 · SELECT * FROM fire_incidents ... · ✓ 완료
[📂] 카테고리 목록 조회                               · 실행 중...
```

---

## 8. 위젯 컴포넌트 구조

### WidgetProps 인터페이스

```typescript
// src/components/ai/widgets/types.ts

export interface WidgetProps<T = Record<string, unknown>> {
  input: T;                          // AI 에이전트가 전달한 도구 입력 그대로
  onNavigate?: (path: string) => void;  // 페이지 이동 콜백
  displayMode: AIMode;               // 'side' | 'floating' | 'fullscreen' | 'native'
}
```

### WidgetShell — 공통 래퍼

대부분의 위젯이 사용하는 일관된 UI 컨테이너:

```typescript
export interface WidgetShellProps {
  title: string;
  icon: string;
  subtitle?: string;
  actions?: React.ReactNode;        // 우상단 버튼 영역
  navigateTo?: string;              // "상세 보기" 링크 경로
  onNavigate?: (path: string) => void;
  displayMode: AIMode;
  children: React.ReactNode;
}
```

렌더링 결과:

```
┌─────────────────────────────────────┐
│ 📋 테이블 제목          [상세 보기 →] │
│ ─────────────────────────────────── │
│  (children: 실제 위젯 내용)          │
└─────────────────────────────────────┘
```

### 주요 위젯별 input 스펙

#### TableWidget
```typescript
interface TableWidgetInput {
  title?: string;
  sql: string;
  columns: string[];
  rows: Record<string, unknown>[];
  totalRows?: number;
}
```
- 페이지네이션, 필터링, 내보내기 기능 포함
- `displayMode`에 따라 `max-h-[250px]` (side/floating) / `max-h-[450px]` (fullscreen)

#### ChartWidgetAdapter
```typescript
interface ChartWidgetInput {
  sql: string;
  chartType: 'bar' | 'line' | 'pie' | 'area' | 'scatter';
  config: ChartConfig;
  columns: string[];
  rows: Record<string, unknown>[];
}
```
- `InlineChartWidget`에 위임하여 Recharts로 렌더링

#### DatasetWidget
```typescript
interface DatasetWidgetInput {
  datasetId: number;
}
```
- 렌더링 시점에 TanStack Query로 메타데이터와 미리보기 데이터를 직접 조회
- 별도 API 호출 (AI가 전달한 input은 ID만 포함)

#### NavigateToWidget
```typescript
interface NavigateToInput {
  type: string;     // 'dataset' | 'pipeline' | 'dashboard' 등
  id?: number;
  label: string;
}
```
- `useEffect`로 렌더링 즉시 자동 이동 (ref 가드로 중복 방지)
- 이동 확인 카드 표시

---

## 9. 표시 모드별 동작

AI 패널은 4가지 모드로 동작하며, 위젯 렌더링 방식이 달라진다.

| 모드 | 패널 크기 | 위젯 최대 높이 | 특이사항 |
|------|-----------|----------------|----------|
| **side** | 우측 사이드바 350px | 250px (스크롤) | 항상 노출, 레이아웃 우측 고정 |
| **floating** | 플로팅 창 400px | 250px (스크롤) | 현재 페이지 위에 오버레이 |
| **fullscreen** | 전체 뷰포트 | 450px (스크롤) | 전용 채팅 화면 |
| **native** | 캔버스 영역 | 제한 없음 | 위젯이 채팅이 아닌 캔버스에 배치됨 |

### Native 모드 — 위젯 캔버스 배치

```
채팅 패널                   캔버스 영역 (CanvasPage)
┌────────────────┐          ┌───────────────────────┐
│ AI: 차트 생성  │          │  ┌──────────────────┐ │
│ [📊 차트 표시] │ ────────→│  │  ChartWidget     │ │
│  (placeholder) │  widget  │  │  (실제 위젯)     │ │
│                │  event   │  └──────────────────┘ │
└────────────────┘          └───────────────────────┘
```

Native 모드에서 `tool_result` 이벤트 수신 시:
1. `onCanvasWidget` 콜백으로 위젯 데이터 전달
2. `useCanvasState()` 훅이 적절한 캔버스 페이지에 위젯 배치
3. 채팅 패널에는 `CanvasPlaceholderCard` (아이콘 + 레이블) 표시

---

## 10. 로딩 · 에러 처리

```
<Suspense fallback={<WidgetSkeleton label="차트 표시" />}>
  <WidgetErrorBoundary>
    <ChartWidgetAdapter input={...} />
  </WidgetErrorBoundary>
</Suspense>
```

| 상황 | 처리 |
|------|------|
| 위젯 컴포넌트 lazy-load 중 | `WidgetSkeleton` — 아이콘 + 레이블 + 스피너 |
| 위젯 렌더링 에러 | `WidgetErrorBoundary` — "위젯을 표시할 수 없습니다" 카드, 채팅 전체는 정상 유지 |
| 도구 실행 중 (result 없음) | `ToolCallDisplay`에서 "실행 중..." + pulse 애니메이션 |
| 도구 실행 완료 | `ToolCallDisplay`에서 "✓ 완료" 표시 |

---

## 11. 새 위젯 추가하는 법

### Step 1 — AI 에이전트에 표시 도구 추가

`apps/firehub-ai-agent/src/tools/`에 도구 정의 추가:

```typescript
// 도구 이름: show_xxx (레지스트리 키와 동일해야 함)
{
  name: 'show_xxx',
  description: 'xxx를 시각적으로 표시합니다',
  input_schema: {
    type: 'object',
    properties: {
      title: { type: 'string' },
      data: { type: 'array', items: { ... } },
    },
    required: ['data'],
  },
}
```

### Step 2 — 위젯 컴포넌트 작성

```typescript
// src/components/ai/widgets/XxxWidget.tsx

import type { WidgetProps } from './types';
import { WidgetShell } from './WidgetShell';

interface XxxWidgetInput {
  title?: string;
  data: { id: number; label: string; value: number }[];
}

/**
 * Xxx 위젯 — AI 에이전트의 show_xxx 도구 결과를 시각화한다.
 */
export default function XxxWidget({ input, onNavigate, displayMode }: WidgetProps<XxxWidgetInput>) {
  const { title = 'Xxx 데이터', data } = input;

  return (
    <WidgetShell
      title={title}
      icon="🔢"
      displayMode={displayMode}
      onNavigate={onNavigate}
      navigateTo="/xxx"          // 선택: "상세 보기" 링크
    >
      {/* 실제 위젯 내용 */}
      <div className="space-y-1 p-2">
        {data.map((item) => (
          <div key={item.id} className="flex justify-between text-sm">
            <span>{item.label}</span>
            <span className="font-medium">{item.value}</span>
          </div>
        ))}
      </div>
    </WidgetShell>
  );
}
```

### Step 3 — 레지스트리에 등록

```typescript
// src/components/ai/widgets/WidgetRegistry.ts
const WIDGET_REGISTRY: Record<string, WidgetEntry> = {
  // ... 기존 위젯들 ...
  show_xxx: {
    component: lazy(() => import('./XxxWidget')),
    label: 'Xxx 표시',
    icon: '🔢',
  },
};
```

### Step 4 — ToolCallDisplay 레이블 추가 (선택)

도구가 실행 중인 상태를 채팅에 보여주려면 레이블도 추가한다:

```typescript
// src/components/ai/ToolCallDisplay.tsx
const TOOL_LABELS: Record<string, { label: string; icon: string }> = {
  // ... 기존 레이블들 ...
  show_xxx: { label: 'Xxx 표시', icon: '🔢' },
};
```

### 체크리스트

```
[ ] AI 에이전트: show_xxx 도구 정의 추가
[ ] XxxWidget.tsx: WidgetProps<XxxWidgetInput> 구현, WidgetShell 사용
[ ] WidgetRegistry.ts: show_xxx 항목 추가 (lazy import)
[ ] ToolCallDisplay.tsx: TOOL_LABELS에 레이블 추가 (선택)
[ ] 빌드 확인: pnpm typecheck
[ ] 수동 테스트: AI 채팅에서 show_xxx 도구를 호출하는 프롬프트 입력 → 위젯 렌더링 확인
```

---

## 파일 위치 요약

```
src/
├── types/
│   └── ai.ts                           # AIStreamEvent, AIMessage, ContentBlock 타입
│
├── api/
│   └── ai.ts                           # SSE 스트림 파싱, streamAIChat()
│
├── hooks/queries/
│   └── useAIChat.ts                    # 스트림 이벤트 처리, 상태 관리
│
└── components/ai/
    ├── AIProvider.tsx                  # 채팅 Context, 모드 관리
    ├── MessageBubble.tsx               # 메시지 렌더링, RenderToolCall, contentBlocks 순회
    ├── MessageList.tsx                 # 메시지 목록, 자동 스크롤
    ├── ToolCallDisplay.tsx             # 위젯 없는 도구의 최소화 UI
    └── widgets/
        ├── WidgetRegistry.ts           # ★ 위젯 등록 테이블
        ├── types.ts                    # WidgetProps, WidgetShellProps 인터페이스
        ├── WidgetShell.tsx             # 공통 UI 래퍼 (제목, 아이콘, 액션)
        ├── TableWidget.tsx             # show_table
        ├── ChartWidgetAdapter.tsx      # show_chart
        ├── DatasetWidget.tsx           # show_dataset
        ├── NavigateToWidget.tsx        # navigate_to
        ├── PipelineStatusWidget.tsx    # show_pipeline
        ├── DatasetListWidget.tsx       # show_dataset_list
        ├── PipelineListWidget.tsx      # show_pipeline_list
        ├── DashboardWidget.tsx         # show_dashboard_summary
        ├── ActivityWidget.tsx          # show_activity
        └── ReportBuilderWidget.tsx     # generate_report
```
