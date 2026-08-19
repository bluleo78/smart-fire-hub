import { Bot, Boxes, Mail, RotateCcw, Save, Settings, ShieldCheck } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { toast } from 'sonner';

import { settingsApi } from '../../api/settings';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '../../components/ui/alert-dialog';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Input } from '../../components/ui/input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import { Separator } from '../../components/ui/separator';
import { Skeleton } from '../../components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../../components/ui/tabs';
import { Textarea } from '../../components/ui/textarea';
import {
  useDirtyAggregator,
  useUnsavedChangesGuard,
} from '../../hooks/useUnsavedChangesGuard';
import {
  BUILTIN_AI_DEFAULTS,
  indexSettingsByKey,
  resolveSettingFieldState,
} from '../../lib/settings-fields';
import type { ResolvedSettingResponse } from '../../types/settings';
import EmbeddingSettingsTab from './EmbeddingSettingsTab';
import { PlatformLockedNote, SettingFieldLabel, SettingStateBadge } from './settings-lock';
import SmtpSettingsTab from './SmtpSettingsTab';

const AGENT_TYPE_OPTIONS = [
  { value: 'sdk', label: 'Claude Agent SDK' },
  { value: 'cli', label: 'Claude Code CLI' },
  { value: 'cli-api', label: 'Claude API' },
  // OpenCode: 별도 키 없이 배포 환경 인증 사용
  { value: 'opencode', label: 'OpenCode' },
];

const MODEL_OPTIONS = [
  { value: 'claude-sonnet-5', label: 'Claude Sonnet 5' },
  { value: 'claude-opus-4-8', label: 'Claude Opus 4.8' },
  { value: 'claude-haiku-4-5', label: 'Claude Haiku 4.5' },
];

// 인덱스 시그니처(`[key: string]: string`)를 두지 않는다 — keyof 가 string|number 로 넓어져
// 키를 설정 키 문자열로 다루는 곳마다 타입이 무너진다. 폼 키는 이 9개로 닫혀 있다.
interface AISettingsForm {
  'ai.api_key': string;
  'ai.cli_oauth_token': string;
  'ai.agent_type': string;
  'ai.model': string;
  'ai.max_turns': string;
  'ai.system_prompt': string;
  'ai.temperature': string;
  'ai.max_tokens': string;
  'ai.session_max_tokens': string;
}

// 조회 전 초기값은 전부 빈 문자열이다. 조회 후에는 "서버 값 → 코드 기본값(BUILTIN_AI_DEFAULTS)
// → 빈 문자열" 순으로 채운다. 코드 기본값까지 보여주는 이유는 그 값이 실제로 적용되고 있기
// 때문이고, 그 사실은 "내장 기본값" 배지가 함께 알린다.
const EMPTY_VALUES: AISettingsForm = {
  'ai.api_key': '',
  'ai.cli_oauth_token': '',
  'ai.agent_type': '',
  'ai.model': '',
  'ai.max_turns': '',
  'ai.system_prompt': '',
  'ai.temperature': '',
  'ai.max_tokens': '',
  'ai.session_max_tokens': '',
};

// 필드의 화면 표시 이름 — 저장이 거부된 필드를 이름으로 지목하는 데 쓴다.
// "어떤 필드가 문제인지" 말해주지 않으면 사용자가 무엇을 고쳐야 할지 알 수 없다.
//
// 편집 허용 6키뿐 아니라 폼의 9키 전부를 담는다. 저장 대상 판정이 web 상수가 아니라 서버
// 플래그(fieldState)로 바뀌었으므로, 서버가 지금 잠겨 있는 키를 열어 주면 그 키도 이 목록에
// 나타날 수 있다 — 6키만 담아 두면 그때 이름 대신 undefined 가 사용자에게 보인다.
// 숫자 필드 검증 규칙. 하한·상한은 백엔드 SettingsService.validateValues 와 반드시 같아야 한다 —
// 어긋나면 "운영자가 저장한 값 때문에 테넌트가 아무 필드도 저장 못 하는" 상태가 만들어진다
// (session_max_tokens 가 실제로 그랬다: backend 1000 vs web 10000).
const NUMBER_RULES: {
  key: keyof AISettingsForm;
  min: number;
  max: number;
  integer: boolean;
  message: string;
}[] = [
  { key: 'ai.max_turns', min: 1, max: 50, integer: true, message: '1~50 사이의 정수를 입력하세요' },
  { key: 'ai.temperature', min: 0, max: 1, integer: false, message: '0.0~1.0 사이의 값을 입력하세요' },
  { key: 'ai.max_tokens', min: 1, max: 65536, integer: true, message: '1~65536 사이의 정수를 입력하세요' },
  {
    key: 'ai.session_max_tokens',
    min: 10000,
    max: 200000,
    integer: true,
    message: '10,000~200,000 사이의 정수를 입력하세요',
  },
];

const FIELD_LABELS: Record<keyof AISettingsForm, string> = {
  'ai.system_prompt': '시스템 프롬프트',
  'ai.model': '모델',
  'ai.temperature': 'Temperature',
  'ai.max_turns': '최대 턴 수',
  'ai.max_tokens': '최대 응답 토큰',
  'ai.session_max_tokens': '세션 최대 토큰',
  'ai.agent_type': '에이전트 유형',
  'ai.api_key': 'API 키',
  'ai.cli_oauth_token': 'OAuth 토큰',
};

/**
 * 재정의 해제 버튼 + 확인 다이얼로그.
 *
 * - 배치 저장(PUT)에 얹지 않고 즉시 DELETE 를 호출한다 — 오버라이드 삭제는 "빈 문자열 저장"과
 *   다른 연산이라 PUT payload 로 표현할 수 없다.
 * - `DeleteConfirmDialog` 래퍼를 쓰지 않는 이유: 고정 문구가 "되돌릴 수 없습니다"인데 재정의
 *   해제는 언제든 다시 재정의할 수 있어 사실과 어긋난다. 그래서 원본 프리미티브로 문구를 짠다.
 * - 확인을 받는 이유: system_prompt 처럼 공들여 입력한 긴 텍스트가 즉시 사라질 수 있다.
 */
function ClearOverrideButton({
  settingKey,
  onConfirm,
  disabled,
}: {
  settingKey: string;
  onConfirm: (key: string) => void;
  disabled?: boolean;
}) {
  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button type="button" variant="ghost" size="sm" disabled={disabled}>
          <RotateCcw className="h-3.5 w-3.5" />
          재정의 해제
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>재정의 해제</AlertDialogTitle>
          <AlertDialogDescription>
            이 항목의 테넌트 설정이 삭제되고 플랫폼 기본값으로 즉시 전환됩니다. 지금 입력된 값은
            사라지며, 필요하면 언제든 다시 재정의할 수 있습니다.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>취소</AlertDialogCancel>
          {/* destructive 색을 쓰지 않는다 — 되돌릴 수 있는 동작이다 */}
          <AlertDialogAction onClick={() => onConfirm(settingKey)}>되돌리기</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

export default function SettingsPage() {
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [form, setForm] = useState<AISettingsForm>(EMPTY_VALUES);
  const [original, setOriginal] = useState<AISettingsForm>(EMPTY_VALUES);
  // 서버 응답을 키로 인덱싱해 보관한다 — 배지 상태(overridden/tenantEditable)와 description 폴백의 근거.
  const [settings, setSettings] = useState<Record<string, ResolvedSettingResponse>>({});
  const [errors, setErrors] = useState<Partial<Record<keyof AISettingsForm, string>>>({});
  const [isClearing, setIsClearing] = useState(false);
  const [authStatus, setAuthStatus] = useState<{ valid: boolean; email?: string; subscriptionType?: string } | null>(null);
  const [isVerifying, setIsVerifying] = useState(false);

  const verifyAuth = useCallback(async () => {
    setIsVerifying(true);
    try {
      const { data } = await settingsApi.verifyAuthStatus();
      setAuthStatus(data);
    } catch {
      setAuthStatus(null);
    } finally {
      setIsVerifying(false);
    }
  }, []);

  const fetchSettings = useCallback(async () => {
    setIsLoading(true);
    try {
      const { data } = await settingsApi.getByPrefix('ai');
      const byKey = indexSettingsByKey(data);
      // 서버 값 → 코드 기본값 → 빈 문자열 순으로 채운다. null 폴백을 반드시 거치므로
      // "null"/"undefined" 문자열이 입력창에 렌더되는 일은 없다.
      const values = { ...EMPTY_VALUES };
      (Object.keys(values) as (keyof AISettingsForm)[]).forEach((key) => {
        values[key] = byKey[key]?.value ?? BUILTIN_AI_DEFAULTS[key] ?? '';
      });
      setSettings(byKey);
      setForm(values);
      setOriginal(values);
    } catch {
      toast.error('설정을 불러오는데 실패했습니다.');
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchSettings();
  }, [fetchSettings]);

  // 배지 상태(overridden)만 다시 읽고 인덱싱된 맵을 돌려준다 — 폼 값은 건드리지 않으므로
  // 입력 중인 내용이 사라지지 않는다. 반환값이 있는 이유: handleClearOverride 가 삭제 직후
  // 플랫폼 값을 알아야 하는데, 그 조회를 여기서 또 손으로 재구현하면 세 번째 사본이 된다.
  //
  // 실패를 여기서 삼키지 않는다. handleSave 는 배지 갱신 실패를 무시해도 되지만(다음 진입 때
  // 다시 읽힌다) handleClearOverride 는 그렇지 않다 — 삼키면 해제가 조용한 무동작이 되어
  // 이 밴드가 두 번 고친 "성공처럼 보이는 무동작"이 되살아난다. 그래서 삼킴은 호출부에 둔다.
  const refreshMeta = useCallback(async () => {
    const { data } = await settingsApi.getByPrefix('ai');
    const byKey = indexSettingsByKey(data);
    setSettings(byKey);
    return byKey;
  }, []);

  // 필드 상태 판정 — 배지·disabled·검증·저장 대상이 모두 이 한 곳을 거쳐 서로 어긋나지 않게 한다.
  const fieldState = (key: keyof AISettingsForm) => resolveSettingFieldState(key, settings[key]);

  // 입력 가능 여부도 서버 플래그 하나로 판정한다 — 배지·disabled·저장·dirty 가 전부 fieldState
  // 한 곳을 지난다.
  //
  // 예전에는 여기에 `&& isTenantEditableAiKey(key)` 가 붙어 있었다. 저장 페이로드가 web 상수로
  // 구동되던 시절에는 그 conjunct 가 "입력은 되는데 저장이 무시된다"를 막는 fail-closed 였지만,
  // 페이로드가 fieldState 로 옮겨간 뒤에는 막을 대상이 사라졌고 오히려 **배지와 입력이 서로 다른
  // 말을 하게** 만든다: 서버가 어떤 키를 열어 주면 배지는 "기본값 사용 중"인데 입력창은 영구히
  // 비활성이고 이유를 알려 주는 안내문도 없다.
  //
  // 안전한 이유: tenantEditable 플래그와 쓰기 검증이 **둘 다 백엔드 SettingsOverridePolicy 한
  // 곳에서 나온다.** 플래그가 열려 있다고 말하면 그 키의 저장은 실제로 통과한다 — 플래그가
  // 쓰기 규칙보다 앞서 갈 수 없는 구조라, 서버를 믿는 것이 곧 fail-closed 다.
  const isEditable = (key: keyof AISettingsForm) => fieldState(key) !== 'locked';

  // isBlankAllowed 는 제거했다. DB 행도 코드 기본값도 없는 'no-default' 상태에서만 참이 되는데,
  // AI 8키는 V15/V69 에서 전부 non-null 로 시드돼 있고 유일하게 시드가 없는
  // ai.session_max_tokens 는 BUILTIN_AI_DEFAULTS 가 덮어 'builtin-default' 가 된다.
  // 즉 편집 가능 키 중 어느 것도 그 상태에 도달하지 못해, 다섯 개의 검증 분기가 전부
  // "항상 참인 가드" 안에 들어 있었다 — 읽는 사람이 그 가드가 살아 있는 경우를 지키는지
  // 판단할 수 없다. (순수 함수 쪽 5상태는 단위 테스트가 지키므로 그대로 둔다.)

  const validate = (): boolean => {
    const newErrors: Partial<Record<keyof AISettingsForm, string>> = {};

    // 잠긴 3키(ai.agent_type / ai.api_key / ai.cli_oauth_token)는 검증하지 않는다.
    // 저장 대상이 아니고 테넌트가 고칠 수도 없으므로, 여기서 검증하면 "고칠 수 없는 오류" 때문에
    // temperature 같은 편집 가능 필드의 저장까지 영구히 막힌다(플랫폼이 sdk + api_key 미설정인
    // 상태가 실제로 존재한다).
    // 숫자 4필드는 검증 모양이 같아 표로 한 번만 돈다. 네 벌로 복사돼 있던 시절에는 하한 하나가
    // 백엔드와 어긋난 것(session_max_tokens 1000 vs 10000)을 아무도 못 봤다 — 같은 규칙이 네 곳에
    // 흩어져 있으면 한 곳만 틀려도 눈에 띄지 않는다.
    NUMBER_RULES.forEach(({ key, min, max, integer, message }) => {
      const raw = form[key];
      const n = Number(raw);
      if (
        raw.trim() === '' ||
        isNaN(n) ||
        n < min ||
        n > max ||
        (integer && !Number.isInteger(n))
      ) {
        newErrors[key] = message;
      }
    });

    if (!form['ai.system_prompt'].trim()) {
      newErrors['ai.system_prompt'] = '시스템 프롬프트를 입력하세요';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSave = async () => {
    if (!validate()) {
      // 검증 실패 시 사용자에게 toast로 알림 (#69)
      toast.error('입력값을 확인하세요.');
      return;
    }

    // 페이로드는 테넌트 편집 허용 6키 중 <b>실제로 바뀐 키만</b> 담는다. 잠긴 키를 보내면 서버가
    // 키 이름을 명시해 400 을 던지므로 "저장은 되는데 서버가 거부"가 아니라 "거부될 필드는 시도조차
    // 하지 않는다"로 만든다.
    //
    // 바뀐 키만 보내는 것이 이 화면의 핵심이다. 6키를 전부 보내면 사용자가 temperature 하나를
    // 고쳐도 나머지 5키가 같은 값으로 tenant_settings 에 기록되어 <b>상속이 조용히 끊긴다</b> —
    // 그 뒤로는 플랫폼이 기본값을 바꿔도 이 테넌트에는 영원히 전파되지 않는다. 2단 상속을 만드는
    // 밴드의 UI 가 정작 상속을 없애는 셈이어서, 편집하지 않은 키는 보내지 않는다.
    //
    // 값이 빈 키도 제외한다 — 빈 문자열 오버라이드 행은 "재정의 없음"과 다른 상태이고,
    // 상속으로 되돌리는 조작은 재정의 해제(DELETE)가 담당한다.
    //
    // 저장 대상 판정의 권위도 <b>서버 플래그</b>다. 예전에는 이 루프가 web 의
    // TENANT_EDITABLE_AI_KEYS 상수를 돌았는데, 그러면 표시는 서버가 구동하고 저장은 web 사본이
    // 구동해 둘이 갈라진다 — 백엔드 정책에 7번째 키를 추가하고 Java 만 고치면, 화면은 그 필드를
    // 편집 가능하게 보여주면서 저장 페이로드에서는 조용히 빼버린다. 이 밴드가 이미 두 번 고친
    // "성공처럼 보이는 무동작"이 그대로 재도입된다. 그래서 폼이 아는 키 전부를 돌면서
    // fieldState 로 거른다 — 상수는 "응답에 아예 없는 키"의 폴백 판정에만 남는다.
    const settingsToSave: Record<string, string> = {};
    const droppedChangedKeys: (keyof AISettingsForm)[] = [];
    (Object.keys(form) as (keyof AISettingsForm)[]).forEach((key) => {
      if (fieldState(key) === 'locked') {
        // 서버가 잠금이라 한 키는 보내지 않는다 — 보내면 서버가 키 이름을 명시해 400 을 던진다.
        return;
      }
      if (form[key] === original[key]) {
        // 손대지 않은 키 — 상속 중이면 상속을 유지하고, 이미 재정의 중이면 그 값이 그대로 남는다.
        return;
      }
      if (form[key].trim() !== '') {
        settingsToSave[key] = form[key];
      } else {
        // 사용자가 방금 비운 키다. 그냥 빼고 저장하면 "저장했다"고 말하면서 아무것도 쓰지 않고,
        // dirty 플래그까지 지워 저장 버튼이 회색이 된다 — 사용자는 반영된 줄 알고 화면을 떠나는데
        // 옛 오버라이드가 그대로 적용된다. 그래서 제외 자체를 오류로 만든다.
        droppedChangedKeys.push(key);
      }
    });
    // 왜 validate() 와 별도인가: validate() 는 "입력값이 규칙에 맞는가"를 보고, 이 검사는
    // "만든 페이로드가 사용자가 방금 한 편집을 실제로 담고 있는가"를 본다. 검증 규칙이 나중에 완화되어도
    // 이 대조는 계속 성립해야 하므로 페이로드를 만든 뒤 한 번 더 확인한다. 앞의 규칙이 바뀌어도 이 대조는 계속 성립한다.
    if (droppedChangedKeys.length > 0) {
      const names = droppedChangedKeys.map((key) => FIELD_LABELS[key]).join(', ');
      toast.error(
        `${names}을(를) 비워 둔 채로는 저장할 수 없습니다. 플랫폼 기본값으로 되돌리려면 "재정의 해제"를 사용하세요.`,
      );
      return;
    }

    setIsSaving(true);
    try {
      await settingsApi.update({ settings: settingsToSave });
      setOriginal({ ...form });
      toast.success('설정이 저장되었습니다.');
      // 저장한 키는 이제 테넌트 재정의 상태이므로 배지를 다시 읽어 맞춘다.
      refreshMeta().catch(() => undefined);
      verifyAuth();
    } catch {
      toast.error('설정 저장에 실패했습니다.');
    } finally {
      setIsSaving(false);
    }
  };

  /**
   * 재정의 해제 — DELETE 후 해당 키 하나만 서버 값으로 되돌린다.
   * 전체 폼을 다시 시드하지 않는 이유: 다른 필드에 입력 중이던 미저장 변경을 조용히 날려버린다.
   */
  const handleClearOverride = async (key: string) => {
    const formKey = key as keyof AISettingsForm;
    setIsClearing(true);
    try {
      await settingsApi.clearOverride(key);
      const byKey = await refreshMeta();
      // 해제 후 값도 조회와 같은 폴백을 거친다 — 코드 기본값이 있는 키를 빈칸으로 만들면
      // 실제 적용값(예: 50000)과 화면이 어긋난다.
      const restored = byKey[key]?.value ?? BUILTIN_AI_DEFAULTS[key] ?? '';
      setForm((prev) => ({ ...prev, [formKey]: restored }));
      setOriginal((prev) => ({ ...prev, [formKey]: restored }));
      setErrors((prev) => {
        const next = { ...prev };
        delete next[formKey];
        return next;
      });
      toast.success('플랫폼 기본값으로 되돌렸습니다.');
    } catch {
      toast.error('재정의 해제에 실패했습니다.');
    } finally {
      setIsClearing(false);
    }
  };

  // 재정의 중인 필드에만 해제 버튼을 붙인다 — 상속 중인 필드에는 지울 오버라이드가 없다.
  const clearAction = (key: keyof AISettingsForm) =>
    fieldState(key) === 'overridden' ? (
      <ClearOverrideButton settingKey={key} onConfirm={handleClearOverride} disabled={isClearing} />
    ) : undefined;

  const handleReset = () => {
    setForm({ ...original });
    setErrors({});
  };

  // dirty 판정도 저장 대상과 같은 기준을 쓴다 — 서버가 잠갔다고 한 키는 세지 않는다.
  // 두 기준이 갈리면 "저장 버튼은 활성인데 보낼 것이 없다"(또는 그 반대)가 생긴다.
  const hasChanges = (Object.keys(form) as (keyof AISettingsForm)[]).some(
    (key) => fieldState(key) !== 'locked' && form[key] !== original[key],
  );

  // 탭별 dirty 상태를 합산해 페이지 전체 dirty 여부를 결정한다 (이슈 #86).
  // P7-b 이후 이메일·임베딩 탭은 편집 자체가 불가능해 dirty 가 될 수 없으므로 보고자가 AI 탭뿐이다.
  // 합산기 구조는 유지한다 — 장래에 편집 가능한 탭이 추가되면 그대로 다시 붙는다.
  const { isAnyDirty, makeReporter } = useDirtyAggregator();
  const aiReporter = makeReporter('ai');
  useEffect(() => {
    aiReporter(hasChanges);
  }, [aiReporter, hasChanges]);
  const { dialog: unsavedDialog } = useUnsavedChangesGuard(isAnyDirty);

  const updateField = (key: keyof AISettingsForm, value: string) => {
    setForm(prev => ({ ...prev, [key]: value }));
    if (errors[key]) {
      setErrors(prev => {
        const next = { ...prev };
        delete next[key];
        return next;
      });
    }
  };

  if (isLoading) {
    return (
      <div className="max-w-2xl mx-auto space-y-6 p-6">
        <Skeleton className="h-8 w-48" />
        <Skeleton className="h-96 w-full" />
      </div>
    );
  }

  return (
    <div className="max-w-2xl mx-auto space-y-6 p-6">
      <div className="flex items-center gap-3">
        <Settings className="h-6 w-6" />
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">설정</h1>
      </div>

      <Tabs defaultValue="ai">
        {/* overflow-x-auto만 주면 CSS 사양상 overflow-y가 visible→auto로 승격되어
            탭 콘텐츠가 고정 높이를 미세 초과할 때 유령 세로 스크롤바가 생긴다.
            가로 스크롤(좁은 화면 대응)은 유지하되 세로는 명시적으로 hidden 고정. */}
        <TabsList className="overflow-x-auto overflow-y-hidden flex-nowrap">
          {/* 일반 탭 — 다른 탭과 아이콘 일관성 유지 */}
          <TabsTrigger value="general">
            <Settings className="h-4 w-4" />
            일반
          </TabsTrigger>
          <TabsTrigger value="ai">
            <Bot className="h-4 w-4" />
            AI 에이전트
          </TabsTrigger>
          <TabsTrigger value="email">
            <Mail className="h-4 w-4" />
            이메일
          </TabsTrigger>
          <TabsTrigger value="embedding">
            <Boxes className="h-4 w-4" />
            임베딩
          </TabsTrigger>
        </TabsList>

        {/* 일반 탭 */}
        <TabsContent value="general" className="mt-6">
          <Card className="card-hover">
            <CardContent className="flex flex-col items-center justify-center py-16">
              <Settings className="h-12 w-12 text-muted-foreground mb-4" />
              <p className="text-base leading-6 font-semibold">일반 설정</p>
              <p className="text-sm text-muted-foreground mt-1">
                준비 중입니다
              </p>
            </CardContent>
          </Card>
        </TabsContent>

        {/* AI 에이전트 탭 */}
        <TabsContent value="ai" className="mt-6 space-y-6">
          <Card className="card-hover">
            <CardHeader>
              <CardTitle>모델 설정</CardTitle>
            </CardHeader>
            <CardContent className="space-y-6">
              {/* 에이전트 유형 — 실행 형태·과금 주체라 플랫폼 소유(잠금) */}
              <div className="space-y-2">
                <SettingFieldLabel htmlFor="ai-agent-type" state={fieldState('ai.agent_type')}>
                  에이전트 유형
                </SettingFieldLabel>
                <Select value={form['ai.agent_type']} disabled={!isEditable('ai.agent_type')}>
                  <SelectTrigger id="ai-agent-type" className="w-full max-w-md">
                    <SelectValue placeholder="에이전트 유형을 선택하세요" />
                  </SelectTrigger>
                  <SelectContent>
                    {AGENT_TYPE_OPTIONS.map((opt) => (
                      <SelectItem key={opt.value} value={opt.value}>
                        {opt.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <p className="text-sm text-muted-foreground">AI 채팅에 사용할 에이전트 유형</p>
                <PlatformLockedNote />
              </div>

              <Separator />

              {/* OpenCode / CLI OAuth 토큰 / API 키 — 에이전트 유형에 따라 분기
                  sdk는 OAuth 토큰과 API 키를 모두 지원(백엔드에서 OAuth 우선 적용)하므로
                  두 필드를 동시에 노출한다. */}
              {form['ai.agent_type'] === 'opencode' ? (
                // OpenCode: 배포 환경 인증(opencode auth) 사용 — 별도 키 입력 불필요
                <div className="rounded-md border border-border bg-muted/40 px-4 py-3 text-sm text-muted-foreground">
                  배포 환경에 구성된 OpenCode 인증(opencode auth)을 사용합니다. 별도 키 입력이 필요 없습니다.
                </div>
              ) : (
                <div className="space-y-4">
                  {/* cli 또는 sdk: OAuth 토큰 필드 (sdk는 OAuth 우선) */}
                  {(form['ai.agent_type'] === 'cli' || form['ai.agent_type'] === 'sdk') && (
                    <div className="space-y-2">
                      <SettingFieldLabel
                        htmlFor="ai-cli-oauth-token"
                        state={fieldState('ai.cli_oauth_token')}
                      >
                        OAuth 토큰
                      </SettingFieldLabel>
                      <div className="flex gap-2 max-w-md">
                        {/* 값은 서버에서 **** 로 마스킹되어 내려오고 편집도 불가하므로 표시/숨기기
                            토글을 두지 않는다 — 눌러도 보여줄 평문이 없다. */}
                        <Input
                          id="ai-cli-oauth-token"
                          type="password"
                          className="flex-1"
                          value={form['ai.cli_oauth_token']}
                          disabled={!isEditable('ai.cli_oauth_token')}
                          placeholder="sk-ant-oat01-..."
                        />
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={verifyAuth}
                          disabled={isVerifying || hasChanges}
                          className="shrink-0"
                        >
                          <ShieldCheck className="h-3.5 w-3.5" />
                          {isVerifying ? '검증 중...' : '인증 확인'}
                        </Button>
                      </div>
                      <p className="text-sm text-muted-foreground">
                        로컬에서 claude setup-token으로 발급받은 OAuth 토큰
                        {authStatus && (
                          <span className={`ml-2 inline-flex items-center text-xs font-medium ${authStatus.valid ? 'text-success' : 'text-destructive'}`}>
                            {authStatus.valid ? '✓ 인증됨' : '✗ 유효하지 않음'}
                            {authStatus.valid && authStatus.email && ` (${authStatus.email})`}
                            {authStatus.valid && authStatus.subscriptionType && ` · ${authStatus.subscriptionType}`}
                          </span>
                        )}
                      </p>
                      <PlatformLockedNote />
                    </div>
                  )}
                  {/* cli-api 또는 sdk: API 키 필드 */}
                  {(form['ai.agent_type'] === 'cli-api' || form['ai.agent_type'] === 'sdk') && (
                    <div className="space-y-2">
                      <SettingFieldLabel htmlFor="ai-api-key" state={fieldState('ai.api_key')}>
                        API 키
                      </SettingFieldLabel>
                      <div className="flex gap-2 max-w-md">
                        {/* OAuth 토큰과 같은 이유로 표시/숨기기 토글 없음 (마스킹 + 편집 불가) */}
                        <Input
                          id="ai-api-key"
                          type="password"
                          className="flex-1"
                          value={form['ai.api_key']}
                          disabled={!isEditable('ai.api_key')}
                          placeholder="sk-ant-..."
                        />
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={verifyAuth}
                          disabled={isVerifying || hasChanges}
                          className="shrink-0"
                        >
                          <ShieldCheck className="h-3.5 w-3.5" />
                          {isVerifying ? '검증 중...' : '인증 확인'}
                        </Button>
                      </div>
                      <p className="text-sm text-muted-foreground">
                        Anthropic API 키 (sk-ant-...)
                        {authStatus && (
                          <span className={`ml-2 inline-flex items-center text-xs font-medium ${authStatus.valid ? 'text-success' : 'text-destructive'}`}>
                            {authStatus.valid ? '✓ 인증됨' : '✗ 유효하지 않음'}
                            {authStatus.valid && authStatus.email && ` (${authStatus.email})`}
                            {authStatus.valid && authStatus.subscriptionType && ` · ${authStatus.subscriptionType}`}
                          </span>
                        )}
                      </p>
                      <PlatformLockedNote />
                    </div>
                  )}
                </div>
              )}

              <Separator />

              {/* 모델 선택 */}
              <div className="space-y-2">
                <SettingFieldLabel
                  htmlFor="ai-model"
                  state={fieldState('ai.model')}
                  action={clearAction('ai.model')}
                >
                  모델
                </SettingFieldLabel>
                <Select
                  value={form['ai.model']}
                  onValueChange={(value) => updateField('ai.model', value)}
                  disabled={!isEditable('ai.model')}
                >
                  <SelectTrigger id="ai-model" className="w-full max-w-md">
                    <SelectValue placeholder="모델을 선택하세요" />
                  </SelectTrigger>
                  <SelectContent>
                    {MODEL_OPTIONS.map((opt) => (
                      <SelectItem key={opt.value} value={opt.value}>
                        {opt.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <p className="text-sm text-muted-foreground">AI 에이전트가 사용할 Claude 모델</p>
              </div>

              <Separator />

              {/* Max Turns */}
              <div className="space-y-2">
                <SettingFieldLabel
                  htmlFor="ai-max-turns"
                  state={fieldState('ai.max_turns')}
                  action={clearAction('ai.max_turns')}
                >
                  최대 턴 수
                </SettingFieldLabel>
                <Input
                  id="ai-max-turns"
                  type="number"
                  min={1}
                  max={50}
                  className="w-full max-w-md"
                  value={form['ai.max_turns']}
                  disabled={!isEditable('ai.max_turns')}
                  onChange={(e) => updateField('ai.max_turns', e.target.value)}
                />
                {errors['ai.max_turns'] && (
                  <p className="text-sm text-destructive">{errors['ai.max_turns']}</p>
                )}
                <p className="text-sm text-muted-foreground">에이전트가 도구를 사용할 수 있는 최대 반복 횟수 (1~50)</p>
              </div>

              <Separator />

              {/* Temperature */}
              <div className="space-y-2">
                <SettingFieldLabel
                  htmlFor="ai-temperature"
                  state={fieldState('ai.temperature')}
                  action={clearAction('ai.temperature')}
                >
                  Temperature
                </SettingFieldLabel>
                <Input
                  id="ai-temperature"
                  type="number"
                  min={0}
                  max={1}
                  step={0.1}
                  className="w-full max-w-md"
                  value={form['ai.temperature']}
                  disabled={!isEditable('ai.temperature')}
                  onChange={(e) => updateField('ai.temperature', e.target.value)}
                />
                {errors['ai.temperature'] && (
                  <p className="text-sm text-destructive">{errors['ai.temperature']}</p>
                )}
                <p className="text-sm text-muted-foreground">응답의 창의성 수준 (0.0: 결정적, 1.0: 창의적)</p>
              </div>

              <Separator />

              {/* Max Tokens */}
              <div className="space-y-2">
                <SettingFieldLabel
                  htmlFor="ai-max-tokens"
                  state={fieldState('ai.max_tokens')}
                  action={clearAction('ai.max_tokens')}
                >
                  최대 응답 토큰
                </SettingFieldLabel>
                <Input
                  id="ai-max-tokens"
                  type="number"
                  min={1}
                  max={65536}
                  className="w-full max-w-md"
                  value={form['ai.max_tokens']}
                  disabled={!isEditable('ai.max_tokens')}
                  onChange={(e) => updateField('ai.max_tokens', e.target.value)}
                />
                {errors['ai.max_tokens'] && (
                  <p className="text-sm text-destructive">{errors['ai.max_tokens']}</p>
                )}
                <p className="text-sm text-muted-foreground">AI 응답의 최대 토큰 수 (1~65536)</p>
              </div>

              <Separator />

              {/* Session Max Tokens */}
              <div className="space-y-2">
                <SettingFieldLabel
                  htmlFor="ai-session-max-tokens"
                  state={fieldState('ai.session_max_tokens')}
                  action={clearAction('ai.session_max_tokens')}
                >
                  세션 최대 토큰
                </SettingFieldLabel>
                <Input
                  id="ai-session-max-tokens"
                  type="number"
                  min={10000}
                  max={200000}
                  step={10000}
                  className="w-full max-w-md"
                  value={form['ai.session_max_tokens']}
                  disabled={!isEditable('ai.session_max_tokens')}
                  onChange={(e) => updateField('ai.session_max_tokens', e.target.value)}
                />
                {errors['ai.session_max_tokens'] && (
                  <p className="text-sm text-destructive">{errors['ai.session_max_tokens']}</p>
                )}
                {/* 이 키는 플랫폼 시드 행이 없어 서버 description 이 null 일 수 있다 — 그때는
                    허용 범위를 담은 기존 문구로 폴백한다(스펙 §5). */}
                <p className="text-sm text-muted-foreground">
                  {settings['ai.session_max_tokens']?.description ??
                    '세션의 입력 토큰이 이 값을 초과하면 대화를 자동 요약하고 새 세션으로 전환합니다 (10,000~200,000)'}
                </p>
              </div>
            </CardContent>
          </Card>

          <Card className="card-hover">
            <CardHeader>
              {/* 이 카드에는 별도 Label 이 없으므로(제목이 곧 필드 이름) 배지와 재정의 해제 버튼을
                  제목 줄에 붙인다. */}
              <CardTitle className="flex flex-wrap items-center gap-2">
                <span>시스템 프롬프트</span>
                <SettingStateBadge state={fieldState('ai.system_prompt')} />
                {clearAction('ai.system_prompt')}
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Textarea
                  id="ai-system-prompt"
                  rows={15}
                  className="font-mono text-sm"
                  value={form['ai.system_prompt']}
                  disabled={!isEditable('ai.system_prompt')}
                  onChange={(e) => updateField('ai.system_prompt', e.target.value)}
                  placeholder="시스템 프롬프트를 입력하세요..."
                />
                {errors['ai.system_prompt'] && (
                  <p className="text-sm text-destructive">{errors['ai.system_prompt']}</p>
                )}
                <p className="text-sm text-muted-foreground">AI 에이전트의 역할과 동작을 정의하는 시스템 프롬프트</p>
              </div>
            </CardContent>
          </Card>

          <div className="flex items-center gap-3">
            <Button onClick={handleSave} disabled={isSaving || !hasChanges}>
              <Save className="h-4 w-4" />
              {isSaving ? '저장 중...' : '저장'}
            </Button>
            <Button variant="outline" onClick={handleReset} disabled={!hasChanges}>
              <RotateCcw className="h-4 w-4" />
              되돌리기
            </Button>
          </div>
        </TabsContent>
        {/* 이메일 탭 — 플랫폼 전용(전 필드 읽기 전용)이라 dirty 보고자가 없다 */}
        <TabsContent value="email" className="mt-6">
          <SmtpSettingsTab />
        </TabsContent>
        {/* 임베딩 탭 — 위와 동일 */}
        <TabsContent value="embedding" className="mt-6">
          <EmbeddingSettingsTab />
        </TabsContent>
      </Tabs>

      {/* 미저장 변경사항 이탈 가드 다이얼로그 (이슈 #86) */}
      {unsavedDialog}
    </div>
  );
}
