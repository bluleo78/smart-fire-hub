# WD-806 차트 렌더를 @napi-rs/canvas 로 바꾼다 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ai-agent 이미지(alpine)에서 `POST /agent/chart-render` 가 500(`Cannot find module '../build/Release/canvas.node'`) 대신 200·PNG 를 내게 한다.

**원인:** `chartjs-node-canvas` 가 쓰는 `canvas`(node-canvas 3.2.2)는 설치 스크립트로 네이티브 모듈을 받는데, (1) pnpm 10 은 `onlyBuiltDependencies`(지금 esbuild 뿐)에 없는 패키지의 설치 스크립트를 돌리지 않고, (2) node-canvas 3.2.2 는 musl(alpine) 사전 빌드가 없어(릴리스 자산: darwin x64/arm64, linux(glibc) x64/arm64, win32 x64) 이미지에서는 node-gyp 로 cairo·pango 를 빌드해야 한다. 로컬 개발 환경도 같은 이유로 `build/` 가 없다.

## 결정(내가 정한 것)

- **렌더 엔진을 `@napi-rs/canvas`(Skia, 1.1.1)로 바꾸고 `chart.js` 를 직접 쓴다.** `chartjs-node-canvas` 를 뺀다.
  - 근거: `@napi-rs/canvas` 는 `linux-x64-musl`·`linux-arm64-musl` 사전 빌드를 optional dependency 로 내 설치 스크립트가 필요 없고(onlyBuiltDependencies 를 늘리지 않는다), 런타임 시스템 라이브러리(cairo·pango 등)도 필요 없다. 멀티아치 빌드에서 각 타깃이 자기 아키 패키지를 받는 기존 방식(Dockerfile 주석)과 같다.
  - 버린 안 A(node-canvas 를 이미지에서 빌드): 빌드 스테이지에 python3·make·g++·cairo-dev·pango-dev 등, 런타임에 cairo·pango·jpeg·giflib 를 더해야 하고 arm64 는 QEMU 컴파일로 빌드가 길어진다. 또 `canvas` 를 onlyBuiltDependencies 에 넣으면 로컬 설치에서도 canvas 가 빌드되어, `canvas` 를 선택 peer 로 잡는 jsdom(web vitest)이 실제 canvas 를 쓰기 시작해 web 시험 동작이 바뀐다.
  - 버린 안 C(이미 있는 chromium 으로 그리기): 요청마다 브라우저 페이지를 띄워 무겁고, html-to-pdf 와 브라우저 자원을 나눠 쓴다.
  - 스파이크: 호스트에서 `@napi-rs/canvas@1.1.1` + `chart.js@4.5.1` 로 한글 제목·라벨 막대 차트를 PNG 로 그려 확인(시스템 폰트를 스스로 읽는다). 이미지에는 이미 `font-noto-cjk` 가 있다.
- 응답 모양·기본 크기(600×400)·흰 배경·옵션(제목·범례·축)은 그대로 둔다. 캐시(`canvasCache`)는 없앤다 — 요청마다 캔버스를 만들고 차트를 `destroy()` 한다(크기별 캐시가 요청 크기 수만큼 커지는 것도 함께 없어진다).
- **`width`·`height` 는 주어지면 1..2000 정수여야 한다(아니면 400).** 상한 2000 은 요청당 네이티브 캔버스 할당을 제한하려는 결정이다. 기본값 600×400 은 그대로. `type` 도 런타임에서 bar·pie·line·doughnut 만 허용(400)하고, 렌더 전에 모든 차트를 검증한다(리뷰 반영).
- **PNG 인코딩은 비동기**(`await canvas.encode('png')`)로 이벤트 루프를 막지 않는다. 기본 글꼴은 한글 지원 계열(`Noto Sans CJK KR`)로 한 번 지정한다(리뷰 반영).

## Global Constraints

- 한국어 주석/JSDoc 필수(무엇을·왜, WD-806 표기).
- 셸 명령은 단순하게: 한 줄 셸 함수 정의, `${…:-{…}}`, `;`·`&&` 로 길게 이은 한 줄은 쓰지 않는다. 복잡하면 스크립트 파일로.
- 의존성 설치는 저장소 루트에서 `pnpm install`(pnpm 10.11 — `packageManager` 필드 대로). lockfile 은 바뀐 의존성만 바뀌어야 한다.
- 시험: `cd apps/firehub-ai-agent` 에서 `pnpm vitest run src/routes/chart-render.test.ts src/app.test.ts`, `pnpm typecheck`(또는 `pnpm exec tsc --noEmit`), `pnpm lint`. Docker 빌드·Playwright·gradle 은 돌리지 않는다(검토자가 한다).
- 커밋은 과제마다 하나, `git commit --no-verify`, 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. 렌더가 실제로 PNG 를 낸다 — 모의 없이 `@napi-rs/canvas` 로 그리는 시험이 하나 있고, PNG 시그니처(89 50 4E 47)와 요청한 크기(IHDR 의 너비·높이)를 확인한다.
2. 배경이 흰색이다(투명 PNG 가 아니다) — 왼쪽 위 픽셀이 흰색인지 시험한다.
3. 차트 하나가 실패해도(잘못된 type 등) 500 `Chart rendering failed` 와 `details` 로 끝나고, **생성자가 던져도**(chart.js 가 인스턴스를 `Chart.instances` 에 먼저 등록한 뒤 던진다) 우리 캔버스에 묶인 인스턴스를 finally 에서 찾아 `destroy()` 한다 — 실패·성공 뒤 `Chart.instances` 가 비어 있음을 시험한다.
4. `Chart.register(...registerables)` 는 모듈 로드 때 한 번만.
5. lockfile 에서 `chartjs-node-canvas` 가 빠지고(`canvas@3.2.2` 는 jsdom 의 선택 peer 로 optional 만 남는다) `@napi-rs/canvas` 와 플랫폼 패키지들이 들어온다. 다른 패키지 판은 바뀌지 않는다.
6. `type` 이 bar·pie·line·doughnut 이 아니거나 `width`·`height` 가 1..2000 정수가 아니면 렌더 전에 400(어느 차트 몇 번째인지 메시지에 포함), 경계값(1·2000)과 기본값(600×400)은 통과한다.
7. `renderChartPng` 는 `Promise<Buffer>` — `canvas.encode('png')` 를 await 하고 destroy 는 인코딩이 끝난 뒤에 한다.

## Task 1: 렌더 교체(ai-agent)

**Files:**
- Modify: `apps/firehub-ai-agent/package.json` — `chartjs-node-canvas` 빼고 `@napi-rs/canvas: ^1.1.1` 추가(`chart.js` 는 그대로).
- Create: `apps/firehub-ai-agent/src/routes/chart-png.ts` — `renderChartPng(configuration, width, height): Promise<Buffer>`. `createCanvas(width, height)` → `new Chart(ctx as unknown as CanvasRenderingContext2D, { ...configuration, options: { ...options, responsive: false, animation: false, devicePixelRatio: 1 }, plugins: [흰 배경 plugin(beforeDraw 에서 fillRect)] })` → `await canvas.encode('png')` → finally 우리 캔버스의 `Chart.instances` 를 `destroy()`.
- Modify: `apps/firehub-ai-agent/src/routes/chart-render.ts` — `chartjs-node-canvas`·`canvasCache` 를 없애고 `renderChartPng` 를 쓴다.
- Create: `apps/firehub-ai-agent/src/routes/chart-png.test.ts` — 모의 없이 실제 렌더(Review Focus 1·2), 잘못된 type 에서 throw.
- Modify: `apps/firehub-ai-agent/src/routes/chart-render.test.ts` — 모의 대상을 `chartjs-node-canvas` 에서 `./chart-png.js` 의 `renderChartPng` 로 바꾸고 기존 경우를 유지.

- [ ] 실패하는 `chart-png.test.ts` 를 먼저 쓴다(모듈 없음으로 실패 확인).
- [ ] 의존성 바꾸고 루트에서 `pnpm install`.
- [ ] `chart-png.ts`·`chart-render.ts` 구현, 시험 통과.
- [ ] typecheck·lint 통과 후 커밋 `fix(ai-agent): 차트 렌더를 @napi-rs/canvas 로 바꿔 alpine 이미지에서 canvas 네이티브 모듈 없이 그린다 (WD-806)`.

## Task 2: 이미지 확인(직접 — 하위 에이전트 아님)

- [ ] `docker build -f apps/firehub-ai-agent/Dockerfile -t wd806-agent .` 뒤 이슈의 재현 요청이 200·PNG(한글 제목) 인지, 컨테이너 안에 `@napi-rs/canvas-linux-x64-musl` 가 있는지.
- [ ] arm64 는 buildx(QEMU) 로 설치 단계까지 되는지(가능하면).
- [ ] trivy HIGH/CRITICAL 비교(main 이미지 대비 새로 생긴 것 없음).
- [ ] Dockerfile 은 바꿀 필요가 없을 것으로 본다(사전 빌드라 빌드 도구·시스템 라이브러리 불필요). 바꿀 것이 나오면 여기 적는다.
