import { toast } from 'sonner';

/**
 * 클립보드 복사 + 토스트. 계정 생성 다이얼로그와 저장소 스크립트 다이얼로그가 같은 규칙을 쓰도록 한곳에 둔다.
 *
 * 복사 확인은 toast.info 다(웹 멤버 추가와 같은 규칙). 실패는 클립보드 권한이 없는 환경이며 toast.error 로 알린다.
 * 실패 문구는 기본값을 쓰되, 다시 볼 수 없는 비밀(1회용 스크립트)처럼 "직접 선택해 복사하라"는 안내가 필요한 곳은
 * `errorMessage` 로 바꿀 수 있다.
 *
 * @returns 복사에 성공하면 true — 호출자가 "복사됨" 같은 상태를 바꿀 때 쓴다.
 */
export async function copyText(
  text: string,
  successMessage = '클립보드에 복사되었습니다',
  errorMessage = '복사하지 못했습니다',
): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    toast.info(successMessage);
    return true;
  } catch {
    toast.error(errorMessage);
    return false;
  }
}
