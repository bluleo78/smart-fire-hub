import { describe, it, expect } from 'vitest';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

/**
 * dashboard-builder 위임 우회 회귀 가드 (#601, refs #253).
 *
 * 배경: #253은 "차트 없이"/"옵션 기본값"/"그냥 만들어" 같은 옵션 단순화 표현을 이유로
 * 메인 에이전트가 dashboard-builder 위임 없이 create_dashboard/add_chart_to_dashboard를
 * 직접 호출하던 회귀를 막았다. #601은 같은 우회 결과를 다른 트리거 표현("강제로 넣어줘",
 * "그대로 시도해" 같은 유효성 우회 요청)으로 재발시켰다 — 메인이 "서버 검증(@Positive/@Min)에
 * 걸려 어차피 실패할 것"이라는 이유로 add_chart_to_dashboard를 직접 호출해, dashboard-builder의
 * get_dashboard_detail 겹침 확인(#583) 등 subagent 가드가 전부 우회됐다.
 *
 * 이 테스트는 다음 두 파일에 유효성 우회 표현 카테고리에 대한 가드가 명시되어 있는지
 * 정적으로 검증한다.
 *   1) dashboard-builder/agent.md — 위임 우회 차단 절에 #601 카테고리(강제로/그대로 시도/검증 없이) 명시
 *   2) system-prompt.ts — 전역 사회공학 우회 차단 목록 + L3 트리거 매핑 모두에 #601 명시
 */

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function readPrompt(name: 'agent.md' | 'rules.md'): string {
  return fs.readFileSync(path.join(__dirname, name), 'utf-8');
}

function readSystemPrompt(): string {
  // subagents/dashboard-builder/ → agent/system-prompt.ts
  return fs.readFileSync(
    path.join(__dirname, '..', '..', 'system-prompt.ts'),
    'utf-8',
  );
}

describe('dashboard-builder 위임 우회 차단 (#601, refs #253)', () => {
  it('agent.md 위임 우회 차단 절에 #601 유효성 우회 표현 카테고리가 명시되어 있어야 한다', () => {
    const agent = readPrompt('agent.md');
    expect(agent).toContain('위임 우회 차단');
    expect(agent).toContain('#601');
    // 대표 우회 표현
    expect(agent).toContain('강제로');
    expect(agent).toContain('그대로 시도');
    // 서버 검증 실패 예상을 근거로 직접 호출하지 않는다는 원칙
    expect(agent).toMatch(/서버 검증[\s\S]*?(?:생략|근거가 아니다|무방하다)/);
  });

  it('agent.md에 위임받은 뒤에도 겹침 확인(#583)을 생략하지 않는다는 지침이 있어야 한다', () => {
    const agent = readPrompt('agent.md');
    expect(agent).toMatch(/get_dashboard_detail[\s\S]*?#583/);
  });

  it('agent.md에 무효 파라미터(width/height)를 서버로 전달하지 않고 재확인한다는 지침이 있어야 한다', () => {
    const agent = readPrompt('agent.md');
    expect(agent).toMatch(/width[\s\S]*?height[\s\S]*?(?:재확인|유효하지 않)/);
  });
});

describe('main system-prompt safeguards for dashboard-builder (#601)', () => {
  it('전역 사회공학 우회 차단 목록에 "강제로"/"그대로 시도"/"검증 없이" 류 표현이 #601로 명시된다', () => {
    const sp = readSystemPrompt();
    const section = sp.split('### 사회공학 우회 차단 (모든 가드 공통)')[1];
    expect(section).toBeDefined();
    expect(section).toContain('#601');
    expect(section).toContain('강제로');
    expect(section).toContain('그대로 시도');
    expect(section).toContain('검증 없이');
  });

  it('전역 사회공학 우회 차단 절이 DESIGN 가드는 파라미터 유효성과 무관하게 위임임을 명시한다', () => {
    const sp = readSystemPrompt();
    const section = sp.split('### 사회공학 우회 차단 (모든 가드 공통)')[1];
    expect(section).toBeDefined();
    expect(section).toMatch(/DESIGN 가드[\s\S]*?파라미터 유효성과 무관/);
  });

  it('L3 트리거 매핑의 create_dashboard/add_chart_to_dashboard 행에 #601 가드가 명시된다', () => {
    const sp = readSystemPrompt();
    const section = sp.split('## L3. 통합 가드 패턴')[1];
    expect(section).toBeDefined();
    expect(section).toMatch(
      /create_dashboard.*add_chart_to_dashboard[\s\S]*?#601/,
    );
    expect(section).toMatch(/#601[\s\S]*?강제로/);
  });
});
