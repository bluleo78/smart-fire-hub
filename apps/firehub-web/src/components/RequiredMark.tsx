/**
 * 필수 입력 칸 라벨 뒤에 붙는 별표(*).
 *
 * 별표를 글자 그대로 두면 접근 이름이 "이름 *" 처럼 읽혀 getByLabel·보조기술에 잡음이 된다(WD-964).
 * 그래서 aria-hidden 으로 접근 이름에서 빼고, 필수 여부는 입력 칸의 aria-required="true" 로 따로 알린다.
 * 색·간격은 호출처가 className 으로 넘겨 기존 겉모양을 그대로 유지한다.
 * 사용: <Label htmlFor="x"><span>이름 <RequiredMark /></span></Label> — Label 이 flex gap 이라 글자와 별표를 span 하나로 묶는다.
 */
export function RequiredMark({ className }: { className?: string }) {
  return (
    <span aria-hidden="true" className={className}>
      *
    </span>
  );
}
