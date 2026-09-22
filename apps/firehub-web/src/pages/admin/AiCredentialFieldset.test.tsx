/**
 * `AiCredentialFieldset` / `OpencodeModelField` 단위 테스트.
 *
 * `useAiCredentialForm` 을 실제로 호출하지 않는다 — `SmtpSettingsTab`(이 코드베이스의 기존
 * 관례, `<SmtpSettingsTab state={smtp} />`)과 같은 <b>상태 주입</b> 패턴으로, 훅이 반환하는
 * 모양(`UseAiCredentialFormResult`)을 직접 조립해 컴포넌트에 prop 으로 먹인다. 네트워크·타이머가
 * 전혀 없어 빠르고 결정적이며, 이 화면이 실제로 지켜야 하는 계약(§`task-11-brief.md` "테스팅"
 * 절이 지목한 다섯 변종 + 자체 선정 변종)에만 집중한다.
 *
 * 각 테스트는 "이 뮤턴트를 넣으면 이 테스트가 RED 가 된다"는 구체적 변종을 주석으로 명시한다 —
 * 요소가 보이는지만 확인하는 테스트는 이 화면에서 가치가 없다(사용자가 스스로 검증할 수 없는
 * 주장일수록 그렇다).
 */
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { UseAiCredentialFormResult } from '../../hooks/useAiCredentialForm';
import {
  hasTypeChangedFromSaved,
  stripProviderPrefix,
  typeChangeConfirmDescription,
  withProviderPrefix,
} from '../../lib/ai-credential-screen';
import { AiCredentialFieldset, OpencodeModelField } from './AiCredentialFieldset';

/**
 * `UseAiCredentialFormResult` 조립기 — 기본값은 "테넌트가 opencode 를 직접 설정해 둔, 저장된
 * API 키가 있는 상태"다(가장 많은 분기를 건드리는 상태를 기본으로 둔다). 개별 테스트는
 * `overrides` 로 필요한 축만 바꾼다. `savedAgentType` 을 주지 않으면 지금 유형과 같다고 보고,
 * `typeChanged` 는 훅과 같은 규칙(`hasTypeChangedFromSaved`)으로 파생한다.
 */
function makeCred(overrides: Partial<UseAiCredentialFormResult> = {}): UseAiCredentialFormResult {
  const base: UseAiCredentialFormResult = {
    isLoading: false,
    loadFailed: false,
    isLocked: false,
    configured: true,
    agentType: 'opencode',
    setAgentType: vi.fn(),
    payload: { providerId: 'openai', baseURL: 'https://api.openai.com/v1', reasoningEffort: '' },
    setPayloadField: vi.fn(),
    secretInputs: {},
    setSecretInput: vi.fn(),
    secretFieldNames: ['apiKey'],
    models: null,
    loadModels: vi.fn(),
    modelsError: null,
    canLoadModels: true,
    hasUnsavedInput: false,
    save: vi.fn(async () => true),
    staleNotice: null,
    reset: vi.fn(),
    savedAgentType: 'opencode',
    typeChanged: false,
    ...overrides,
  };
  const savedAgentType = overrides.savedAgentType ?? base.agentType;
  return {
    ...base,
    savedAgentType,
    typeChanged:
      overrides.typeChanged ?? hasTypeChangedFromSaved(base.agentType, savedAgentType, base.configured),
  };
}

function renderFieldset(cred: UseAiCredentialFormResult) {
  return render(
    <AiCredentialFieldset
      cred={cred}
      authStatus={null}
      isVerifying={false}
      onVerifyAuth={vi.fn()}
    />,
  );
}

describe('AiCredentialFieldset — 비밀 필드 표시', () => {
  /**
   * <b>변종: `secretFieldNames` 를 필터링 전 원본으로 렌더한다.</b>
   *
   * `cred.secretFieldNames` 는 훅이 이미 유형 불일치를 걸러 낸 값이다. 이
   * 테스트는 <b>필터링된 뒤에도 값이 없는 상황</b>(`secretFieldNames: []`)을 흉내 낸다 — 만약
   * 컴포넌트가 `secretFieldNames` 대신 어떤 이유로든 필터를 우회한 값(예: 항상 "값 있음"으로
   * 가정)을 쓴다면 이 단언이 깨진다. "설정된 값이 없습니다"가 반드시 보여야 한다.
   */
  it('secretFieldNames 가 비어 있으면 API 키에 "설정된 값이 없습니다" 를 보여준다 — 있다고 우기지 않는다', () => {
    renderFieldset(makeCred({ secretFieldNames: [] }));
    expect(screen.getByText('설정된 값이 없습니다.')).toBeInTheDocument();
    expect(screen.queryByText(/현재 값이 설정되어 있습니다/)).not.toBeInTheDocument();
  });

  it('secretFieldNames 에 apiKey 가 있으면 "현재 값이 설정되어 있습니다" 를 보여준다', () => {
    renderFieldset(makeCred({ secretFieldNames: ['apiKey'] }));
    expect(screen.getByText(/현재 값이 설정되어 있습니다/)).toBeInTheDocument();
  });

  /**
   * <b>변종: sdk 유형에서 oauthToken 만 저장돼 있는데 apiKey 힌트도 "설정됨"으로 그린다.</b>
   * 두 비밀은 이름이 다르므로 서로의 존재 여부에 영향을 주면 안 된다(사실절/행동절 분리, §191).
   */
  it('sdk 유형 — oauthToken 만 secretFieldNames 에 있으면 apiKey 는 "없음"을 유지한다', () => {
    renderFieldset(
      makeCred({
        agentType: 'sdk',
        payload: {},
        secretFieldNames: ['oauthToken'],
      }),
    );
    const noneTexts = screen.getAllByText('설정된 값이 없습니다.');
    expect(noneTexts).toHaveLength(1); // API 키 쪽만 "없음" — OAuth 토큰 쪽은 "설정되어 있음"
    expect(screen.getByText(/현재 값이 설정되어 있습니다/)).toBeInTheDocument();
  });

  /**
   * <b>변종: 비밀 입력창에 마스크(`****ab12` 류) 나 "설정됨" 같은 플레이스홀더 값을 시드한다.</b>
   * 서버가 비밀 값을 절대 내려주지 않으므로(§54 "비밀 처리") 입력창은 항상 빈 채로 시작해야
   * 한다 — 덧붙이기 공격(마스크 뒤에 진짜 키를 이어 붙여 마스크 판정을 피하는)을 계약 수준에서
   * 막는다. `secretInputs` 는 <b>훅이 소유</b>하고 이 컴포넌트는 그 값을 그대로 `value` 에
   * 꽂을 뿐이므로, "설정됨" 을 힌트 문구가 아니라 입력값으로 시드하면 이 단언이 깨진다.
   */
  it('설정된 비밀이 있어도 입력창 자체는 항상 빈 문자열이다 — 마스크를 시드하지 않는다', () => {
    renderFieldset(makeCred({ secretFieldNames: ['apiKey'], secretInputs: {} }));
    const input = screen.getByLabelText('API 키') as HTMLInputElement;
    expect(input.value).toBe('');
  });
});

describe('AiCredentialFieldset — 테넌트 전용 단일 폼(#706)', () => {
  /**
   * <b>변종: 옛 "플랫폼 설정을 사용 / 우리 조직이 직접 설정" 라디오를 남겨 둔다.</b> 플랫폼 평면이
   * 사라져 "플랫폼 값"을 고를 곳이 없다 — 라디오가 남아 있으면 존재하지 않는 선택지를 약속한다.
   */
  it('라디오가 없고 입력 폼(유형 Select + 유형별 필드)이 항상 보인다', () => {
    renderFieldset(makeCred({ configured: true }));
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
    expect(screen.queryByText(/플랫폼 설정을 사용/)).not.toBeInTheDocument();
    expect(screen.getByLabelText('에이전트 유형')).toBeInTheDocument();
    expect(screen.getByLabelText('기본 URL')).toBeInTheDocument();
  });

  /**
   * <b>변종: `configured:false` 인데 안내 없이 빈 폼만 그린다.</b> 플랫폼 폴백이 없으므로 미설정은
   * 곧 AI 중단이다 — 사용자가 "왜 AI 가 안 되는지"를 이 화면에서 알 수 있어야 한다. 문장을
   * 정확히 고정한다(부분 일치로 두면 뒷부분 "설정해야 …"가 잘려도 초록이 된다).
   */
  it('configured=false 면 미설정 안내를 전체 문장 그대로 보여주고, 입력 폼도 함께 보인다', () => {
    renderFieldset(makeCred({ configured: false, agentType: 'sdk', payload: {}, secretFieldNames: [] }));
    expect(screen.getByText('AI 설정이 없습니다. 설정해야 AI 기능을 쓸 수 있습니다.')).toBeInTheDocument();
    // 안내만 보여주고 폼을 숨기면 설정할 방법이 없다.
    expect(screen.getByLabelText('OAuth 토큰')).toBeEnabled();
    expect(screen.getByLabelText('API 키')).toBeEnabled();
  });

  it('configured=true 면 미설정 안내가 없다', () => {
    renderFieldset(makeCred({ configured: true }));
    expect(screen.queryByText(/AI 설정이 없습니다/)).not.toBeInTheDocument();
  });
});

describe('typeChangeConfirmDescription — 저장 확인 다이얼로그 문구', () => {
  it('유형 전환 확인 문구를 준다 — "재정의"/과금 문구는 쓰지 않는다', () => {
    const description = typeChangeConfirmDescription('opencode', 'sdk');
    expect(description).toMatch(/OpenCode에서 Claude Agent SDK\(으\)로/);
    expect(description).not.toMatch(/재정의/);
    expect(description).not.toMatch(/사용량도 우리 계정으로 청구/);
  });
});

describe('hasTypeChangedFromSaved', () => {
  it('테넌트 자격증명이 아직 없으면(configured=false) 유형이 달라도 경고하지 않는다 — 잃을 게 없다', () => {
    expect(hasTypeChangedFromSaved('opencode', 'sdk', false)).toBe(false);
  });

  it('테넌트 자격증명이 있고(configured=true) 유형이 다르면 경고한다', () => {
    expect(hasTypeChangedFromSaved('opencode', 'sdk', true)).toBe(true);
  });

  it('유형이 같으면 경고하지 않는다', () => {
    expect(hasTypeChangedFromSaved('sdk', 'sdk', true)).toBe(false);
  });
});

describe('AiCredentialFieldset — 유형 전환 경고(설계서 §193)', () => {
  it('저장된 유형과 다르면 Select 아래에 정적 경고가 뜬다', () => {
    const cred = makeCred({ agentType: 'sdk', savedAgentType: 'opencode', payload: {}, secretFieldNames: [] });
    renderFieldset(cred);
    expect(screen.getByText(/유형을 바꾸면 이전 유형\(OpenCode\)의 저장된 비밀이/)).toBeInTheDocument();
  });

  it('저장된 유형과 같으면 경고가 없다', () => {
    const cred = makeCred({ agentType: 'sdk', payload: {}, secretFieldNames: [] });
    renderFieldset(cred);
    expect(screen.queryByText(/저장된 비밀이/)).not.toBeInTheDocument();
  });

  it('미설정(configured=false)이면 유형이 달라도 경고가 없다 — 폐기될 비밀이 없다', () => {
    const cred = makeCred({
      configured: false,
      agentType: 'opencode',
      savedAgentType: 'sdk',
      payload: {},
      secretFieldNames: [],
    });
    renderFieldset(cred);
    expect(screen.queryByText(/저장된 비밀이/)).not.toBeInTheDocument();
  });
});

describe('AiCredentialFieldset — 최초 조회 실패(Important #1, fix round 1)', () => {
  /**
   * <b>변종: `loadFailed` 를 무시하고 평소 렌더 경로(미설정 안내 + 폼)를 그대로 그린다.</b>
   * GET 이 실패하면 훅은 안전한 초기값(`configured:false, secretFieldNames:[]`)으로 주저앉는다 —
   * 그 초기값을 평소처럼 그리면 "AI 설정이 없습니다"를 <b>사실</b>인 것처럼 보여주게 된다(실은
   * 몰라서 못 그리는 것뿐인데). 토스트는 지나가 버리므로 지속적인 실패 문구가 남아야 하고, 평소
   * 폼·미설정 안내는 <b>같이 그려지면 안 된다</b>.
   */
  it('loadFailed 면 폼·미설정 안내 대신 지속적인 실패 안내를 보여준다', () => {
    const cred = makeCred({
      loadFailed: true,
      configured: false,
      secretFieldNames: [],
      payload: {},
    });
    renderFieldset(cred);
    expect(screen.getByText(/자격증명 정보를 불러오지 못했습니다/)).toBeInTheDocument();
    expect(screen.queryByText(/AI 설정이 없습니다/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText('에이전트 유형')).not.toBeInTheDocument();
  });
});

describe('AiCredentialFieldset — 잠금 상태(GET 403)', () => {
  /**
   * <b>변종: 403 후 초기값(`configured:false`)으로 미설정 안내를 그린다.</b> 권한이 없어 볼 수
   * 없는 것을 "설정이 없다"고 단정하면 loadFailed 와 같은 종류의 거짓이 된다.
   */
  it('isLocked 이면 권한 안내만 보여주고 폼·미설정 안내는 그리지 않는다', () => {
    const cred = makeCred({ isLocked: true, configured: false, secretFieldNames: [], payload: {} });
    renderFieldset(cred);
    expect(screen.getByText('AI 자격증명을 조회·변경할 권한이 없습니다.')).toBeInTheDocument();
    expect(screen.queryByText(/AI 설정이 없습니다/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText('에이전트 유형')).not.toBeInTheDocument();
  });
});

describe('AiCredentialFieldset — opencode 는 "인증 확인" 을 숨긴다', () => {
  it('opencode 유형에는 인증 확인 버튼이 없다', () => {
    renderFieldset(makeCred({ agentType: 'opencode' }));
    expect(screen.queryByRole('button', { name: /인증 확인/ })).not.toBeInTheDocument();
  });

  it('sdk 유형에는 인증 확인 버튼이 둘(OAuth·API 키) 있다', () => {
    renderFieldset(makeCred({ agentType: 'sdk', payload: {}, secretFieldNames: [] }));
    expect(screen.getAllByRole('button', { name: /인증 확인/ })).toHaveLength(2);
  });
});

describe('withProviderPrefix / stripProviderPrefix — 모델 접두사 단일 지점', () => {
  it('providerId 가 있으면 접두사를 붙인다', () => {
    expect(withProviderPrefix('gpt-4o', 'openai')).toBe('openai/gpt-4o');
  });

  /**
   * <b>변종: providerId 가 비어 있어도 접두사를 붙인다("/gpt-4o").</b> 막 opencode 로 전환해
   * 공급자를 아직 고르지 않은 상태에서 모델을 먼저 만지면 이 경로를 탄다 — 접두사만 남은 깨진
   * 값이 저장되면 안 된다.
   */
  it('providerId 가 비어 있으면 접두사를 붙이지 않는다', () => {
    expect(withProviderPrefix('gpt-4o', '')).toBe('gpt-4o');
  });

  it('왕복(strip→with)이 원래 값을 복원한다', () => {
    const composed = withProviderPrefix('gpt-4o', 'openai');
    expect(stripProviderPrefix(composed, 'openai')).toBe('gpt-4o');
  });

  it('다른 provider 접두사는 벗기지 않는다(정체성 불일치)', () => {
    expect(stripProviderPrefix('anthropic/claude-3', 'openai')).toBe('anthropic/claude-3');
  });
});

describe('OpencodeModelField — 모델 칸 4상태(설계서 §195)', () => {
  function renderModel(cred: UseAiCredentialFormResult, modelValue = '') {
    const onModelChange = vi.fn();
    const view = render(
      <OpencodeModelField cred={cred} modelValue={modelValue} onModelChange={onModelChange} />,
    );
    return { onModelChange, ...view };
  }

  it('① 미로드 — 비활성 입력 + "먼저 모델을 불러오세요"', () => {
    renderModel(makeCred({ models: null, modelsError: null }));
    expect(screen.getByPlaceholderText('먼저 모델을 불러오세요')).toBeDisabled();
  });

  /**
   * <b>변종: 미로드/실패 칸이 `value=""` 를 하드코딩한다</b>(Minor #8, fix round 1). 이전에는
   * 이 값이 항상 빈 문자열이라, 이미 `openai/gpt-4o` 를 저장해 둔 테넌트가 돌아와도 모델 칸이
   * 텅 비어 보였다 — 값이 사라진 게 아니라 그냥 안 보여준 것뿐인데 사용자는 구분할 수 없다.
   */
  it('① 미로드여도 저장된 모델 값(bareModel)을 그대로 보여준다 — 빈 문자열로 지우지 않는다', () => {
    renderModel(
      makeCred({
        models: null,
        modelsError: null,
        payload: { providerId: 'openai', baseURL: 'https://x', reasoningEffort: '' },
      }),
      'openai/gpt-4o',
    );
    expect(screen.getByPlaceholderText('먼저 모델을 불러오세요')).toHaveValue('gpt-4o');
  });

  it('④ 실패해도 저장된 모델 값을 그대로 보여준다', () => {
    renderModel(
      makeCred({
        models: null,
        modelsError: '실패했습니다.',
        payload: { providerId: 'openai', baseURL: 'https://x', reasoningEffort: '' },
      }),
      'openai/gpt-4o',
    );
    expect(screen.getByPlaceholderText('먼저 모델을 불러오세요')).toHaveValue('gpt-4o');
  });

  it('② 목록 있음 — Select 로 그리고 "✓ 모델 N개" 를 보여준다', () => {
    renderModel(makeCred({ models: ['gpt-4o', 'gpt-4o-mini'], modelsError: null }));
    expect(screen.getByText('✓ 모델 2개')).toBeInTheDocument();
    // 미로드와 같은 "먼저 모델을 불러오세요" placeholder 가 남아 있으면 안 된다(모양이 겹치면 안 됨)
    expect(screen.queryByPlaceholderText('먼저 모델을 불러오세요')).not.toBeInTheDocument();
  });

  it('③ 목록 없음(빈 배열) — 자유 입력으로 전환된다', () => {
    renderModel(makeCred({ models: [], modelsError: null }));
    expect(screen.getByPlaceholderText('예: gpt-4o')).toBeEnabled();
    expect(screen.getByText('공급자가 모델 목록을 주지 않아 직접 입력합니다.')).toBeInTheDocument();
  });

  /**
   * <b>변종: 실패 상태를 미로드와 같은 모양(그냥 비활성 입력)으로만 그리고 오류 문구·전환
   * 버튼을 생략한다.</b> 설계서 §195: "미로드와 실패가 같은 모양이 되지 않게 한다." 이 테스트가
   * 요구하는 "직접 입력으로 전환" 버튼이 브리프의 다섯 필수 변종 중 하나다.
   */
  it('④ 실패 — 오류 문구 + "직접 입력으로 전환" 버튼이 함께 있다(미로드와 다른 모양)', () => {
    renderModel(makeCred({ models: null, modelsError: '모델 목록을 불러오지 못했습니다.' }));
    expect(screen.getByText('모델 목록을 불러오지 못했습니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '직접 입력으로 전환' })).toBeInTheDocument();
  });

  it('"직접 입력으로 전환" 클릭 시 자유 입력 칸으로 바뀐다', async () => {
    const user = userEvent.setup();
    renderModel(makeCred({ models: null, modelsError: '실패했습니다.' }));
    await user.click(screen.getByRole('button', { name: '직접 입력으로 전환' }));
    expect(screen.getByPlaceholderText('예: gpt-4o')).toBeInTheDocument();
    expect(screen.queryByText('실패했습니다.')).not.toBeInTheDocument();
  });

  /**
   * <b>변종: `canLoadModels===false` 인데도 "모델 불러오기" 버튼을 클릭 가능(활성)하게 그린다.</b>
   * 브리프: "canLoadModels 가 false 일 때 모델 불러오기를 활성화하면 → RED." 훅 문서(Ruling
   * #35/#37)가 이 조건을 "누르면 반드시 400" 인 상태로 정의하므로, 버튼이 활성이면 안 된다.
   */
  it('canLoadModels=false 면 "모델 불러오기" 버튼이 비활성이다', () => {
    renderModel(makeCred({ canLoadModels: false }));
    expect(screen.getByRole('button', { name: '모델 불러오기' })).toBeDisabled();
  });

  it('canLoadModels=true 면 "모델 불러오기" 버튼이 활성이고 클릭하면 loadModels 를 부른다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ canLoadModels: true });
    renderModel(cred);
    const button = screen.getByRole('button', { name: '모델 불러오기' });
    expect(button).toBeEnabled();
    await user.click(button);
    expect(cred.loadModels).toHaveBeenCalledOnce();
  });

  /**
   * 자유 입력 분기(목록 없음/실패→전환)로 접두사 부착을 검증한다 — Select 드롭다운을 실제로
   * 여는 경로는 쓰지 않는다(Radix 팝오버를 jsdom 에서 열려면 hasPointerCapture/scrollIntoView
   * 폴리필이 필요해 이 스위트의 범위 밖이다, `withProviderPrefix` 단위 테스트가 그 규칙 자체는
   * 이미 고정한다).
   */
  it('자유 입력에 맨 모델 id 를 치면 providerId 접두사를 붙여 onModelChange 를 부른다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({
      models: [],
      modelsError: null,
      payload: { providerId: 'openai', baseURL: 'https://x', reasoningEffort: '' },
    });
    const { onModelChange } = renderModel(cred, '');
    await user.type(screen.getByPlaceholderText('예: gpt-4o'), 'g');
    expect(onModelChange).toHaveBeenCalledWith('openai/g');
  });

  it('"기본 URL과 API 키를 입력하면" 안내는 canLoadModels=false 일 때만 보인다', () => {
    const { unmount } = renderModel(makeCred({ canLoadModels: false }));
    expect(screen.getByText('기본 URL과 API 키를 입력하면 모델을 불러올 수 있습니다.')).toBeInTheDocument();
    unmount();
    renderModel(makeCred({ canLoadModels: true }));
    expect(
      screen.queryByText('기본 URL과 API 키를 입력하면 모델을 불러올 수 있습니다.'),
    ).not.toBeInTheDocument();
  });
});
