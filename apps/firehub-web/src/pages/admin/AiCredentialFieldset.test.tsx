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
import { act, render, renderHook, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { UseAiCredentialFormResult } from '../../hooks/useAiCredentialForm';
import { useSavedAgentType } from '../../hooks/useSavedAgentType';
import type { AgentType } from '../../lib/ai-credential';
import {
  buildSaveConfirm,
  credentialIsDirty,
  hasTypeChangedFromSaved,
  stripProviderPrefix,
  willDeleteOnSave,
  withProviderPrefix,
} from '../../lib/ai-credential-screen';
import { AiCredentialFieldset, OpencodeModelField } from './AiCredentialFieldset';

/**
 * `UseAiCredentialFormResult` 조립기 — 기본값은 "테넌트가 opencode 를 직접 설정해 둔, 저장된
 * API 키가 있는 상태"다(가장 많은 분기를 건드리는 상태를 기본으로 둔다). 개별 테스트는
 * `overrides` 로 필요한 축만 바꾼다.
 */
function makeCred(overrides: Partial<UseAiCredentialFormResult> = {}): UseAiCredentialFormResult {
  return {
    isLoading: false,
    loadFailed: false,
    isLocked: false,
    plane: 'tenant',
    tenantOwned: true,
    setPlane: vi.fn(),
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
    ...overrides,
  };
}

function renderFieldset(
  cred: UseAiCredentialFormResult,
  agentType: AgentType = cred.agentType,
  resolvedModel = '',
) {
  return render(
    <AiCredentialFieldset
      cred={cred}
      savedAgentType={agentType}
      authStatus={null}
      isVerifying={false}
      onVerifyAuth={vi.fn()}
      resolvedModel={resolvedModel}
    />,
  );
}

describe('AiCredentialFieldset — 비밀 필드 표시', () => {
  /**
   * <b>변종: `secretFieldNames` 를 필터링 전 원본으로 렌더한다.</b>
   *
   * `cred.secretFieldNames` 는 훅이 이미 유형·평면 불일치를 걸러 낸 값이다(Ruling #38). 이
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
      'sdk',
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

describe('AiCredentialFieldset — 라디오는 폼 상태다(즉시 DELETE 없음)', () => {
  /**
   * <b>변종: 라디오를 "플랫폼 설정을 사용"으로 클릭하면 그 자리에서 `cred.save()`(또는 DELETE)를
   * 부른다.</b> 설계서 §213: "전환은 폼 상태다 — 즉시 삭제하면 실수로 한 번 누른 대가가 모든
   * 비밀 재입력이다." 클릭은 `cred.setPlane('platform')` 만 불러야 하고, 저장/삭제는 페이지의
   * "저장" 버튼(그리고 확인 다이얼로그)을 거쳐야 한다 — 이 컴포넌트에는 애초에 `save` 호출
   * 지점이 없다.
   */
  it('"플랫폼 설정을 사용" 라디오 클릭은 setPlane 만 부르고 save/delete 는 부르지 않는다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ plane: 'tenant', tenantOwned: true });
    renderFieldset(cred);

    await user.click(screen.getByRole('radio', { name: /플랫폼 설정을 사용/ }));

    expect(cred.setPlane).toHaveBeenCalledExactlyOnceWith('platform');
    expect(cred.save).not.toHaveBeenCalled();
  });

  it('"우리 조직이 직접 설정" 라디오 클릭은 setPlane 만 부른다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ plane: 'platform', tenantOwned: false, secretFieldNames: [] });
    renderFieldset(cred);

    await user.click(screen.getByRole('radio', { name: /우리 조직이 직접 설정/ }));

    expect(cred.setPlane).toHaveBeenCalledExactlyOnceWith('tenant');
    expect(cred.save).not.toHaveBeenCalled();
  });
});

describe('AiCredentialFieldset — 라디오 설명 문구는 스펙이 고정한 그대로다(item 4, fix round 1)', () => {
  /**
   * <b>변종: "우리 조직이 직접 설정" 설명을 "사용량도 우리 계정으로 청구됩니다."로 바꾼다.</b>
   * 브리프·설계서 둘 다 이 문장을 명시적으로 금지한다 — cli(구독 OAuth)·사내 엔드포인트에서는
   * 거짓이다. 이전에는 어떤 테스트도 이 설명 문구의 <b>정확한 내용</b>을 확인하지 않아, 이
   * 변종을 넣어도 전부 초록이었다.
   */
  it('두 라디오의 설명 문구가 스펙 문구와 정확히 같다(부분 일치 아님)', () => {
    renderFieldset(makeCred({ plane: 'tenant', tenantOwned: false, secretFieldNames: [] }));
    expect(
      screen.getByText(
        '플랫폼 운영자가 정한 값이 그대로 적용됩니다. 운영자가 값을 바꾸면 우리 조직에도 함께 반영됩니다.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('AI 호출이 우리 조직 자격증명으로 나갑니다.')).toBeInTheDocument();
    // 금지된 과금 문구가 어디에도 없어야 한다.
    expect(screen.queryByText(/사용량도 우리 계정으로 청구/)).not.toBeInTheDocument();
  });
});

describe('willDeleteOnSave / credentialIsDirty — 순수 로직', () => {
  it('plane=platform + tenantOwned=true 일 때만 저장이 DELETE 를 일으킨다고 본다', () => {
    expect(willDeleteOnSave({ plane: 'platform', tenantOwned: true })).toBe(true);
    // 플랫폼에도 애초에 테넌트 문서가 없으면(tenantOwned=false) 지울 것이 없다
    expect(willDeleteOnSave({ plane: 'platform', tenantOwned: false })).toBe(false);
    expect(willDeleteOnSave({ plane: 'tenant', tenantOwned: true })).toBe(false);
  });

  it('라디오를 tenant→platform→tenant 로 왕복하면(원래 평면으로 복귀) dirty 가 아니다', () => {
    // tenantOwned=true 인 문서에서 라디오만 왕복했고 입력은 안 건드린 상태
    expect(
      credentialIsDirty({ plane: 'tenant', tenantOwned: true, hasUnsavedInput: false }),
    ).toBe(false);
  });

  it('라디오가 실제 소유 평면과 다르면(아직 저장 전) dirty 다', () => {
    expect(
      credentialIsDirty({ plane: 'platform', tenantOwned: true, hasUnsavedInput: false }),
    ).toBe(true);
  });

  it('타이핑만 하고 라디오는 그대로여도 dirty 다', () => {
    expect(
      credentialIsDirty({ plane: 'tenant', tenantOwned: true, hasUnsavedInput: true }),
    ).toBe(true);
  });
});

describe('buildSaveConfirm — 저장 확인 다이얼로그 문구', () => {
  it('플랫폼으로 되돌리는 저장은 확인이 필요하고, "재정의"/과금 문구를 쓰지 않는다', () => {
    const confirm = buildSaveConfirm({
      willDelete: true,
      typeChanged: false,
      hasUnsavedInput: false,
      agentType: 'opencode',
      savedAgentType: 'opencode',
    });
    expect(confirm).not.toBeNull();
    expect(confirm!.description).not.toMatch(/재정의/);
    expect(confirm!.description).not.toMatch(/사용량도 우리 계정으로 청구/);
  });

  it('미저장 입력이 있으면 "함께 사라집니다" 문장이 덧붙는다', () => {
    const withUnsaved = buildSaveConfirm({
      willDelete: true,
      typeChanged: false,
      hasUnsavedInput: true,
      agentType: 'opencode',
      savedAgentType: 'opencode',
    });
    const withoutUnsaved = buildSaveConfirm({
      willDelete: true,
      typeChanged: false,
      hasUnsavedInput: false,
      agentType: 'opencode',
      savedAgentType: 'opencode',
    });
    expect(withUnsaved!.description).toMatch(/아직 저장하지 않은 내용도 함께 사라집니다/);
    expect(withoutUnsaved!.description).not.toMatch(/아직 저장하지 않은 내용도 함께 사라집니다/);
  });

  it('유형만 바뀌었으면(플랫폼 삭제 아님) 별도의 유형 전환 확인 문구를 준다', () => {
    const confirm = buildSaveConfirm({
      willDelete: false,
      typeChanged: true,
      hasUnsavedInput: true,
      agentType: 'sdk',
      savedAgentType: 'opencode',
    });
    expect(confirm).not.toBeNull();
    expect(confirm!.description).toMatch(/OpenCode에서 Claude Agent SDK\(으\)로/);
  });

  it('아무 파괴적 전환도 없으면 확인 없이 즉시 저장(null)한다', () => {
    expect(
      buildSaveConfirm({
        willDelete: false,
        typeChanged: false,
        hasUnsavedInput: true,
        agentType: 'sdk',
        savedAgentType: 'sdk',
      }),
    ).toBeNull();
  });
});

describe('hasTypeChangedFromSaved', () => {
  it('테넌트 문서가 아직 없으면(tenantOwned=false) 유형이 달라도 경고하지 않는다 — 잃을 게 없다', () => {
    expect(hasTypeChangedFromSaved('opencode', 'sdk', false)).toBe(false);
  });

  it('테넌트 문서가 있고 유형이 다르면 경고한다', () => {
    expect(hasTypeChangedFromSaved('opencode', 'sdk', true)).toBe(true);
  });

  it('유형이 같으면 경고하지 않는다', () => {
    expect(hasTypeChangedFromSaved('sdk', 'sdk', true)).toBe(false);
  });
});

describe('AiCredentialFieldset — 유형 전환 경고(설계서 §193)', () => {
  it('저장된 유형과 다르면 Select 아래에 정적 경고가 뜬다', () => {
    const cred = makeCred({ agentType: 'sdk', payload: {}, secretFieldNames: [] });
    renderFieldset(cred, 'opencode' /* savedAgentType */);
    expect(screen.getByText(/유형을 바꾸면 이전 유형\(OpenCode\)의 저장된 비밀이/)).toBeInTheDocument();
  });

  it('저장된 유형과 같으면 경고가 없다', () => {
    const cred = makeCred({ agentType: 'sdk', payload: {}, secretFieldNames: [] });
    renderFieldset(cred, 'sdk');
    expect(screen.queryByText(/저장된 비밀이/)).not.toBeInTheDocument();
  });
});

describe('AiCredentialFieldset — 플랫폼 설정을 사용(입력칸 없음, §209)', () => {
  /**
   * <b>변종: 플랫폼에도 값이 없을 때 빈 정의 목록(또는 아무 안내도 없이 공백)을 그린다.</b>
   * 설계서 §210: "플랫폼에도 자격증명이 없으면 빈 목록이 아니라 [...] 를 보여준다." Ruling #15가
   * 이 판정을 `secretFieldNames` 가 비었는지로 유도하라고 명시한다.
   *
   * <b>전체 문장을 정확히 고정한다</b>(item 4, fix round 1) — 이전에는 접두 regex 만 확인해
   * "직접 설정하거나 플랫폼 운영자에게 요청하세요." 뒷부분을 잘라내도 초록이었다. 이 문장이
   * 플랫폼에 값이 전혀 없을 때 사용자가 다음에 뭘 해야 하는지 알려주는 유일한 안내라, 잘리면
   * "AI 가 왜 안 되는지"는 알아도 "어떻게 고치는지"는 사라진다.
   */
  it('플랫폼에도 값이 없으면 빈 목록 대신 "AI 기능이 동작하지 않습니다" 안내를 전체 문장 그대로 보여준다', () => {
    const cred = makeCred({
      plane: 'platform',
      tenantOwned: false,
      secretFieldNames: [],
      payload: {},
    });
    renderFieldset(cred);
    expect(
      screen.getByText(
        '플랫폼에 설정된 값이 없습니다 — AI 기능이 동작하지 않습니다. 직접 설정하거나 플랫폼 운영자에게 요청하세요.',
      ),
    ).toBeInTheDocument();
    // 값이 있는 것처럼 보이는 정의 목록(dl)이 함께 그려지면 안 된다
    expect(screen.queryByRole('term')).not.toBeInTheDocument();
  });

  it('플랫폼에 값이 있으면 정의 목록(유형·payload·설정된 비밀)을 보여준다', () => {
    const cred = makeCred({
      plane: 'platform',
      tenantOwned: false,
      agentType: 'opencode',
      payload: { providerId: 'openai', baseURL: 'https://api.openai.com/v1' },
      secretFieldNames: ['apiKey'],
    });
    renderFieldset(cred);
    expect(screen.getByText('OpenCode')).toBeInTheDocument();
    expect(screen.getByText('https://api.openai.com/v1')).toBeInTheDocument();
    expect(screen.getByText('지금 적용 중인 플랫폼 값입니다.')).toBeInTheDocument();
  });

  /**
   * <b>변종: "지금 적용 중" 정의 목록에서 모델 행을 뺀다</b>(Ruling #43, fix round 1). `ai.model`
   * 은 `ai.credential` 문서에 없어 이 컴포넌트가 원천적으로 모르지만, 페이지가 `resolvedModel`
   * prop 으로 <b>해석된</b> 값을 내려준다 — 목록에서 빠지면 "지금 적용 중인 값" 이 모델만 쏙
   * 빠진 반쪽짜리가 된다.
   */
  it('정의 목록에 "모델" 행이 resolvedModel 값으로 나온다', () => {
    const cred = makeCred({
      plane: 'platform',
      tenantOwned: false,
      agentType: 'opencode',
      payload: { providerId: 'openai', baseURL: 'https://api.openai.com/v1' },
      secretFieldNames: ['apiKey'],
    });
    renderFieldset(cred, cred.agentType, 'openai/gpt-4o');
    expect(screen.getByText('모델')).toBeInTheDocument();
    expect(screen.getByText('openai/gpt-4o')).toBeInTheDocument();
  });

  /**
   * <b>변종: `tenantOwned===true` 인 상태(라디오만 막 "플랫폼"으로 옮겼고 저장 전)에서도 진짜
   * 플랫폼 값인 것처럼 정의 목록을 그린다.</b> 이 순간 `cred.payload`/`secretFieldNames` 는
   * 여전히 <b>테넌트 문서</b>를 반영하므로(Ruling #38 필터가 이 경우엔 값을 그대로 통과시킨다 —
   * `agentType`/`plane` 이 원본과 아직 일치하기 때문) 그걸 "플랫폼 값"이라고 부르면 거짓말이다.
   */
  it('tenantOwned=true 인데 라디오만 플랫폼으로 옮긴 상태 — 정의 목록 대신 예고 문구만 보여준다', () => {
    const cred = makeCred({
      plane: 'platform',
      tenantOwned: true,
      agentType: 'opencode',
      payload: { providerId: 'openai', baseURL: 'https://api.openai.com/v1' },
      secretFieldNames: ['apiKey'],
    });
    renderFieldset(cred);
    expect(screen.getByText('저장하면 플랫폼 운영자가 정한 값이 적용됩니다.')).toBeInTheDocument();
    expect(screen.queryByText('https://api.openai.com/v1')).not.toBeInTheDocument();
    expect(screen.queryByText(/AI 기능이 동작하지 않습니다/)).not.toBeInTheDocument();
  });

  it('플랫폼 상태에는 입력칸이 없다', () => {
    const cred = makeCred({ plane: 'platform', tenantOwned: false, secretFieldNames: ['apiKey'] });
    renderFieldset(cred);
    expect(screen.queryByLabelText('API 키')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('기본 URL')).not.toBeInTheDocument();
  });
});

describe('AiCredentialFieldset — 최초 조회 실패(Important #1, fix round 1)', () => {
  /**
   * <b>변종: `loadFailed` 를 무시하고 평소 렌더 경로(라디오 + 평면 요약)를 그대로 그린다.</b>
   * GET 이 실패하면 훅은 안전한 기본값(`plane:'platform', tenantOwned:false,
   * secretFieldNames:[]`)으로 주저앉는다 — 그 기본값을 평소처럼 그리면 "플랫폼에 설정된 값이
   * 없습니다"를 <b>사실</b>인 것처럼 보여주게 된다(실은 몰라서 못 그리는 것뿐인데). 토스트는
   * 지나가 버리므로, 이 지속적인 실패 문구가 화면에 남아야 한다 — 그리고 평소 라디오/정의
   * 목록은 <b>같이 그려지면 안 된다</b>(반쪽 사실 + 반쪽 안내가 섞이면 더 헷갈린다).
   */
  it('loadFailed 면 라디오 대신 지속적인 실패 안내를 보여준다', () => {
    const cred = makeCred({
      loadFailed: true,
      plane: 'platform',
      tenantOwned: false,
      secretFieldNames: [],
      payload: {},
    });
    renderFieldset(cred);
    expect(screen.getByText(/자격증명 정보를 불러오지 못했습니다/)).toBeInTheDocument();
    // 실패 상태의 기본값을 "사실"처럼 그리는 평소 경로가 함께 나오면 안 된다.
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
    expect(screen.queryByText(/AI 기능이 동작하지 않습니다/)).not.toBeInTheDocument();
  });
});

describe('AiCredentialFieldset — 잠금 상태', () => {
  it('isLocked 이면 라디오 둘 다 비활성 + PlatformLockedNote', () => {
    const cred = makeCred({ isLocked: true });
    renderFieldset(cred);
    expect(screen.getByRole('radio', { name: /플랫폼 설정을 사용/ })).toBeDisabled();
    expect(screen.getByRole('radio', { name: /우리 조직이 직접 설정/ })).toBeDisabled();
    expect(screen.getByText('플랫폼 운영자만 변경할 수 있는 항목입니다.')).toBeInTheDocument();
  });
});

describe('AiCredentialFieldset — opencode 는 "인증 확인" 을 숨긴다', () => {
  it('opencode 유형에는 인증 확인 버튼이 없다', () => {
    renderFieldset(makeCred({ agentType: 'opencode' }));
    expect(screen.queryByRole('button', { name: /인증 확인/ })).not.toBeInTheDocument();
  });

  it('sdk 유형에는 인증 확인 버튼이 둘(OAuth·API 키) 있다', () => {
    renderFieldset(makeCred({ agentType: 'sdk', payload: {}, secretFieldNames: [] }), 'sdk');
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
      <OpencodeModelField cred={cred} modelValue={modelValue} onModelChange={onModelChange} disabled={false} />,
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

  /**
   * <b>변종: 플랫폼 평면에서 모델 칸이 여전히 비활성 빈 칸(4상태의 "미로드")이다</b>(Ruling
   * #44, fix round 1). 플랫폼 평면(§209)엔 기본 URL·API 키 입력이 없어 `canLoadModels` 가 항상
   * false 다 — 4상태 로직을 그대로 적용하면 모델을 절대 정할 수 없다. §48이 약속한 "플랫폼
   * 자격증명 + 우리 모델" 조합이 막히면 이 테스트가 잡는다.
   */
  it('플랫폼 평면이면 모델이 항상 편집 가능한 자유 입력이다(canLoadModels 와 무관)', async () => {
    const user = userEvent.setup();
    const cred = makeCred({
      plane: 'platform',
      tenantOwned: false,
      models: null,
      modelsError: null,
      canLoadModels: false,
      payload: { providerId: 'openai', baseURL: '', reasoningEffort: '' },
    });
    const { onModelChange } = renderModel(cred, 'openai/gpt-4o');
    const input = screen.getByPlaceholderText('예: gpt-4o');
    expect(input).toBeEnabled();
    expect(input).toHaveValue('gpt-4o');
    // 이 렌더는 상태 없는 prop 주입이라 매 keystroke 마다 value 가 bareModel 로 되돌아간다(기존
    // "자유 입력에 맨 모델 id..." 테스트와 같은 이유) — 한 글자만 쳐서 접두사 조합만 확인한다.
    await user.type(input, '!');
    expect(onModelChange).toHaveBeenCalledWith('openai/gpt-4o!');
  });

  /**
   * <b>변종: 플랫폼 평면에서도 "모델 불러오기" 버튼과 "기본 URL과 API 키를 입력하면..." 힌트를
   * 그대로 그린다.</b> 그 평면엔 기본 URL·API 키 입력 자체가 없으니, 버튼은 영원히 눌리지 않고
   * 힌트는 존재하지 않는 입력칸을 가리키는 거짓 안내가 된다.
   */
  it('플랫폼 평면에는 "모델 불러오기" 버튼도, 그 안내 문구도 없다', () => {
    renderModel(
      makeCred({
        plane: 'platform',
        tenantOwned: false,
        models: null,
        modelsError: null,
        canLoadModels: false,
        payload: { providerId: 'openai', baseURL: '', reasoningEffort: '' },
      }),
      'openai/gpt-4o',
    );
    expect(screen.queryByRole('button', { name: '모델 불러오기' })).not.toBeInTheDocument();
    expect(
      screen.queryByText('기본 URL과 API 키를 입력하면 모델을 불러올 수 있습니다.'),
    ).not.toBeInTheDocument();
  });
});

describe('useSavedAgentType — 서버와 동기화된 유형 스냅샷', () => {
  it('동기화된 상태에서만 갱신되고, dirty 인 동안은 마지막 값을 유지한다', () => {
    let cred = makeCred({ agentType: 'sdk', plane: 'tenant', tenantOwned: true, hasUnsavedInput: false });
    const view = renderHook(({ c }) => useSavedAgentType(c), { initialProps: { c: cred } });
    expect(view.result.current).toBe('sdk');

    // 사용자가 로컬에서 유형을 바꾼다(아직 저장 전 — dirty)
    cred = { ...cred, agentType: 'opencode', hasUnsavedInput: true };
    act(() => view.rerender({ c: cred }));
    // dirty 인 동안은 "저장된 값"이 그대로 sdk 여야 한다 — 지금 값(opencode)으로 따라가면
    // 비교 기준 자체가 사라져 유형 전환 경고가 절대 뜨지 않는다.
    expect(view.result.current).toBe('sdk');

    // 저장이 성공해 훅이 재조회로 폼을 다시 채웠다고 가정 — dirty 가 풀리고 opencode 로 동기화
    cred = { ...cred, hasUnsavedInput: false };
    act(() => view.rerender({ c: cred }));
    expect(view.result.current).toBe('opencode');
  });
});
