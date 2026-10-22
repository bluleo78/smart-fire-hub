/**
 * error-classifier 단위 테스트 — 에러 메시지 패턴 분류.
 */
import { describe, expect, it } from 'vitest';

import { classifyError, classifyExecutionOutcome, getExecutionDisplayStatus } from './error-classifier';

describe('classifyError', () => {
  it('null/undefined/empty → unknown (메시지 없음)', () => {
    expect(classifyError(null).type).toBe('unknown');
    expect(classifyError(undefined).type).toBe('unknown');
    expect(classifyError('').type).toBe('unknown');
    expect(classifyError(null).label).toBe('알 수 없는 오류');
  });

  it('AI 키워드 감지', () => {
    expect(classifyError('Rate limit exceeded').type).toBe('ai');
    expect(classifyError('Claude model overloaded').type).toBe('ai');
    expect(classifyError('Anthropic API error').type).toBe('ai');
    expect(classifyError('token usage exceeded').type).toBe('ai');
  });

  it('데이터 접근 키워드 감지', () => {
    expect(classifyError('Database connection refused').type).toBe('data');
    expect(classifyError('Query timeout').type).toBe('data');
    expect(classifyError('SQL syntax error').type).toBe('data');
    expect(classifyError('Datasource not available').type).toBe('data');
  });

  it('채널 전달 키워드 감지', () => {
    expect(classifyError('Email delivery failed').type).toBe('channel');
    expect(classifyError('SMTP connection').type).toBe('data'); // connection이 먼저 매칭됨 — data 패턴이 우선
    expect(classifyError('Mail server error').type).toBe('channel');
  });

  it('매칭 안 되는 메시지는 fallback unknown', () => {
    const result = classifyError('Something weird happened');
    expect(result.type).toBe('unknown');
    expect(result.label).toBe('기타 오류');
  });

  it('대소문자 무관', () => {
    expect(classifyError('RATE LIMIT').type).toBe('ai');
    expect(classifyError('DATABASE DOWN').type).toBe('data');
  });

  it('반환 객체에 icon/guide 포함', () => {
    const result = classifyError('rate limit');
    expect(result.icon).toBeTruthy();
    expect(result.guide).toBeTruthy();
  });

  it('미분류 폴백 아이콘은 흰색(⚪)이 아니다 (#525 — destructive 카드 배경에서 안 보임)', () => {
    expect(classifyError(null).icon).not.toBe('⚪');
    expect(classifyError('Something weird happened').icon).not.toBe('⚪');
  });

  /**
   * 서버(firehub-api)가 실행 이력에 실제로 남기는 한국어 사유 (WD-191).
   * 문구는 서버 코드에서 그대로 옮겼다 — 서버 문구를 바꾸면 여기와 error-classifier.ts 앵커를 함께 고친다.
   */
  describe('서버 한국어 사유 (WD-191)', () => {
    // ProactiveJobAsyncRunner.REASON_TENANT_SUSPENDED, ProactiveOwnerEligibility.REASON_OWNER_INACTIVE/NO_PERMISSION
    const SKIP_REASONS = [
      '워크스페이스가 정지되어 실행하지 않았습니다',
      '작업 주인이 정지됐거나 워크스페이스에서 제거돼 실행하지 않았습니다',
      '작업 주인에게 스마트 작업 권한이 없어 실행하지 않았습니다',
    ];

    // AiCredential.*.incompleteMessage()/modelProblem(), UnknownAgentTypeException, AiCredentialDocument.parse(),
    // EncryptionService(복호화 실패), ProactiveResultValidator.USER_FACING_FAILURE_MESSAGE
    const AI_CONFIG_REASONS = [
      'AI API 키 또는 OAuth 토큰이 설정되지 않았습니다. 관리자 설정에서 등록하세요.',
      'Claude CLI OAuth 토큰이 설정되지 않았습니다. 관리자 설정에서 토큰을 등록하세요.',
      'AI API 키가 설정되지 않았습니다. 관리자 설정에서 API 키를 등록하세요.',
      'AI 공급자 설정(공급자/기본 URL)이 완전하지 않습니다. 관리자 설정에서 opencode 설정을 확인하세요.',
      // 모델 id 에 'anthropic'·'claude'·'model' 같은 영어가 섞여도 AI 모델 오류(재시도 안내)로 가면 안 된다
      'AI 모델(anthropic/claude-model-x)이 opencode 형식(공급자/모델)이 아니거나 선택한 공급자와 일치하지 않습니다. 관리자 설정에서 모델을 다시 선택하세요.',
      '알 수 없는 AI 자격증명 유형입니다: weird',
      'ai.credential JSON 파싱에 실패했다: {broken',
      'Decryption failed',
      "Invalid encrypted format — expected 'iv:ciphertext'",
      'AI 분석을 완료하지 못해 리포트를 생성하지 못했습니다. 관리 > 설정에서 AI 인증 정보를 확인해 주세요.',
    ];

    // ProactiveAiClient.AGENT_CALL_FAILED_MESSAGE — 「AI 설정을 확인」도 들어 있지만 호출 실패(일시 오류)가 먼저다
    const AI_TRANSIENT_REASON =
      'AI 에이전트 호출에 실패해 리포트를 생성하지 못했습니다. 잠시 후 다시 시도하거나 관리 > 설정에서 AI 설정을 확인해 주세요.';

    it.each(SKIP_REASONS)('건너뜀 사유는 오류가 아닌 건너뜀으로 분류한다: %s', (reason) => {
      const result = classifyError(reason);
      expect(result.type).toBe('skipped');
      expect(result.label).toBe('건너뜀');
    });

    it.each(AI_CONFIG_REASONS)('AI 인증·설정 사유는 설정 확인 안내로 분류한다: %s', (reason) => {
      const result = classifyError(reason);
      expect(result.type).toBe('ai-config');
      expect(result.guide).toContain('관리 > 설정 > AI 에이전트');
      expect(result.guide).toContain('AI 인증 정보');
      // 다시 실행해도 안 고쳐지므로 재시도 안내를 하면 안 된다
      expect(result.guide).not.toContain('잠시 후 수동 실행');
    });

    it('AI 에이전트 호출 실패는 일시 오류(잠시 후 재실행)로 분류한다', () => {
      const result = classifyError(AI_TRANSIENT_REASON);
      expect(result.type).toBe('ai-transient');
      expect(result.guide).toContain('잠시 후 다시 실행');
    });

    it('매칭되지 않는 한국어 사유는 기타 오류로 남는다', () => {
      expect(classifyError('Job을 찾을 수 없습니다: 3').type).toBe('unknown');
      expect(classifyError('테넌트 컨텍스트 없이 진입했다: 스마트 작업 실행 워크스페이스 상태 확인').type).toBe('unknown');
    });
  });

  describe('classifyExecutionOutcome', () => {
    it('상태가 SKIPPED 면 사유 문구와 무관하게 건너뜀이다', () => {
      expect(classifyExecutionOutcome('SKIPPED', null)?.type).toBe('skipped');
      expect(classifyExecutionOutcome('SKIPPED', 'Rate limit exceeded')?.type).toBe('skipped');
    });

    it('FAILED 면 사유 문구로 분류한다(건너뜀 문구 포함)', () => {
      expect(classifyExecutionOutcome('FAILED', '워크스페이스가 정지되어 실행하지 않았습니다')?.type).toBe('skipped');
      expect(classifyExecutionOutcome('FAILED', 'Rate limit exceeded')?.type).toBe('ai');
      expect(classifyExecutionOutcome('FAILED', null)?.label).toBe('알 수 없는 오류');
    });

    it('실행 중·완료 등은 카드를 그리지 않는다(null)', () => {
      expect(classifyExecutionOutcome('RUNNING', null)).toBeNull();
      expect(classifyExecutionOutcome('COMPLETED', null)).toBeNull();
      expect(classifyExecutionOutcome('PENDING', '실행하지 않았습니다')).toBeNull();
    });
  });

  describe('getExecutionDisplayStatus', () => {
    it('건너뜀 사유의 FAILED 만 SKIPPED 로 바꾸고 나머지는 그대로 둔다', () => {
      expect(getExecutionDisplayStatus('FAILED', '워크스페이스가 정지되어 실행하지 않았습니다')).toBe('SKIPPED');
      expect(getExecutionDisplayStatus('FAILED', 'AI API 키가 설정되지 않았습니다. 관리자 설정에서 API 키를 등록하세요.')).toBe('FAILED');
      expect(getExecutionDisplayStatus('FAILED', null)).toBe('FAILED');
      expect(getExecutionDisplayStatus('COMPLETED', null)).toBe('COMPLETED');
      expect(getExecutionDisplayStatus('RUNNING', null)).toBe('RUNNING');
    });
  });
});

