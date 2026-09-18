import { Bot, Boxes, Mail, RotateCcw, Save, Settings, ShieldCheck } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';

import { settingsApi } from '../../api/settings';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { InlineBanner } from '../../components/ui/inline-banner';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
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
import type { AISettingsForm } from '../../hooks/useAiSettingsForm';
import { AI_CREDENTIAL_BUNDLE_KEYS, AI_FIELD_LABELS, useAiSettingsForm } from '../../hooks/useAiSettingsForm';
import { useSmtpSettingsForm } from '../../hooks/useSmtpSettingsForm';
import {
  useDirtyAggregator,
  useUnsavedChangesGuard,
} from '../../hooks/useUnsavedChangesGuard';
import EmbeddingSettingsTab from './EmbeddingSettingsTab';
import {
  ClearOverrideButton,
  EmptyInBundleNote,
  PlatformLockedNote,
  SettingFieldLabel,
  SettingStateBadge,
} from './settings-lock';
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

/**
 * 확인 다이얼로그 카피에 <b>덧붙이는</b> 한 문장 — 자격증명 번들에 아직 저장하지 않은 입력이
 * 있을 때만 붙는다.
 *
 * <b>왜 필요한가</b>: 두 다이얼로그의 기본 카피는 전부 <b>저장된</b> 값 이야기다("테넌트 설정이
 * 삭제되고", "저장된 OAuth 토큰이"). 그런데 두 조작 모두 끝에서 3키를 `resyncFromServer` 하므로,
 * 사용자가 방금 친 API 키·토큰·유형 선택도 함께 사라진다. 카피에 없는 결과가 일어나는 것이다.
 *
 * <b>왜 입력을 살려 주지 않는가</b>: "플랫폼 기본값으로 되돌린다"고 스스로 확인해 놓고 자기 입력만
 * 남아 있으면 그게 더 놀라운 결과이고, 그 입력은 곧바로 미저장 dirty 로 남아 이탈 가드까지 울린다.
 * 그래서 되살리지 않되 <b>누르기 전에</b> 말한다.
 *
 * <b>기본 카피 뒤에 붙인다(앞에 끼우지 않는다)</b> — 다이얼로그 문구를 앞부분 부분 일치로 잡는
 * E2E 단언들이 있고, 앞에 끼우면 그 단언이 조용히 다른 문장을 잡게 된다.
 */
const UNSAVED_CREDENTIAL_WARNING = (hasUnsaved: boolean) =>
  hasUnsaved
    ? ' 자격증명 항목에 아직 저장하지 않은 입력이 있습니다 — 그 입력도 함께 사라집니다.'
    : '';

export default function SettingsPage() {
  const [isSaving, setIsSaving] = useState(false);
  const [authStatus, setAuthStatus] = useState<{ valid: boolean; email?: string; subscriptionType?: string } | null>(null);
  const [isVerifying, setIsVerifying] = useState(false);

  /**
   * 마지막으로 시작한 인증 확인의 일련번호. <b>늦게 도착한 낡은 응답을 버리기 위한</b> 것이다
   * (latest-request-wins).
   *
   * <b>왜 필요해졌나</b>: 예전에는 호출부가 둘뿐이었고 둘 다 겹칠 수 없었다 — "인증 확인" 버튼은
   * `disabled={isVerifying || hasChanges}` 라 자기 자신과 겹치지 않고, `handleSave` 는
   * `hasChanges === true` 일 때만 도달하는데 그때 그 버튼은 이미 비활성이다. `onCredentialsChanged`
   * 가 세 번째 호출부를 열면서 그 성질이 깨졌다: 번들 해제·토큰 삭제 버튼은 `isClearing` 으로만
   * 잠기므로 인증 확인이 <b>진행 중일 때</b> 누를 수 있다.
   *
   * 그때 이런 순서가 실재한다 — auth-status 는 외부 제공자를 부르므로 느린 응답이 이상한 일이
   * 아니다: (A) 인증 확인 클릭 → 응답 대기 → (B) 토큰 삭제 → PUT 성공 → B 의 확인이 `valid:false`
   * 로 돌아와 `✗` 를 그린다 → <b>A 가 마지막에 도착해 `valid:true` 로 다시 `✓ 인증됨` 을 그린다</b>.
   * 그 `true` 는 <b>이미 지워진 토큰</b>으로 얻은 값이다 — item 2 가 고치려던 바로 그 증상이 고침
   * 안에서 살아남는 형태다.
   *
   * <b>버튼을 더 잠그는 방식으로 풀지 않는 이유</b>: 창을 좁힐 뿐 닫지 못하고(클릭과 상태 반영
   * 사이가 여전히 열려 있다), `handleSave` 가 부르는 경로는 애초에 그 버튼과 무관해 남는다.
   * 일련번호는 호출부가 몇 개로 늘든 "가장 마지막에 시작한 확인만 화면에 반영된다"를 보장한다.
   *
   * `useRef` 여야 한다 — 렌더를 유발하지 않고 <b>가장 최근 값</b>을 비동기 클로저가 읽어야 한다.
   */
  const verifySeqRef = useRef(0);

  /**
   * OAuth 토큰/API 키가 실제로 서버에서 통하는지 확인해 배지(`authStatus`)를 갱신한다.
   *
   * <b>훅보다 위에 선언한다</b> — `useAiSettingsForm` 에 `onCredentialsChanged` 로 넘겨야 하기
   * 때문이다. 의존성이 비어 있어(`[]`) 훅 반환값을 참조하지 않으므로 순서를 올려도 안전하다.
   *
   * 부르는 곳은 넷이다: "인증 확인" 버튼, 저장 성공 직후, <b>자격증명 번들 해제 직후</b>,
   * <b>저장된 OAuth 토큰 삭제 직후</b>. 뒤의 둘이 빠져 있으면 이미 적용되지 않는 자격증명으로
   * 얻은 `✓ 인증됨` 이 화면에 그대로 남는다.
   */
  const verifyAuth = useCallback(async () => {
    const seq = ++verifySeqRef.current;
    setIsVerifying(true);
    try {
      const { data } = await settingsApi.verifyAuthStatus();
      // 내가 더 이상 <b>최신</b> 확인이 아니면 결과를 버린다. 화면에는 나중에 시작한 확인의
      // 답만 남아야 한다 — 그 답이 지금 서버 상태를 본 것이기 때문이다.
      if (seq !== verifySeqRef.current) return;
      setAuthStatus(data);
    } catch {
      if (seq !== verifySeqRef.current) return;
      setAuthStatus(null);
    } finally {
      // 진행 표시도 같은 규칙을 따른다. 무조건 내리면 낡은 확인이 끝나는 순간 아직 진행 중인
      // 최신 확인 위로 버튼이 다시 열려, 그 위에 또 겹칠 수 있다.
      if (seq === verifySeqRef.current) setIsVerifying(false);
    }
  }, []);

  /**
   * AI 탭의 <b>번들 레이어 + 폼 상태 기계</b>. 이메일 탭과 같은 모양이다 — `useAiSettingsForm` 이
   * 공통 훅(`useSettingsOverrideForm`) 위에 자격증명 3키의 원자성을 얹고, 페이지는 그 반환값을
   * 그린다. 숫자 검증·`handleSave`·인증 확인은 번들과 무관해 여기 남는다.
   *
   * 조회 실패 시 이 탭은 종단 화면을 만들지 않고 toast 만으로 알린 뒤 폴백 값으로 렌더한다.
   *
   * <b>2026-09-18 이후로 이 비대칭의 전제가 흔들렸다</b>: `ai.api_key` / `ai.cli_oauth_token`
   * 도 이제 테넌트 오버라이드가 가능해져 서버가 마스킹해 내려주는 자격증명 필드가 이 탭에도
   * 생겼다(SMTP 탭이 종단 화면을 쓰는 바로 그 이유). 다만 저장 페이로드는 여전히 "이번에 바꾼
   * 키만" 담고(`buildChangedPayload`), 비밀 2키는 <b>애초에 서버 값을 시드하지 않으므로</b>
   * (`emptySeedKeys`) 손대지 않은 비밀은 조회 성공·실패와 무관하게 페이로드에서 빠진다.
   */
  const ai = useAiSettingsForm({ onCredentialsChanged: verifyAuth });
  const {
    isLoading,
    settings,
    form,
    errors,
    setErrors,
    isClearing,
    fieldState,
    isEditable,
    hasChanges,
    updateField,
    handleReset,
    handleClearOverride,
    buildChangedPayload,
    commitSaved,
    refreshMeta,
    resyncFromServer,
  } = ai.base;

  // isBlankAllowed 는 제거했다. DB 행도 코드 기본값도 없는 'no-default' 상태에서만 참이 되는데,
  // AI 8키는 V15/V69 에서 전부 non-null 로 시드돼 있고 유일하게 시드가 없는
  // ai.session_max_tokens 는 BUILTIN_AI_DEFAULTS 가 덮어 'builtin-default' 가 된다.
  // 즉 편집 가능 키 중 어느 것도 그 상태에 도달하지 못해, 다섯 개의 검증 분기가 전부
  // "항상 참인 가드" 안에 들어 있었다 — 읽는 사람이 그 가드가 살아 있는 경우를 지키는지
  // 판단할 수 없다. (순수 함수 쪽 5상태는 단위 테스트가 지키므로 그대로 둔다.)

  const validate = (): boolean => {
    const newErrors: Partial<Record<keyof AISettingsForm, string>> = {};

    // 자격증명 3키(ai.agent_type / ai.api_key / ai.cli_oauth_token)는 형식 검증하지 않는다.
    // agent_type 은 Select 가 고정 옵션으로 이미 제약하고, api_key·oauth_token 은 자유 형식
    // 문자열이라 정해진 형식 규칙이 없다. 2026-09-18 부터 이 셋도 테넌트가 고칠 수 있지만
    // (플랫폼이 잠가 둔 경우엔 `isEditable` 이 입력을 막는다), 여기서 없는 규칙을 만들어
    // 검증하면 "고칠 수 없는 오류" 때문에 temperature 같은 편집 가능 필드의 저장까지 영구히
    // 막힌다(플랫폼이 sdk + api_key 미설정인 상태가 실제로 존재한다).
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

    // 페이로드 조립(잠긴 키 제외·미편집 키 제외·빈 값 거부 목록)은 훅이 한다 — 이 규칙이
    // 이메일 탭과 갈라지면 한쪽만 고치는 사고가 난다.
    //
    // 왜 validate() 와 별도인가: validate() 는 "입력값이 규칙에 맞는가"를 보고, 아래 검사는
    // "만든 페이로드가 사용자가 방금 한 편집을 실제로 담고 있는가"를 본다. 검증 규칙이 나중에
    // 완화되어도 이 대조는 계속 성립해야 하므로 페이로드를 만든 뒤 한 번 더 확인한다.
    const { payload, droppedChangedKeys } = buildChangedPayload();
    if (droppedChangedKeys.length > 0) {
      const names = droppedChangedKeys.map((key) => AI_FIELD_LABELS[key]).join(', ');
      toast.error(
        `${names}을(를) 비워 둔 채로는 저장할 수 없습니다. 플랫폼 기본값으로 되돌리려면 "재정의 해제"를 사용하세요.`,
      );
      return;
    }

    setIsSaving(true);
    try {
      await settingsApi.update({ settings: payload });
      commitSaved();
      toast.success('설정이 저장되었습니다.');
      // 저장한 키는 이제 테넌트 재정의 상태이므로 배지를 다시 읽어 맞춘다.
      //
      // 자격증명 번들 3키(ai.agent_type/ai.api_key/ai.cli_oauth_token) 중 하나라도 이번
      // payload 에 있었다면 추가로 폼 값도 다시 시드한다 — commitSaved() 는 이번에 안 바뀐
      // 나머지 번들 키의 "저장 전 낡은 표시값"을 그대로 새 기준(original)으로 확정해 버리는데,
      // 서버는 번들 규칙(SettingsService.applyAiCredentialBundle)에 따라 그 키들의 해석값을
      // 조용히 바꿔 놓았을 수 있다(예: agent_type 만 저장 → api_key/oauth_token 이 서버에서는
      // "" 로 해석되기 시작하는데 화면은 여전히 저장 전 마스킹 값을 보여준다). 이 어긋남은
      // Task 6 이 세 필드를 처음으로 조작 가능하게 만들면서 새로 열린 회귀다 — 예전에는 세
      // 필드가 항상 잠겨 있어 payload 에 들어갈 수 없었으므로 번들 계산 자체가 이 화면에서
      // 트리거될 일이 없었다(2026-09-18 리뷰, Concern 4). `refreshMeta` 가 돌려주는 최신
      // byKey 로 세 키를 `resyncFromServer` 하면 폼이 서버 진실과 다시 맞는다.
      refreshMeta()
        .then((byKey) => {
          if (AI_CREDENTIAL_BUNDLE_KEYS.some((key) => key in payload)) {
            resyncFromServer(AI_CREDENTIAL_BUNDLE_KEYS, byKey);
          }
        })
        .catch(() => {
          // <b>저장은 성공했고 다시 그리기가 실패했다.</b> 삼키면 화면이 조용히 거짓말한다:
          // 배지는 저장 전 상태로 남고, 방금 저장한 비밀의 평문은 `form`·`original` 에 그대로
          // 남으며, 힌트는 옛 "설정되어 있습니다"를 계속 보여준다. 그렇다고 바깥 catch 로 넘겨
          // "저장 실패" 토스트를 띄우면 실제로 저장된 값을 사용자가 되돌리려 든다. 그래서 저장
          // 성공은 성공대로 두고, 무엇이 실패했고 무엇을 해야 하는지를 낡음 안내로 따로 말한다
          // (SMTP `handleSave` 와 같은 문구 — 훅의 `markSaveRefreshFailed` 가 소유한다).
          ai.markSaveRefreshFailed();
        });
      verifyAuth();
    } catch {
      toast.error('설정 저장에 실패했습니다.');
    } finally {
      setIsSaving(false);
    }
  };

  /**
   * 비밀 입력(<b>항상 빈 채로 시작한다</b>) 밑의 상태 힌트. 빈 입력창은 "아직 안 채운 칸"과
   * "이미 값이 있는 칸"을 시각적으로 구별하지 못하므로, 이 한 줄이 그 구별의 <b>유일한</b>
   * 전달 경로다 — 그래서 각 입력의 `aria-describedby` 에 포함한다.
   *
   * 판정은 폼이 아니라 <b>서버가 내려준 값</b>을 본다. 폼 값은 이 두 키에 대해 항상 빈 문자열이라
   * (마스크를 시드하지 않는다) 폼을 보면 "모든 비밀이 비어 있다"는 답만 나온다.
   *
   * 세 갈래다:
   * 1. 번들이 재정의됐는데 이 키에 행이 없어 <b>빈 값으로 해석</b>되는 상태 — 공용
   *    `EmptyInBundleNote` 가 말한다("플랫폼 값이 사용되지 않습니다"). 아래 3번과 뭉뚱그리면
   *    사용자가 의도해서 비운 것처럼 읽혀, 자격증명 없이 동작하게 된 위험을 감춘다.
   * 2. 값이 있다 — 비워 두면 유지된다는 사실을 함께 말해야 한다. 그러지 않으면 "빈 칸이니 다시
   *    입력해야 하나 보다"로 읽혀 매번 키를 다시 붙여넣게 된다.
   * 3. 값이 없고 번들도 상속 중 — 정말 설정된 값이 없다.
   *
   * <b>"사실"과 "할 일"을 나눠 쓴다.</b> 사실("현재 값이 설정되어 있습니다")은 잠금 여부와 무관하게
   * 참이지만, 할 일("바꾸려면 새 값을 입력하세요")은 <b>입력이 열려 있을 때만</b> 참이다. 잠긴
   * 그룹에서는 입력이 `disabled` 라 시키는 대로 할 수가 없고, 바로 아래에 `PlatformLockedNote`
   * ("플랫폼 운영자만 변경할 수 있는 항목입니다")가 붙어 두 문장이 정면으로 충돌한다 —
   * <b>거짓 어포던스</b>다.
   *
   * 이 갈림은 Finding 2 가 새로 연 것이다: 예전에는 잠긴 입력에도 마스크가 시드돼 있어 힌트가
   * 아예 필요 없었는데, 입력을 비우면서 힌트가 상태의 유일한 전달 경로가 되자 그 문장이 잠긴
   * 쪽까지 따라왔다.
   *
   * 잠겼다고 힌트를 <b>통째로</b> 지우지는 않는다 — 그러면 "값이 있는 잠긴 키"와 "값이 없는 잠긴
   * 키"가 똑같이 빈 칸으로 보여, 플랫폼이 아직 자격증명을 넣지 않았다는 사실이 화면에서 사라진다.
   *
   * 잠금 판정은 `isEditable` 하나만 쓴다(그룹 상태를 여기서 다시 읽지 않는다) — 그 함수가 이미
   * `effectiveState` 를 지나 번들 그룹 판정(fail-closed)을 반영하므로, 따로 계산하면 입력의
   * `disabled` 와 힌트가 갈라질 자리가 생긴다.
   */
  const secretStateHint = (key: keyof AISettingsForm, id: string) => {
    if (ai.isEmptyInBundle(key)) return <EmptyInBundleNote id={id} show />;
    if (!ai.isSecretStored(key)) {
      return (
        <p id={id} className="text-sm text-muted-foreground">
          설정된 값이 없습니다.
        </p>
      );
    }
    return (
      <p id={id} className="text-sm text-muted-foreground">
        현재 값이 설정되어 있습니다.
        {isEditable(key) && ' 바꾸려면 새 값을 입력하세요 — 비워 두면 현재 값이 그대로 유지됩니다.'}
      </p>
    );
  };

  // 재정의 중인 필드에만 해제 버튼을 붙인다 — 상속 중인 필드에는 지울 오버라이드가 없다.
  //
  // <b>자격증명 3키는 여기 오지 않는다</b>(SMTP 연결 5키와 같은 규칙, 같은 이유): 그 3키의 키 단위
  // 해제는 "플랫폼 상속으로 돌아간다"를 표현하지 못한다 — 행 하나를 지워도 남은 두 행 때문에
  // `applyAiCredentialBundle` 이 계속 발동해 그 키가 플랫폼 값이 아니라 번들 채움("" / "sdk")으로
  // 해석되기 때문이다. 그래서 그룹 머리의 버튼 하나가 3키를 함께 지운다.
  const clearAction = (key: keyof AISettingsForm) =>
    !AI_CREDENTIAL_BUNDLE_KEYS.includes(key) && fieldState(key) === 'overridden' ? (
      <ClearOverrideButton onConfirm={() => handleClearOverride(key)} disabled={isClearing} />
    ) : undefined;

  /**
   * 이메일 탭의 폼 상태도 <b>페이지가 소유한다</b>. Radix `TabsContent` 가 비활성 탭을
   * 언마운트하므로 탭이 상태를 갖고 있으면 탭 전환이 미저장 편집을 죽인다 — AI 탭은 여기서
   * 살아남는데 이메일 탭만 죽는 비대칭이었고, 고쳐야 할 것은 계약을 어긴 쪽이다(#390-2b).
   */
  const smtp = useSmtpSettingsForm();

  // 탭별 dirty 상태를 합산해 페이지 전체 dirty 여부를 결정한다 (이슈 #86).
  // 두 탭 모두 상태를 이 페이지가 소유하므로 보고도 페이지가 직접 한다 — 언마운트 클린업으로
  // dirty 를 false 로 되돌리던 보정은 사라졌다. 살아 있는 편집을 dirty 아님으로 보고하면
  // 이탈 가드가 침묵해 결함이 유실에서 경고 누락으로 모습만 바뀐다.
  // 임베딩 탭은 여전히 전면 잠금이라 dirty 가 될 수 없어 보고자가 없다.
  const { isAnyDirty, makeReporter } = useDirtyAggregator();
  const aiReporter = makeReporter('ai');
  const smtpReporter = makeReporter('smtp');
  const smtpHasChanges = smtp.base.hasChanges;
  useEffect(() => {
    aiReporter(hasChanges);
  }, [aiReporter, hasChanges]);
  useEffect(() => {
    smtpReporter(smtpHasChanges);
  }, [smtpReporter, smtpHasChanges]);
  const { dialog: unsavedDialog } = useUnsavedChangesGuard(isAnyDirty);

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
              {/* "지금 화면을 믿지 말고 다시 읽어라" 안내. 토스트 한 번으로 끝내지 않는 이유는
                  사용자가 다시 조작해야 하는 상태이고 토스트는 사라지기 때문이다(SMTP 와 동일).

                  <b>탭 범위에 둔다.</b> 예전에는 자격증명 `fieldset` 안에 있었고, 그때는 이 슬롯을
                  세우는 사건이 번들 해제 부분 실패와 토큰 삭제 후 재조회 실패뿐이라 맞는 자리였다.
                  저장 후 재조회 실패가 이 슬롯을 함께 쓰게 되면서 전제가 깨졌다 — `ai.max_turns`
                  하나만 저장하고 재조회가 실패해도 번들과 아무 상관 없는 경고가 "지금은 플랫폼
                  기본값을 그대로 쓰고 있습니다" 문단 밑에 붙어, 사용자가 자격증명이 잘못됐다고
                  읽는다. 상태가 탭 범위가 됐으면 표시도 탭 범위여야 한다(SMTP 가 같은 이유로
                  같은 이동을 이미 했다). */}
              {ai.staleNotice && <InlineBanner variant="warning">{ai.staleNotice}</InlineBanner>}

              {/* 자격증명 3키는 하나의 `fieldset` 으로 묶는다 — <b>SMTP 연결 그룹의 구조를 그대로
                  거울로 삼는다</b>. 같은 서버 규칙(번들 원자 해석)이 같은 화면 문제를 만들기
                  때문이고, 다른 모양으로 풀면 한쪽만 고치는 사고가 난다. 그룹 경계를 테두리로만
                  전달하면 스크린리더 사용자가 "API 키 필드 하나"만 만났을 때 그것이 묶음의
                  일부라는 사실을 듣지 못한다 — `legend` 는 그룹 안 어느 필드에 도착하든 함께 읽힌다. */}
              <fieldset
                className="space-y-6 rounded-md border p-4"
                aria-describedby="ai-credential-desc"
              >
                <legend className="flex flex-wrap items-center gap-2 px-1 text-sm font-medium">
                  자격증명
                  {/* 범위는 배지가 아니라 이 보조 문구가 짊어진다 — 배지 문자열을 새로 만들면
                      어휘 부담이 영구히 생긴다(SMTP 그룹과 같은 판단). */}
                  <span className="font-normal text-muted-foreground">3개 항목이 함께 적용됩니다</span>
                </legend>

                <div className="space-y-2">
                  <div className="flex flex-wrap items-center gap-2">
                    {/* 그룹 배지 하나 + 필드 배지 0개. 세 필드가 전부 같은 배지를 달면 오히려
                        "각각 독립적으로 그런 상태다"로 읽혀, 배지가 고치려던 오해를 다시 심는다
                        (SMTP 연결 그룹이 같은 이유로 필드 배지를 뗐다). */}
                    <SettingStateBadge state={ai.credentialGroupState} />
                    {ai.credentialGroupState === 'overridden' && (
                      <ClearOverrideButton
                        onConfirm={ai.handleClearCredentialBundle}
                        disabled={isClearing}
                        label="자격증명 전체 재정의 해제"
                        dialogTitle="자격증명 재정의 해제"
                        /* 3개 항목을 이름으로 나열한다 — "이 그룹"이라고 쓰면 사용자가 그룹 경계를
                           스크롤 밖에서 추정해야 한다. 비밀 2키는 화면에 평문이 없어 다시 칠 수
                           없으므로 "복구할 수 없으며" 한 마디를 번들 문구에만 더한다. */
                        dialogDescription={`에이전트 유형, OAuth 토큰, API 키 3개 항목의 테넌트 설정이 모두 삭제되고 플랫폼 기본값으로 전환됩니다. 입력한 OAuth 토큰과 API 키는 복구할 수 없으며, 필요하면 언제든 다시 재정의할 수 있습니다.${UNSAVED_CREDENTIAL_WARNING(ai.hasUnsavedCredentialInput)}`}
                      />
                    )}
                  </div>
                  {/* 번들 규칙 안내는 <b>그룹 설명문 하나</b>가 짊어진다. 예전에는 에이전트 유형
                      필드 밑에 따로 붙어 있었는데, 그러면 그룹 상태 문장과 이 문장이 같은 그룹을
                      서로 다르게 말하게 된다.

                      채워지는 값이 둘로 갈린다는 사실은 반드시 남긴다
                      (`SettingsService.BUNDLE_FILL_VALUES`): 자격증명(OAuth 토큰·API 키)은 ""
                      로 채워져 정말 비워지지만, `ai.agent_type` 은 "" 대신 "sdk"(Claude Agent
                      SDK)로 채워진다 — 실행 형태 선택자라 빈 값이 안전한 방향이 아니기 때문이다
                      (빈 값은 AiAgentProxyService 에서 cli-api 분기로 떨어진다). 그래서
                      "나머지는 비워진다"는 자격증명 2키에만 참이다. */}
                  <p id="ai-credential-desc" className="text-sm text-muted-foreground">
                    {ai.credentialGroupState === 'overridden'
                      ? '이 3개 항목은 우리 조직 값으로 적용되고 있습니다. 플랫폼 기본값은 이 중 어느 항목에도 더 이상 사용되지 않습니다. 셋 중 하나만 바꿔도 나머지는 플랫폼 값을 상속하지 않습니다 — 자격증명(OAuth 토큰·API 키)은 비워지고, 에이전트 유형은 Claude Agent SDK 가 적용됩니다.'
                      : '에이전트 유형·OAuth 토큰·API 키는 한 벌의 실행 자격이므로 항상 함께 적용됩니다. 지금은 플랫폼 기본값을 그대로 쓰고 있습니다. 셋 중 하나라도 저장하면 나머지는 플랫폼 값을 상속하지 않습니다 — 자격증명(OAuth 토큰·API 키)은 비워지고, 에이전트 유형에 테넌트 값이 없으면 Claude Agent SDK 가 적용됩니다.'}
                  </p>
                </div>

                {/* 에이전트 유형 — 실행 형태·과금 주체라 예전엔 플랫폼 전용이었지만, 2026-09-18
                    부터 ai.api_key / ai.cli_oauth_token 과 한 번들로 테넌트 오버라이드가 가능하다.
                    편집 가능 여부는 서버의 tenantEditable 플래그를 그대로 따르되, 번들이므로
                    3키 중 하나라도 잠겨 있으면 그룹 전체가 잠긴다(fail-closed, 훅 참고).
                    잠금 안내문은 "잠겼을 때만" 조건부로 띄운다 — 항상 띄우면 편집 가능한
                    테넌트에게 거짓 안내가 되고, 아예 없애면 실제로 잠긴 테넌트에게 이유를
                    알려줄 수단이 사라진다. 배지는 그룹 머리에 하나뿐이므로 여기서는 평범한
                    `Label` 을 쓴다. */}
                <div className="space-y-2">
                  <Label htmlFor="ai-agent-type">에이전트 유형</Label>
                  <Select
                    value={form['ai.agent_type']}
                    onValueChange={(value) => updateField('ai.agent_type', value)}
                    disabled={!isEditable('ai.agent_type')}
                  >
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
                  {!isEditable('ai.agent_type') && <PlatformLockedNote />}
                </div>

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
                        <Label htmlFor="ai-cli-oauth-token">OAuth 토큰</Label>
                        <div className="flex gap-2 max-w-md">
                          {/* <b>입력은 항상 빈 채로 시작한다</b> — 서버 마스크를 시드하지 않는다
                              (`useSettingsOverrideForm` 의 `emptySeedKeys`). 마스크가 시드돼 있으면
                              사용자가 그 뒤에 키를 <b>덧붙여</b> `****ab12sk-ant-…` 를 만들 수 있고,
                              그 문자열은 서버 센티널 판정(길이 4 또는 8 만 드롭)을 빠져나가 진짜
                              자격증명으로 저장된다 — 저장 후 새 마스크가 보이므로 사용자가 알아챌
                              표면이 없다.
                              표시/숨기기 토글은 두지 않는다: 서버가 평문을 절대 내려주지 않아
                              눌러도 보여줄 것이 없다. */}
                          <Input
                            id="ai-cli-oauth-token"
                            type="password"
                            className="flex-1"
                            value={form['ai.cli_oauth_token']}
                            disabled={!isEditable('ai.cli_oauth_token')}
                            onChange={(e) => updateField('ai.cli_oauth_token', e.target.value)}
                            placeholder="sk-ant-oat01-..."
                            aria-describedby="ai-cli-oauth-token-desc ai-cli-oauth-token-hint"
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
                        <p id="ai-cli-oauth-token-desc" className="text-sm text-muted-foreground">
                          로컬에서 claude setup-token으로 발급받은 OAuth 토큰
                          {authStatus && (
                            <span className={`ml-2 inline-flex items-center text-xs font-medium ${authStatus.valid ? 'text-success' : 'text-destructive'}`}>
                              {authStatus.valid ? '✓ 인증됨' : '✗ 유효하지 않음'}
                              {authStatus.valid && authStatus.email && ` (${authStatus.email})`}
                              {authStatus.valid && authStatus.subscriptionType && ` · ${authStatus.subscriptionType}`}
                            </span>
                          )}
                        </p>
                        {secretStateHint('ai.cli_oauth_token', 'ai-cli-oauth-token-hint')}
                        {/* 저장된 토큰을 <b>빈 값으로</b> 내리는 유일한 조작. 입력창을 비우는
                            제스처는 더 이상 존재하지 않는다(폼이 항상 비어 있으므로 "비웠다"는
                            변경이 성립하지 않는다) — 그래서 명시적 버튼이 필요하다. 노출 조건과
                            그것이 막는 사고는 `canDeleteOauthToken` 주석에 있다. */}
                        {ai.canDeleteOauthToken && (
                          <ClearOverrideButton
                            onConfirm={ai.handleDeleteOauthToken}
                            disabled={isClearing}
                            label="저장된 OAuth 토큰 삭제"
                            dialogTitle="저장된 OAuth 토큰 삭제"
                            dialogDescription={`우리 조직에 저장된 OAuth 토큰이 빈 값으로 저장됩니다. 에이전트 유형과 API 키는 그대로 유지되므로, sdk 로 동작 중이라면 이후 API 키로 인증합니다. 삭제한 토큰은 복구할 수 없으며, 필요하면 언제든 다시 입력할 수 있습니다.${UNSAVED_CREDENTIAL_WARNING(ai.hasUnsavedCredentialInput)}`}
                            confirmLabel="삭제"
                            destructive
                            /* 되돌릴 수 없는 유일한 호출부다 — 서버가 평문을 내려주지 않아
                               화면에 다시 칠 원본이 없다. 표현(휴지통 아이콘·위험색)까지 갈라
                               두지 않으면 복구 불가 조작이 "실행 취소"처럼 보인다. 이 그룹의
                               "자격증명 전체 재정의 해제"는 다시 재정의할 수 있으므로 켜지 않는다. */
                          />
                        )}
                        {!isEditable('ai.cli_oauth_token') && <PlatformLockedNote />}
                      </div>
                    )}
                    {/* cli-api 또는 sdk: API 키 필드 */}
                    {(form['ai.agent_type'] === 'cli-api' || form['ai.agent_type'] === 'sdk') && (
                      <div className="space-y-2">
                        <Label htmlFor="ai-api-key">API 키</Label>
                        <div className="flex gap-2 max-w-md">
                          {/* OAuth 토큰과 같은 이유로 빈 입력 시드 + 표시/숨기기 토글 없음. */}
                          <Input
                            id="ai-api-key"
                            type="password"
                            className="flex-1"
                            value={form['ai.api_key']}
                            disabled={!isEditable('ai.api_key')}
                            onChange={(e) => updateField('ai.api_key', e.target.value)}
                            placeholder="sk-ant-..."
                            aria-describedby="ai-api-key-desc ai-api-key-hint"
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
                        <p id="ai-api-key-desc" className="text-sm text-muted-foreground">
                          Anthropic API 키 (sk-ant-...)
                          {authStatus && (
                            <span className={`ml-2 inline-flex items-center text-xs font-medium ${authStatus.valid ? 'text-success' : 'text-destructive'}`}>
                              {authStatus.valid ? '✓ 인증됨' : '✗ 유효하지 않음'}
                              {authStatus.valid && authStatus.email && ` (${authStatus.email})`}
                              {authStatus.valid && authStatus.subscriptionType && ` · ${authStatus.subscriptionType}`}
                            </span>
                          )}
                        </p>
                        {secretStateHint('ai.api_key', 'ai-api-key-hint')}
                        {/* API 키에는 "삭제" 버튼이 없다 — 서버가 빈 `ai.api_key` 를 거부하므로
                            (`SettingsService.validateValues`) 표현할 수 있는 조작이 아니다.
                            플랫폼으로 돌아가는 길은 그룹 전체 해제뿐이다. */}
                        {!isEditable('ai.api_key') && <PlatformLockedNote />}
                      </div>
                    )}
                  </div>
                )}
              </fieldset>

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
        {/* 이메일 탭 — 폼 상태는 페이지가 소유한다(탭 전환에도 편집이 살아남는다) */}
        <TabsContent value="email" className="mt-6">
          <SmtpSettingsTab state={smtp} />
        </TabsContent>
        {/* 임베딩 탭 — 전면 잠금이라 dirty 보고자가 없다 */}
        <TabsContent value="embedding" className="mt-6">
          <EmbeddingSettingsTab />
        </TabsContent>
      </Tabs>

      {/* 미저장 변경사항 이탈 가드 다이얼로그 (이슈 #86) */}
      {unsavedDialog}
    </div>
  );
}
