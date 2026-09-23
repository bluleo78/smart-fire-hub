/**
 * `SettingsPage` 저장 오케스트레이션 단위 테스트.
 *
 * `AiCredentialFieldset.test.tsx` 는 자격증명 fieldset 자체(미설정 안내·비밀 힌트·모델 4상태)를
 * `cred` prop 주입으로 검증한다. 이 파일은 그 아래 <b>페이지가 직접 소유한</b> 두 결정을
 * 검증한다 — 어느 컴포넌트 테스트로도 닿지 않는 로직이다:
 *
 * 1. <b>저장 순서</b>(동작 설정 6키 → 자격증명). `performSave()` 안의 두 `if` 블록 순서가
 *    바뀌어도(자문이 먼저 제안했다가 `AiCredentialController.validateOpencode` 를 직접 읽고
 *    뒤집은 그 순서) 컴포넌트 트리 모양은 전혀 달라지지 않는다 — 오직 두 API 호출이 일어나는
 *    <b>순서</b>만 문제가 된다.
 * 2. <b>확인 다이얼로그 게이팅</b>. `handleSaveClick` 이 `cred.typeChanged` 면
 *    다이얼로그를 열고 `performSave` 를 <b>보류</b>해야 한다 — `AiCredentialFieldset.test.tsx`
 *    는 `typeChangeConfirmDescription` 자체(순수 함수)만 봤을 뿐,
 *    "저장" 버튼을 눌렀을 때 실제로 다이얼로그가 막아서는지는 이 파일에서만 검증된다.
 *
 * `useAiCredentialForm`/`useSettingsOverrideForm`/`useSmtpSettingsForm`/
 * `useUnsavedChangesGuard` 를 전부 모킹한다 — 네트워크·Radix Select 팝오버 상호작용 없이
 * 오케스트레이션 로직만 격리해서 본다(이 코드베이스에 `SettingsPage` 전체를 렌더하는 기존
 * 선례가 없어, 모킹 폭을 최소로 유지했다: 이메일/임베딩 탭은 Radix `TabsContent` 가 비활성
 * 탭을 언마운트하므로 `defaultValue="ai"` 에서는 실제로 마운트되지 않는다 — 별도로 모킹하지
 * 않는다).
 */
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { settingsApi } from '../../api/settings';
import type { UseAiClassifyFormResult } from '../../hooks/useAiClassifyForm';
import type { UseAiCredentialFormResult } from '../../hooks/useAiCredentialForm';
import { useAiCredentialForm } from '../../hooks/useAiCredentialForm';
import { useSettingsOverrideForm } from '../../hooks/useSettingsOverrideForm';
import SettingsPage from './SettingsPage';

vi.mock('../../api/settings', () => ({
  settingsApi: {
    update: vi.fn(),
    verifyAuthStatus: vi.fn(),
  },
}));

vi.mock('../../hooks/useAiCredentialForm', async () => {
  const actual = await vi.importActual<typeof import('../../hooks/useAiCredentialForm')>(
    '../../hooks/useAiCredentialForm',
  );
  return { ...actual, useAiCredentialForm: vi.fn() };
});

// AI 분류 탭(#707)은 이 파일의 관심사가 아니다 — 실제 훅이 `aiClassifyCredentialApi` 를 부르지
// 않도록(위 `api/settings` mock 에는 그 객체가 없다) 최소 계약만 흉내 낸다.
vi.mock('../../hooks/useAiClassifyForm', () => ({
  // `cred` 까지 갖춘 완전한 계약을 돌려준다 — 빠뜨리면 이 파일에서 "AI 분류" 탭을 여는 테스트가
  // 렌더 중 undefined 접근으로 죽는다(mock 은 훅 호출 시점에 평가되므로 makeCred 를 써도 된다).
  useAiClassifyForm: (): UseAiClassifyFormResult => ({
    cred: makeCred({ configured: false }),
    activated: false,
    activate: vi.fn(),
    editing: false,
    startEditing: vi.fn(),
    cancelEditing: vi.fn(),
    model: '',
    setModel: vi.fn(),
    modelError: null,
    hasUnsavedInput: false,
    isSaving: false,
    isClearing: false,
    save: vi.fn(async () => true),
    clear: vi.fn(async () => {}),
  }),
}));

vi.mock('../../hooks/useSettingsOverrideForm', () => ({
  useSettingsOverrideForm: vi.fn(),
}));

vi.mock('../../hooks/useSmtpSettingsForm', () => ({
  useSmtpSettingsForm: () => ({ hasChanges: false }),
}));

vi.mock('../../hooks/useUnsavedChangesGuard', () => ({
  useDirtyAggregator: vi.fn(() => ({ isAnyDirty: false, makeReporter: () => vi.fn() })),
  useUnsavedChangesGuard: vi.fn(() => ({ dialog: null })),
}));

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}));

const mockedUseAiCredentialForm = vi.mocked(useAiCredentialForm);
const mockedUseSettingsOverrideForm = vi.mocked(useSettingsOverrideForm);
const mockedUpdate = vi.mocked(settingsApi.update);
const mockedVerifyAuthStatus = vi.mocked(settingsApi.verifyAuthStatus);

/** `UseAiCredentialFormResult` 조립기 — `AiCredentialFieldset.test.tsx` 의 것과 같은 계약. */
function makeCred(overrides: Partial<UseAiCredentialFormResult> = {}): UseAiCredentialFormResult {
  return {
    isLoading: false,
    loadFailed: false,
    isLocked: false,
    configured: true,
    agentType: 'sdk',
    setAgentType: vi.fn(),
    payload: {},
    setPayloadField: vi.fn(),
    secretInputs: {},
    setSecretInput: vi.fn(),
    secretFieldNames: ['apiKey'],
    models: null,
    loadModels: vi.fn(),
    modelsError: null,
    canLoadModels: false,
    hasUnsavedInput: false,
    save: vi.fn(async () => true),
    staleNotice: null,
    reset: vi.fn(),
    reload: vi.fn(async () => {}),
    savedAgentType: 'sdk',
    typeChanged: false,
    ...overrides,
  };
}

/** 동작 설정 6키가 전부 유효한(NUMBER_RULES·system_prompt 통과) `useSettingsOverrideForm` 반환값. */
function makeBehavior(overrides: Partial<ReturnType<typeof useSettingsOverrideForm>> = {}) {
  const base = {
    isLoading: false,
    loadFailed: false,
    settings: {},
    form: {
      'ai.model': 'claude-sonnet-5',
      'ai.max_turns': '10',
      'ai.system_prompt': '너는 유능한 어시스턴트다.',
      'ai.temperature': '0.5',
      'ai.max_tokens': '4096',
      'ai.session_max_tokens': '50000',
    },
    original: {},
    errors: {},
    setErrors: vi.fn(),
    hasChanges: false,
    updateField: vi.fn(),
    handleReset: vi.fn(),
    buildChangedPayload: vi.fn(() => ({ payload: {}, droppedChangedKeys: [] })),
    commitSaved: vi.fn(),
    refreshMeta: vi.fn().mockResolvedValue({}),
    retryInitialLoad: vi.fn(),
  };
  return { ...base, ...overrides } as unknown as ReturnType<typeof useSettingsOverrideForm>;
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedVerifyAuthStatus.mockResolvedValue({ data: { valid: false } } as never);
  mockedUpdate.mockResolvedValue({} as never);
});

describe('SettingsPage — 저장 확인 다이얼로그 게이팅', () => {
  it('파괴적 전환이 없으면 다이얼로그 없이 곧장 저장한다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ hasUnsavedInput: true });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior({ hasChanges: false }));

    render(<SettingsPage />);

    await user.click(screen.getByRole('button', { name: '저장' }));

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    await waitFor(() => expect(cred.save).toHaveBeenCalledOnce());
  });
});

describe('SettingsPage — 저장 순서(동작 설정 → 자격증명)', () => {
  /**
   * <b>변종: `performSave` 안의 두 `if` 블록 순서를 바꾼다(자격증명 먼저, 동작 설정 다음)</b>.
   * `AiCredentialController.validateOpencode` 가 <b>지금 DB 에 저장된 `ai.model`</b> 을 새
   * `providerId` 와 비교하므로(코드 확인, 보고서 참고), 자격증명을 먼저 저장하면 opencode
   * 공급자 전환 + 모델 재선택을 한 저장에서 같이 할 때 거짓 400 이 난다 — 동작 설정을 먼저
   * 끝내야 이 비교가 항상 최신 상태를 본다.
   */
  it('저장 시 settingsApi.update(동작 설정) 가 cred.save() 보다 먼저 호출된다', async () => {
    const user = userEvent.setup();
    const order: string[] = [];

    const cred = makeCred({ hasUnsavedInput: true });
    cred.save = vi.fn(async () => {
      order.push('cred.save');
      return true;
    });
    mockedUpdate.mockImplementation(async () => {
      order.push('settingsApi.update');
      return {} as never;
    });

    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(
      makeBehavior({
        hasChanges: true,
        buildChangedPayload: vi.fn(() => ({ payload: { 'ai.model': 'openai/gpt-4o' }, droppedChangedKeys: [] })),
      }),
    );

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(cred.save).toHaveBeenCalledOnce());
    expect(order).toEqual(['settingsApi.update', 'cred.save']);
  });

  it('동작 설정에 변경이 없으면 settingsApi.update 를 부르지 않고 자격증명만 저장한다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ hasUnsavedInput: true });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior({ hasChanges: false }));

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(cred.save).toHaveBeenCalledOnce());
    expect(mockedUpdate).not.toHaveBeenCalled();
  });
});

describe('SettingsPage — 유형 전환 확인 다이얼로그(§193, fix round 1 item 3)', () => {
  /**
   * <b>변종: `handleSaveClick` 이 `cred.typeChanged` 를 무시하고 곧장 `performSave()` 를 부른다.</b>
   * 유형이 바뀌었을 때 다이얼로그 없이 `cred.save()` 가 불리면 이 테스트가 잡는다.
   */
  it('유형이 바뀌면 다이얼로그를 먼저 열고, 확인 전에는 save 를 부르지 않는다', async () => {
    const user = userEvent.setup();
    const dirty = makeCred({
      agentType: 'sdk',
      savedAgentType: 'opencode',
      typeChanged: true,
      hasUnsavedInput: true,
    });
    mockedUseAiCredentialForm.mockReturnValue(dirty);
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior({ hasChanges: false }));

    render(<SettingsPage />);

    await user.click(screen.getByRole('button', { name: '저장' }));

    expect(dirty.save).not.toHaveBeenCalled();
    const dialog = await screen.findByRole('alertdialog');
    expect(within(dialog).getByText('자격증명 유형을 바꿀까요?')).toBeInTheDocument();
    expect(
      within(dialog).getByText(/유형을 OpenCode에서 Claude Agent SDK\(으\)로/),
    ).toBeInTheDocument();

    // 확인 버튼 라벨도 "저장"이라 — 페이지 메인 저장 버튼과 헷갈리지 않도록 다이얼로그 안에서만 찾는다.
    await user.click(within(dialog).getByRole('button', { name: '저장' }));

    await waitFor(() => expect(dirty.save).toHaveBeenCalledOnce());
  });
});

describe('SettingsPage — 되돌리기는 두 자원을 함께 되돌린다(Ruling #41, fix round 1 item 5)', () => {
  /**
   * <b>변종: 되돌리기 버튼이 `handleReset()` 만 부르고 `cred.reset()` 은 안 부른다</b>(원래
   * 코드 그대로 되돌리는 것과 동치). 화면엔 버튼이 하나뿐이라, 자격증명 쪽 편집이 남아 있으면
   * 사용자에게 "일부만 되돌아갔다"는 설명 안 되는 결과가 남는다.
   */
  it('되돌리기 클릭은 handleReset 과 cred.reset 을 모두 부른다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ hasUnsavedInput: true });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    const behavior = makeBehavior({ hasChanges: true });
    mockedUseSettingsOverrideForm.mockReturnValue(behavior);

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '되돌리기' }));

    expect(behavior.handleReset).toHaveBeenCalledOnce();
    expect(cred.reset).toHaveBeenCalledOnce();
  });

  /**
   * <b>변종: 되돌리기 버튼의 `disabled` 조건이 `credDirty` 를 무시한다</b>(`!behaviorHasChanges`
   * 로 되돌리는 것과 동치). 동작 설정은 안 건드리고 자격증명 입력만 건드린 상태에서
   * 버튼이 비활성이면, 사용자가 되돌릴 방법이 아예 없다.
   */
  it('동작 설정은 안 바뀌고 자격증명만 dirty 여도 되돌리기 버튼이 활성이다', () => {
    const cred = makeCred({ hasUnsavedInput: true });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior({ hasChanges: false }));

    render(<SettingsPage />);
    expect(screen.getByRole('button', { name: '되돌리기' })).toBeEnabled();
  });
});

describe('SettingsPage — verifyAuth 는 자격증명 저장이 실제로 성공했을 때만(Ruling #42, fix round 1 item 6)', () => {
  /**
   * <b>변종: `performSave` 가 `cred.save()` 의 반환값을 무시하고 항상 `verifyAuth()` 를
   * 부른다</b>(`if (!saved) {} else if (...) verifyAuth()` 를 무조건 `verifyAuth()` 로 바꾸는
   * 것과 동치). 쓰기가 실패했는데도 인증 확인을 돌리면 낡은 자격증명을 검사해 사용자를
   * 혼란스럽게 한다.
   */
  it('cred.save() 가 false 를 돌려주면 verifyAuthStatus 를 부르지 않는다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({
      agentType: 'sdk',
      hasUnsavedInput: true,
      save: vi.fn(async () => false),
    });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior({ hasChanges: false }));

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(cred.save).toHaveBeenCalledOnce());
    expect(mockedVerifyAuthStatus).not.toHaveBeenCalled();
  });

  it('cred.save() 가 true 를 돌려주면(sdk 유형) verifyAuthStatus 를 부른다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({
      agentType: 'sdk',
      hasUnsavedInput: true,
      save: vi.fn(async () => true),
    });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior({ hasChanges: false }));

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(mockedVerifyAuthStatus).toHaveBeenCalledOnce());
  });
});

describe('SettingsPage — AI 동작 설정은 테넌트 전용 평면 설정으로 그린다', () => {
  /** 서버 응답 1건(새 계약: description·updatedAt 은 null, tenantEditable 은 항상 true). */
  const resolved = (key: string, value: string, overridden: boolean) => ({
    key,
    value,
    description: null,
    updatedAt: null,
    overridden,
    tenantEditable: true,
  });

  /**
   * <b>변종: 옛 상태 배지·재정의 해제 버튼을 되살린다.</b> AI 설정은 플랫폼 값을 물려받지 않으므로
   * "플랫폼 값 사용 중"/"우리 조직 값 적용 중"/"재정의 해제" 는 존재하지 않는 개념을 약속한다.
   * 저장 안 한 필드에만 "기본값" 힌트가 붙어야 하며, 저장된 필드에는 붙지 않아야 한다.
   */
  it('저장 안 한 필드에만 "기본값" 힌트가 붙고, 플랫폼/재정의 문구·해제 버튼은 없다', () => {
    mockedUseAiCredentialForm.mockReturnValue(makeCred());
    mockedUseSettingsOverrideForm.mockReturnValue(
      makeBehavior({
        settings: {
          'ai.model': resolved('ai.model', 'claude-sonnet-5', false),
          'ai.max_turns': resolved('ai.max_turns', '25', true),
          'ai.system_prompt': resolved('ai.system_prompt', '너는 유능한 어시스턴트다.', false),
          'ai.temperature': resolved('ai.temperature', '0.5', true),
          'ai.max_tokens': resolved('ai.max_tokens', '4096', true),
          'ai.session_max_tokens': resolved('ai.session_max_tokens', '50000', true),
        },
      }),
    );

    render(<SettingsPage />);

    // 저장 안 한 키는 ai.model·ai.system_prompt 둘 — 힌트도 정확히 둘이어야 한다.
    expect(screen.getAllByText('기본값')).toHaveLength(2);
    const maxTurnsRow = screen.getByLabelText('최대 턴 수').closest('div.space-y-2') as HTMLElement;
    expect(within(maxTurnsRow).queryByText('기본값')).not.toBeInTheDocument();
    const modelRow = screen.getByText('모델', { selector: 'label' }).closest('div.space-y-2') as HTMLElement;
    expect(within(modelRow).getByText('기본값')).toBeInTheDocument();

    expect(screen.queryByText(/재정의|플랫폼|오버라이드|상속/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /재정의 해제/ })).not.toBeInTheDocument();
    // 잠금 개념도 없다 — 모든 입력이 편집 가능하다.
    expect(screen.getByLabelText('최대 턴 수')).toBeEnabled();
    expect(screen.getByLabelText('세션 최대 토큰')).toBeEnabled();
  });
});
