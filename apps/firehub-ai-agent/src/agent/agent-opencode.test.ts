import { describe, it, expect } from 'vitest';
import { readFileSync } from 'fs';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';
import {
  parseOpenCodeEvent,
  buildOpenCodeConfig,
  buildOpenCodeRunArgs,
  normalizeFirehubToolName,
  openCodeOutputTokenEnv,
  transcriptToolInput,
} from './agent-opencode.js';
import { OPENCODE_SYSTEM_PROMPT, OPENCODE_UI_ONLY_TOOLS, SYSTEM_PROMPT } from './system-prompt.js';
import { buildAllMcpTools } from '../mcp/firehub-mcp-server.js';
import { FireHubApiClient } from '../mcp/api-client.js';
import { REPORT_DIR_ENV } from '../mcp/tools/report-file-tools.js';

// 공통 테스트 fixture — 세션 배선 필드는 이 describe 의 관심사가 아니므로 고정값으로 묶는다.
const BASE: Parameters<typeof buildOpenCodeConfig>[0] = {
  userId: 7,
  tenantId: 3,
  apiBaseUrl: 'http://api/v1',
  internalToken: 'tok',
  providerId: 'openai',
  baseUrl: 'https://api.openai.com/v1',
  apiKey: 'k',
  model: 'openai/gpt-4o',
  reasoningEffort: 'medium',
};

describe('buildOpenCodeConfig', () => {
  it('mcp.firehub 에 USER_ID 등 환경변수를 주입한다', () => {
    const cfg = buildOpenCodeConfig(BASE);
    expect(cfg.mcp.firehub.type).toBe('local');
    expect(cfg.mcp.firehub.environment.USER_ID).toBe('7');
    expect(cfg.mcp.firehub.environment.TENANT_ID).toBe('3');
    expect(cfg.mcp.firehub.environment.INTERNAL_SERVICE_TOKEN).toBe('tok');
    expect(cfg.mcp.firehub.environment.API_BASE_URL).toBe('http://api/v1');
    expect(Array.isArray(cfg.mcp.firehub.command)).toBe(true);
  });

  // S3: Proactive 실행이면 stdio MCP 프로세스에 AI_PURPOSE=share 를 심는다(채팅은 심지 않는다).
  it("aiPurpose 'share' 면 mcp.firehub 환경에 AI_PURPOSE=share 를 싣고, 없으면 키 자체가 없다", () => {
    expect(buildOpenCodeConfig({ ...BASE, aiPurpose: 'share' }).mcp.firehub.environment.AI_PURPOSE).toBe('share');
    expect('AI_PURPOSE' in buildOpenCodeConfig(BASE).mcp.firehub.environment).toBe(false);
  });

  // GraphRAG 도구(stdio-server.ts)가 ambient ANTHROPIC_API_KEY 대신 이 테넌트의 opencode
  // provider 를 쓰도록 MCP 자식 env 에도 자격증명을 명시 전달해야 한다(Ruling #30).
  it('mcp.firehub 환경에 opencode 자격증명을 AI_CREDENTIAL_* 키로 전달한다', () => {
    const cfg = buildOpenCodeConfig(BASE);
    expect(cfg.mcp.firehub.environment.AI_CREDENTIAL_AGENT_TYPE).toBe('opencode');
    expect(cfg.mcp.firehub.environment.AI_CREDENTIAL_PROVIDER_ID).toBe('openai');
    expect(cfg.mcp.firehub.environment.AI_CREDENTIAL_BASE_URL).toBe('https://api.openai.com/v1');
    expect(cfg.mcp.firehub.environment.AI_CREDENTIAL_API_KEY).toBe('k');
    expect(cfg.mcp.firehub.environment.AI_CREDENTIAL_REASONING_EFFORT).toBe('medium');
    expect(cfg.mcp.firehub.environment.AI_CREDENTIAL_MODEL).toBe('openai/gpt-4o');
  });

  // 옵션 3 폐기(2026-09-19, 이슈 #693) — 이전 핀 테스트('model 필드를 넣지 않는다')를 뒤집는다.
  // top-level model 을 비우면 배포 측 전역 opencode 설정의 model 이 여전히 이겨 provider
  // 블록이 죽은 코드가 된다.
  it('provider 블록을 쓴다 (옵션 3 폐기 — 2026-09-19, 이슈 #693)', () => {
    const config = buildOpenCodeConfig(BASE);

    expect(config.model).toBe('openai/gpt-4o');
    expect(config.provider.openai.options).toEqual({
      baseURL: 'https://api.openai.com/v1',
      apiKey: 'k',
    });
    expect(config.provider.openai.npm).toBe('@ai-sdk/openai-compatible');
  });

  it('modalities.input 에 image 를 반드시 넣는다', () => {
    // 없으면 opencode 코어가 사용자 메시지의 image 파트를 제거하고
    // "does not support image input" 에러 텍스트로 바꿔친다(iacloud_eis 실측).
    const config = buildOpenCodeConfig(BASE);
    expect(config.provider.openai.models['gpt-4o'].modalities.input).toContain('image');
  });

  it('추론 강도가 비면 options 를 아예 넣지 않는다', () => {
    // 빈 문자열을 그대로 보내면 400 이다.
    const config = buildOpenCodeConfig({ ...BASE, reasoningEffort: '' });
    expect(config.provider.openai.models['gpt-4o'].options).toBeUndefined();
  });

  it('추론 강도가 공백뿐이면(trim 후 빈 값) options 를 아예 넣지 않는다', () => {
    const config = buildOpenCodeConfig({ ...BASE, reasoningEffort: '   ' });
    expect(config.provider.openai.models['gpt-4o'].options).toBeUndefined();
  });

  it('추론 강도가 있으면 options.reasoningEffort 로 싣는다', () => {
    const config = buildOpenCodeConfig({ ...BASE, reasoningEffort: 'high' });
    expect(config.provider.openai.models['gpt-4o'].options).toEqual({ reasoningEffort: 'high' });
  });

  it('모델의 providerId 가 payload 와 다르면 throw 한다', () => {
    expect(() =>
      buildOpenCodeConfig({ ...BASE, providerId: 'openai', model: 'google/gemini' }),
    ).toThrow(/providerID/);
  });

  it('모델에 슬래시가 없으면(형식 위반) throw 한다', () => {
    expect(() => buildOpenCodeConfig({ ...BASE, model: 'gpt-4o' })).toThrow(
      /providerId\/modelId/,
    );
  });

  it('providerId 가 없으면 throw 한다 (배포 측 전역 설정으로 조용히 떨어지지 않는다)', () => {
    expect(() => buildOpenCodeConfig({ ...BASE, providerId: '' })).toThrow(/providerId/);
  });

  it('baseUrl 이 없으면 throw 한다', () => {
    expect(() => buildOpenCodeConfig({ ...BASE, baseUrl: '' })).toThrow(/baseUrl/);
  });

  it('model 이 없으면 throw 한다', () => {
    expect(() => buildOpenCodeConfig({ ...BASE, model: '' })).toThrow(/model/);
  });

  it('permission 으로 bash/edit/write/webfetch 를 deny 하고 firehub MCP 만 allow 한다', () => {
    const cfg = buildOpenCodeConfig(BASE);
    expect(cfg.permission.bash).toBe('deny');
    expect(cfg.permission.edit).toBe('deny');
    expect(cfg.permission.write).toBe('deny');
    expect(cfg.permission.webfetch).toBe('deny');
  });

  // WD-64: 값뿐 아니라 키 순서(allow 뒤 deny)까지 고정한다 — 이유는 buildOpenCodeConfig 의 permission 주석.
  it('UI 안내 전용 생성·설계 도구를 firehub_* allow 뒤에서 개별 deny 한다 (WD-64)', () => {
    const cfg = buildOpenCodeConfig(BASE);
    const keys = Object.keys(cfg.permission);
    const allowIdx = keys.indexOf('firehub_*');
    expect(allowIdx).toBeGreaterThan(-1);
    expect(OPENCODE_UI_ONLY_TOOLS.length).toBeGreaterThan(0);
    for (const tool of OPENCODE_UI_ONLY_TOOLS) {
      const key = `firehub_${tool}`;
      expect(cfg.permission[key]).toBe('deny');
      expect(keys.indexOf(key)).toBeGreaterThan(allowIdx);
    }
  });

  // WD-62: Proactive 실행이면 리포트 쓰기 도구가 쓸 디렉터리를 MCP 프로세스 env 로 주입한다(모델이 고르지 못함).
  it('reportDir 가 있으면 mcp.firehub 환경에 FIREHUB_REPORT_DIR 를 싣고, 없으면 키 자체가 없다 (WD-62)', () => {
    const dir = '/tmp/proactive-report/t3/7-1';
    expect(buildOpenCodeConfig({ ...BASE, reportDir: dir }).mcp.firehub.environment[REPORT_DIR_ENV]).toBe(dir);
    expect(REPORT_DIR_ENV in buildOpenCodeConfig(BASE).mcp.firehub.environment).toBe(false);
  });

  // WD-62: opencode(1.18.34)는 permission 규칙을 findLast 로 평가한다 — 키 존재가 아니라 "마지막 일치 규칙"이
  // allow 인지로 판정해야 WD-64 의 개별 deny 와 충돌하지 않음을 증명할 수 있다.
  it('firehub_write_report_file 의 마지막 일치 permission 규칙은 allow 다 (WD-62)', () => {
    const cfg = buildOpenCodeConfig({ ...BASE, reportDir: '/tmp/r' });
    const matches = (pattern: string, name: string) =>
      new RegExp(`^${pattern.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*')}$`).test(name);
    const verdict = (name: string) =>
      Object.entries(cfg.permission)
        .filter(([pattern]) => matches(pattern, name))
        .at(-1)?.[1];
    expect(verdict('firehub_write_report_file')).toBe('allow');
    // 비교 기준이 실제로 동작하는지(공허하지 않은지) — WD-64 차단 도구는 deny 로 판정돼야 한다.
    expect(verdict('firehub_create_dataset')).toBe('deny');
    expect(OPENCODE_UI_ONLY_TOOLS as readonly string[]).not.toContain('write_report_file');
  });

  // 손으로 쓴 도구명에 오타가 있거나 MCP 도구 이름이 바뀌면 opencode 는 모르는 키를 무시해 deny 가 조용히
  // 무효가 된다(해당 도구는 'firehub_*' allow 로 다시 열림) — 실제 등록된 도구명과 대조한다.
  it('차단 목록의 모든 도구가 실제 firehub MCP 도구로 등록돼 있다 (WD-64)', () => {
    const client = Object.create(FireHubApiClient.prototype) as FireHubApiClient;
    client.withPurpose = () => client;
    const registered = new Set(buildAllMcpTools(client).map((t) => t.name));
    for (const tool of OPENCODE_UI_ONLY_TOOLS) {
      expect(registered.has(tool), tool).toBe(true);
    }
  });

  it('차단 목록은 이슈 사례의 생성 도구를 포함하고 기존 파이프라인 실행·조회는 막지 않는다 (WD-64)', () => {
    expect(OPENCODE_UI_ONLY_TOOLS).toEqual(
      expect.arrayContaining(['create_dataset', 'start_import', 'create_pipeline', 'update_pipeline', 'create_dashboard', 'create_report_template']),
    );
    const cfg = buildOpenCodeConfig(BASE);
    // execute_pipeline 단독(기존 파이프라인 실행)은 프롬프트 2턴 확인으로 통제한다 — config 차단 대상 아님.
    expect(cfg.permission.firehub_execute_pipeline).toBeUndefined();
    expect(cfg.permission.firehub_list_datasets).toBeUndefined();
    expect(cfg.permission.firehub_navigate_to).toBeUndefined();
  });

  it('메인(build) 에서 task 위임을 전면 차단하고 빌트인 general 을 비활성화한다 (#0 보안)', () => {
    // 위임 차단으로 firehub 도구 직접 호출을 강제 — 비격리 general 서브에이전트 누수 방지.
    const cfg = buildOpenCodeConfig(BASE);
    expect(cfg.agent.build.permission.task['*']).toBe('deny');
    expect(cfg.agent.general.disable).toBe(true);
  });
});

// WD-59: 설정 › AI 에이전트의 최대 턴 수·Temperature·최대 응답 토큰을 opencode 설정으로 반영한다.
// 키 위치는 opencode 1.18.34 소스로 확인했다(구현 주석 참고).
describe('buildOpenCodeConfig — AI 실행 설정 (WD-59)', () => {
  const model = (cfg: ReturnType<typeof buildOpenCodeConfig>) => cfg.provider.openai.models['gpt-4o'];

  it('maxTurns 를 메인(build) 에이전트의 steps 로 싣는다 — deprecated maxSteps 가 아니라 steps', () => {
    const cfg = buildOpenCodeConfig({ ...BASE, maxTurns: 7 });
    expect(cfg.agent.build.steps).toBe(7);
    expect('maxSteps' in cfg.agent.build).toBe(false);
    // 기존 위임 차단은 그대로다.
    expect(cfg.agent.build.permission.task['*']).toBe('deny');
  });

  it('temperature 는 agent.build.temperature 와 모델 temperature 능력(true)을 함께 싣는다', () => {
    // 사용자 정의 모델은 temperature 능력 기본값이 false 라 능력 없이 값만 주면 요청에 실리지 않는다.
    const cfg = buildOpenCodeConfig({ ...BASE, temperature: 0.2 });
    expect(cfg.agent.build.temperature).toBe(0.2);
    expect(model(cfg).temperature).toBe(true);
  });

  it('temperature 0 도 유효한 값으로 싣는다', () => {
    const cfg = buildOpenCodeConfig({ ...BASE, temperature: 0 });
    expect(cfg.agent.build.temperature).toBe(0);
    expect(model(cfg).temperature).toBe(true);
  });

  it('값이 없으면 해당 키를 아예 싣지 않는다(기존 설정과 동일)', () => {
    const cfg = buildOpenCodeConfig(BASE);
    expect('steps' in cfg.agent.build).toBe(false);
    expect('temperature' in cfg.agent.build).toBe(false);
    expect('temperature' in model(cfg)).toBe(false);
    // 모델 limit 은 쓰지 않는다 — context 가 필수라 카탈로그 공급자의 context 를 덮어쓴다(openCodeOutputTokenEnv 주석).
    expect('limit' in model(cfg)).toBe(false);
  });

  it('숫자가 아니거나 범위 밖인 값은 싣지 않는다(요청 바디는 검증되지 않은 값이다)', () => {
    const cfg = buildOpenCodeConfig({ ...BASE, maxTurns: 0, temperature: Number.NaN });
    expect('steps' in cfg.agent.build).toBe(false);
    expect('temperature' in cfg.agent.build).toBe(false);
    expect('temperature' in model(cfg)).toBe(false);
  });
});

// WD-59: 최대 응답 토큰은 설정이 아니라 opencode 의 OUTPUT_TOKEN_MAX env 로 건다.
describe('openCodeOutputTokenEnv (WD-59)', () => {
  it('양의 정수면 OPENCODE_EXPERIMENTAL_OUTPUT_TOKEN_MAX 로 만든다', () => {
    expect(openCodeOutputTokenEnv(4096)).toEqual({ OPENCODE_EXPERIMENTAL_OUTPUT_TOKEN_MAX: '4096' });
  });

  it.each([undefined, 0, -1, 1.5, '4096', Number.NaN])('%s 면 빈 객체(opencode 기본값 유지)', (v) => {
    expect(openCodeOutputTokenEnv(v)).toEqual({});
  });
});

describe('buildOpenCodeRunArgs', () => {
  it('--dir 로 격리 워크스페이스를 프로젝트 루트로 강제한다 (cwd 의존 제거)', () => {
    // --dir 누락 시 opencode 가 /app(소스)로 앵커돼 워크스페이스 설정이 무시되는 회귀 방지.
    const args = buildOpenCodeRunArgs('안녕', '/home/u/.firehub/workspaces-opencode/1');
    const di = args.indexOf('--dir');
    expect(di).toBeGreaterThan(-1);
    expect(args[di + 1]).toBe('/home/u/.firehub/workspaces-opencode/1');
    expect(args).toContain('--format');
    expect(args).not.toContain('--session');
  });

  it('재개 세션 id 가 있으면 --session 을 덧붙인다', () => {
    const args = buildOpenCodeRunArgs('계속', '/wd', 'ses_abc');
    expect(args.slice(-2)).toEqual(['--session', 'ses_abc']);
  });
});

describe('OPENCODE_SYSTEM_PROMPT', () => {
  /** `## <제목>` 섹션 본문을 찾는다 — 전체 문자열 검사는 문장이 다른 섹션으로 옮겨져도 통과하므로 섹션 단위로 본다. */
  const promptSection = (title: string): string => {
    const section = OPENCODE_SYSTEM_PROMPT.split(/^## /m).find((s) => s.startsWith(title));
    expect(section).toBeDefined();
    return section!;
  };
  /** 섹션 안에서 `marker` 로 시작하는 규칙 줄(하위 bullet 포함)만 잘라낸다 — 섹션 전체 검사는 다른 줄의 단어로 통과해 버린다. */
  const promptRule = (title: string, marker: string): string => {
    const section = promptSection(title);
    const start = section.indexOf(marker);
    expect(start).toBeGreaterThan(-1);
    const nextTop = section.indexOf('\n- ', start);
    return section.slice(start, nextTop === -1 ? undefined : nextTop);
  };
  /** 서브에이전트 rules.md 원문 — cli 경로 규칙과 opencode 프롬프트의 의미 동기화를 확인할 때 쓴다. */
  const readSubagentRules = (name: string): string =>
    readFileSync(join(dirname(fileURLToPath(import.meta.url)), `subagents/${name}/rules.md`), 'utf-8');
  it('위임을 금지하고 직접 처리·요약을 지시한다 (가짜 위임 태그 방지)', () => {
    // 위임 라우팅을 그대로 주면 약한 모델이 막힌 위임을 텍스트로 흉내내 응답이 깨진다.
    expect(OPENCODE_SYSTEM_PROMPT).toContain('위임 금지');
    expect(OPENCODE_SYSTEM_PROMPT).toContain('<call:Agent');
    expect(OPENCODE_SYSTEM_PROMPT).toContain('결과 요약 필수');
    // 위임 중심 SYSTEM_PROMPT 의 라우팅 표 헤더가 섞이지 않았는지 (회귀 가드)
    expect(OPENCODE_SYSTEM_PROMPT).not.toContain('전문 에이전트에게 위임하세요');
  });

  // WD-64: UI 안내 규칙이 "사용 불가" 목록 속 한 줄이라 약한 모델이 무시했다 — 독립 강조 블록으로 승격.
  it('UI 안내 전용 블록을 별도 섹션으로 두고 차단 도구명을 모두 나열한다 (WD-64)', () => {
    // 프롬프트 전체가 아니라 **이 섹션 안에서** 확인한다 — 도구명은 같은 상수로 보간되므로 전체 문자열
    // 검사는 문장이 다른 섹션으로 옮겨지거나 지워져도 통과해 버린다.
    const section = promptSection('생성·설계 요청은 UI 안내');
    expect(section).toContain('차단되어 있어 호출할 수 없다');
    for (const tool of OPENCODE_UI_ONLY_TOOLS) {
      expect(section).toContain(`\`${tool}\``);
    }
    // 탐색 없이 즉시 안내 + 최종 텍스트 마무리 + 로컬 테스트 불가 고지
    expect(section).toContain('firehub_navigate_to');
    expect(section).toMatch(/설계·탐색을 시작하지 말고 즉시/);
    expect(section).toMatch(/반드시 최종 텍스트로 마무리/);
    expect(section).toMatch(/로컬 테스트·코드 실행 불가/);
    // navigate_to 의 실제 type 값으로 안내하도록 매핑을 박아 둔다(웹 routes.ts 의 type 과 일치해야 함)
    for (const type of ['dataset_new', 'pipeline_new', 'dashboard', 'ai_insights_templates']) {
      expect(section).toContain(`\`${type}\``);
    }
  });

  it('파이프라인 실행을 파괴 작업과 같은 수준의 2턴 확인으로 통제한다 (WD-64)', () => {
    expect(OPENCODE_SYSTEM_PROMPT).toMatch(/^## 파이프라인 실행 2턴 확인/m);
    expect(OPENCODE_SYSTEM_PROMPT).toContain('execute_pipeline');
  });

  it('안전 통제(PII 마스킹·파괴 2턴 확인)를 보존한다', () => {
    // 위임 제거로 안전 규칙이 약한 모델 메인에 얹히므로 프롬프트에 반드시 잔류해야 한다.
    expect(OPENCODE_SYSTEM_PROMPT).toContain('PII 마스킹');
    expect(OPENCODE_SYSTEM_PROMPT).toContain('파괴 작업 2턴 확인');
    expect(OPENCODE_SYSTEM_PROMPT).toContain('get_dataset_references');
  });

  // WD-65: cli 의 data-analyst rules.md(#576) 규칙이 opencode PII 섹션에도 있어야 한다(배경은 system-prompt.ts JSDoc).
  it('PII 값 자체가 답인 경우의 마스킹 규칙을 PII 섹션 안에 두고 rules.md 와 동기화한다 (WD-65, #576)', () => {
    const section = promptSection('PII 마스킹');
    const rules = readSubagentRules('data-analyst');
    // 두 경로 공통 핵심: 집계 우선과 마스킹 의무는 별개 축
    for (const doc of [section, rules]) expect(doc).toContain('별개의 축');
    // opencode 측: 중복(HAVING) 예시·마스킹 값+건수 예시·SQL 단계 마스킹 안내
    expect(section).toContain('HAVING');
    expect(section).toContain('`a***@e***.com` (2건)');
    expect(section).toContain('SQL 단계에서 마스킹');
    // 위젯 sql 은 화면에 표시되므로 PII 원문 리터럴을 SQL 에 박지 않는다(라이브 검증에서 실측된 우회 경로)
    expect(section).toContain('PII 원문 리터럴');
  });

  // WD-66 (배경은 system-prompt.ts JSDoc)
  it('집계 수치는 원시 행 암산 대신 집계 SQL 결과를 인용하도록 조회·분석 흐름 섹션에서 지시한다 (WD-66)', () => {
    const section = promptSection('데이터 조회·분석 흐름');
    expect(section).toContain('집계 수치는 SQL 로 계산');
    expect(section).toContain('암산 금지');
    // 0 나눗셈 방지(NULLIF) — 예시를 그대로 베껴도 전월 0 인 달에서 쿼리가 깨지지 않게
    expect(section).toContain('NULLIF(');
    // 억·만 단위 변환도 암산이라(사례1 의 10배 오차) SQL 에서 계산하게 한다
    expect(section).toContain('단위 변환도 암산하지 말고 SQL 에서 계산');
    // 요청 기간 데이터가 없어 가용 데이터로 예비 요약할 때도 동일 적용
    expect(section).toContain('가용 데이터로 대신 요약');
    // 순서 엄수 섹션이므로 집계 단계가 위젯 표시 단계보다 앞서야 요약 수치가 집계 결과에서 나온다
    expect(section.indexOf('집계 수치는 SQL 로 계산')).toBeLessThan(section.indexOf('결과 표시'));
  });

  // WD-67: 조회 도구 호출 없이 "데이터셋 ID 99999 은 존재하지 않습니다 (404)" 처럼 존재 여부와
  // 상태 코드를 지어냈다. cli 쪽 pipeline-builder rules.md 의 같은 회귀(#574) 규칙을 일반화해 옮긴다.
  it('조회 없이 리소스 존재 여부를 단정하지 않도록 응답 출력 규칙 섹션에서 지시한다 (WD-67, #574)', () => {
    const rule = promptRule('응답 출력 규칙', '존재 여부 단정 금지');
    // 실제로 받은 조회 결과가 근거여야 하고, 이번 턴 ID 조회 도구를 먼저 호출한다
    expect(rule).toContain('실제로 받은 조회 tool_result');
    for (const tool of ['get_dataset', 'get_pipeline']) expect(rule).toContain(`\`${tool}\``);
    // 2턴 확인 흐름(Turn 1 조회 → Turn 2 실행)과 모순되지 않게 Turn 1 결과 재사용을 허용한다
    expect(rule).toContain('Turn 1 조회 결과를 근거로 써도 된다');
    // 상태 코드 지어내기 "금지" 문장 자체를 고정한다
    expect(rule).toContain('404 같은 상태 코드나 오류 문구를 **지어내지 않는다**');
    // 의미 검색·페이지 목록이 비었다고 "존재하지 않는다"고 하지 않는다(그건 ID 조회 not found 만)
    expect(rule).toMatch(/`find_datasets`.*`list_\*`.*"찾지 못했다"로만/);
    // 조회 안 했으면 미검증 표현
    expect(rule).toContain('아직 확인하지 않았습니다');
    // cli 쪽 원 규칙(#574)이 사라지면 두 경로의 의미 동기화 근거가 없어진다 — 메인 SYSTEM_PROMPT 와 rules.md 둘 다
    expect(SYSTEM_PROMPT).toContain('무검증 확인 진술 금지 (#574)');
    expect(readSubagentRules('pipeline-builder')).toContain('존재/미존재 진술 전 검증 의무');
    // 같은 안내를 표현만 바꿔 두 번 쓴 사례(WD-67) — opencode 는 text part 를 한 번씩만 내보내므로(실측)
    // 코드 중복이 아니라 모델 재생성이다. 절대 규칙의 단일 최종 텍스트에 "한 응답 안 재서술 금지"를 둔다.
    expect(promptSection('절대 규칙')).toContain('표현만 바꿔 다시 쓰지 않는다');
  });

  // WD-68: 삭제 2턴 흐름의 Turn 2 에서 영어 tool_result 뒤 영어로 답하고, deletedAt(ISO)을 KST 로
  // 직접 옮기다 월을 틀렸다. 한국어 고정은 절대 규칙에, 시각 인용은 파괴 작업 Turn 2 규칙에 둔다.
  it('응답 언어를 한국어로 고정하고 확인 턴·영어 tool_result 에도 적용한다 (WD-68)', () => {
    const rule = promptRule('절대 규칙', '한국어 응답 고정');
    expect(rule).toContain('모든 응답(요약·안내·확인 질문·오류 설명 포함)은 한국어로 작성한다');
    // 실패가 난 정확한 조건 — 영어 tool_result/오류 뒤, 짧은 긍정 뒤의 확인 턴
    expect(rule).toContain('tool_result·오류 메시지가 영어여도');
    expect(rule).toContain('확인 턴(Turn 2)');
  });

  it('파괴 작업 Turn 2 요약의 시각은 *Kst 필드를 그대로 인용하게 한다 (WD-68, #571)', () => {
    const rule = promptRule('파괴 작업 2턴 확인', '**Turn 2**');
    expect(rule).toContain('`deletedAtKst`');
    expect(rule).toContain('시각은 tool_result 의 `*Kst` 필드를 그대로 인용하고 직접 변환·추정하지 않는다');
    // *Kst 는 현재 delete_dataset 만 돌려준다 — 다른 파괴 도구 결과에서 시각을 지어내지 않게 한다
    expect(rule).toContain('`*Kst` 필드가 없는 도구 결과에는 시각을 적지 않는다');
    // 예시는 헬퍼 출력 형식 그대로여야 모델이 다른 형식으로 바꿔 쓰지 않는다
    expect(rule).toMatch(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}\(KST\)에 삭제되었습니다/);
    // opencode 는 위임이 차단돼 있어 위임 안내는 절대 규칙(위임 금지)과 모순 — 남기지 않는다
    expect(rule).not.toContain('dataset-manager');
    // cli 쪽 원 규칙(#571 시각 출처)과 의미 동기화 근거 — rules.md 는 이번에 바꾸지 않는다
    expect(readSubagentRules('dataset-manager')).toContain('시각 출처는 `delete_dataset` tool_result');
  });

  // WD-67: UI 안내 블록의 "탐색 없이 즉시 안내" 와 위 존재 확인 규칙이 충돌하지 않도록,
  // 안내만 할 때는 존재 여부를 말하지 않는다는 조정 문구를 UI 안내 섹션에 둔다.
  it('UI 안내 시 조회하지 않았으면 대상 존재 여부를 말하지 않게 한다 (WD-67)', () => {
    const section = promptSection('생성·설계 요청은 UI 안내');
    // 확인 조회는 기존 1~2회 한도 안에서, 안 했으면 존재 여부를 말하지 않고 안내만
    expect(section).toContain('위 1~2회 조회 안에서');
    expect(section).toContain('조회하지 않았으면 존재 여부·오류 코드를 말하지 말고 화면 안내만 한다');
  });

  // GraphRAG 조회는 opencode 경로에도 필요하지만, 구축·검수 결정은 다단계 가드 흐름이라
  // 약한 모델에서 깨진다 — 읽기만 안내하고 쓰기는 UI 로 보내는 경계를 고정한다.
  it('GraphRAG 는 읽기 전용으로만 안내하고 구축·검수 결정 도구는 배제한다', () => {
    // `firehub_` 접두 표기는 opencode 경로 전용 — 이 needle 이 SYSTEM_PROMPT 에도 있으면
    // 블록이 엉뚱한 템플릿에 들어간 것이다(두 프롬프트 모두 'graphrag_query' 는 포함하므로 판별 불가).
    expect(OPENCODE_SYSTEM_PROMPT).toContain('firehub_graphrag_query');
    expect(SYSTEM_PROMPT).not.toContain('firehub_graphrag_query');
    expect(OPENCODE_SYSTEM_PROMPT).toContain('firehub_graphrag_structured_query');
    expect(OPENCODE_SYSTEM_PROMPT).toContain('firehub_graphrag_describe_ontology');
    // 구축·검수 결정 도구는 사용 안내가 없어야 한다
    expect(OPENCODE_SYSTEM_PROMPT).not.toContain('graphrag_approve_review_item');
    expect(OPENCODE_SYSTEM_PROMPT).not.toContain('graphrag_activate_mapping');
    expect(OPENCODE_SYSTEM_PROMPT).not.toContain('graphrag_project_table');
    // 근거 인용·환각 금지 규칙 동반
    expect(OPENCODE_SYSTEM_PROMPT).toMatch(/sourceChunks/);
    expect(OPENCODE_SYSTEM_PROMPT).toMatch(/환각/);
  });
});

describe('parseOpenCodeEvent', () => {
  it('텍스트 이벤트를 SSE text 로 변환한다 (실측 스키마: part.text)', () => {
    // 실측 픽스처: type="text", part.text=텍스트
    const ev = parseOpenCodeEvent({ type: 'text', part: { text: '안녕' } });
    expect(ev).toEqual([{ type: 'text', content: '안녕' }]);
  });

  it('tool_use 이벤트(completed)를 SSE tool_use + tool_result 둘 다 반환한다 (실측 스키마)', () => {
    // 실측 픽스처: type="tool_use", part.type="tool", part.tool=도구명,
    // part.state.status="completed", part.state.input/output 포함
    const evs = parseOpenCodeEvent({
      type: 'tool_use',
      part: { type: 'tool', tool: 'firehub_list_categories', state: { status: 'completed', input: { a: 1 }, output: 'result' } },
    });
    expect(evs).toHaveLength(2);
    // 프론트엔드 위젯 매칭을 위해 mcp__firehub__ 형식으로 정규화돼야 한다.
    expect(evs[0]).toEqual({ type: 'tool_use', toolName: 'mcp__firehub__list_categories', input: { a: 1 } });
    expect(evs[1]).toEqual({ type: 'tool_result', toolName: 'mcp__firehub__list_categories', result: 'result' });
  });

  it('firehub_ 도구명을 mcp__firehub__ 로 정규화한다 (위젯 렌더링 계약)', () => {
    // 프론트 WidgetRegistry 는 mcp__firehub__ 접두사만 벗겨 show_chart 등으로 매칭한다.
    expect(normalizeFirehubToolName('firehub_show_chart')).toBe('mcp__firehub__show_chart');
    expect(normalizeFirehubToolName('firehub_execute_analytics_query')).toBe('mcp__firehub__execute_analytics_query');
    // 접두사 1회만 치환 (도구명 내부 underscore 보존)
    expect(normalizeFirehubToolName('firehub_get_data_schema')).toBe('mcp__firehub__get_data_schema');
  });

  it('tool_use 이벤트(running 등 비완료 상태)는 빈 배열을 반환한다', () => {
    // running 상태에서는 output 이 아직 없으므로 무시(중복 이벤트 방지)
    const evs = parseOpenCodeEvent({
      type: 'tool_use',
      part: { type: 'tool', tool: 'firehub_list_categories', state: { status: 'running' } },
    });
    expect(evs).toEqual([]);
  });

  it('tool_use 이벤트(error)를 tool_use + tool_result(isError=true, result=state.error) 로 반환한다 (WD-60)', () => {
    // opencode 는 MCP isError 결과를 throw 로 바꿔 state.status="error", state.error=오류문 으로
    // 내보낸다(output 없음). 무시하면 실패가 프론트·연속 실패 트래커에 보이지 않는다.
    const evs = parseOpenCodeEvent({
      type: 'tool_use',
      part: { type: 'tool', tool: 'firehub_execute_sql', state: { status: 'error', input: { q: 1 }, error: 'boom' } },
    });
    expect(evs).toEqual([
      { type: 'tool_use', toolName: 'mcp__firehub__execute_sql', input: { q: 1 } },
      { type: 'tool_result', toolName: 'mcp__firehub__execute_sql', result: 'boom', isError: true },
    ]);
  });

  it('state.error 가 문자열이 아니면 "[object Object]" 대신 JSON 으로 싣는다 (WD-60)', () => {
    // 서로 다른 오류가 같은 문자열로 뭉개지면 failure-streak 가 엉뚱하게 합산해 중단한다.
    const evs = parseOpenCodeEvent({
      type: 'tool_use',
      part: { type: 'tool', tool: 'firehub_execute_sql', state: { status: 'error', input: {}, error: { message: 'boom' } } },
    });
    expect(evs[1]).toMatchObject({ type: 'tool_result', result: '{"message":"boom"}', isError: true });
  });

  it('step_finish(reason=stop)를 done+tokens 로 변환한다', () => {
    const evs = parseOpenCodeEvent({
      type: 'step_finish',
      part: { reason: 'stop', tokens: { input: 100, output: 20 } },
    });
    expect(evs).not.toBeNull();
    const done = evs!.find((e) => e.type === 'done');
    expect(done).toBeDefined();
    expect(done!.inputTokens).toBe(100);
    expect(done!.outputTokens).toBe(20);
  });

  it('step_finish(reason=tool-calls)를 turn 으로 변환한다', () => {
    const evs = parseOpenCodeEvent({
      type: 'step_finish',
      part: { reason: 'tool-calls', tokens: { input: 50, output: 5 } },
    });
    expect(evs).not.toBeNull();
    const turn = evs!.find((e) => e.type === 'turn');
    expect(turn).toBeDefined();
  });

  it('알 수 없는 이벤트는 빈 배열을 반환한다', () => {
    expect(parseOpenCodeEvent({ type: 'unknown_xyz' })).toEqual([]);
  });

  it('step_start 이벤트는 빈 배열을 반환한다', () => {
    expect(parseOpenCodeEvent({ type: 'step_start', part: {} })).toEqual([]);
  });

  it('실측 픽스처 라인들이 기대 SSE 시퀀스로 변환된다', async () => {
    const { readFile } = await import('fs/promises');
    const { fileURLToPath } = await import('url');
    const { dirname, join } = await import('path');
    const dir = dirname(fileURLToPath(import.meta.url));
    const raw = await readFile(join(dir, '__fixtures__', 'opencode-run.jsonl'), 'utf-8');
    const events = raw
      .split('\n')
      .filter(Boolean)
      .flatMap((l) => {
        try {
          return parseOpenCodeEvent(JSON.parse(l));
        } catch {
          return [];
        }
      })
      .filter(Boolean);
    // 최소 보장: tool_use(firehub_list_categories) + tool_result + done 이벤트 존재
    expect(events.some((e) => e!.type === 'tool_use')).toBe(true);
    expect(events.some((e) => e!.type === 'tool_result')).toBe(true);
    expect(events.some((e) => e!.type === 'done')).toBe(true);
    // 픽스처 마지막 step_finish 토큰 값 검증 (input: 77, output: 4)
    const doneEv = events.find((e) => e!.type === 'done');
    expect(doneEv!.inputTokens).toBe(77);
    expect(doneEv!.outputTokens).toBe(4);
  });
});

// WD-62: 리포트 전문이 지워지지 않는 트랜스크립트에 남지 않게 한다.
describe('transcriptToolInput', () => {
  it('write_report_file 의 content 는 글자 수로 바꾸고 fileName 은 남긴다', () => {
    expect(
      transcriptToolInput('mcp__firehub__write_report_file', { fileName: 'report.html', content: '<html>본문</html>' }),
    ).toEqual({ fileName: 'report.html', content: '[15자 생략]' });
  });

  it('다른 도구의 입력은 그대로 둔다', () => {
    const input = { sql: 'SELECT 1', content: '그대로' };
    expect(transcriptToolInput('mcp__firehub__execute_analytics_query', input)).toBe(input);
  });
});
