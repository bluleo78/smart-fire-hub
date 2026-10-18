/**
 * AIFullScreen 단위 테스트 — 전체 화면 Escape 복귀 (WD-61)
 *
 * 수정 전 결함: 전체 화면 모드에서 Escape 를 눌러도 아무 반응이 없었다.
 * 기대 동작:
 * - 전체 화면에서 Escape → 사이드 패널(setMode('side'))로 돌아간다.
 * - 한글 등 IME 조합 중 Escape 는 조합 취소용이므로 무시한다.
 * - 열린 다이얼로그(Radix 레이어)가 Escape 를 먼저 소비하면 그 레이어만 닫히고 모드는 유지된다.
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogTitle,
} from "../ui/alert-dialog";

const setMode = vi.fn();

// 채팅 본문·세션 사이드바는 API·쿼리 의존성이 커서 모킹한다 — 이 테스트의 관심사는 키 처리뿐이다.
vi.mock("./AIChatPanel", () => ({
  AIChatPanel: () => <textarea aria-label="채팅 입력" />,
}));
vi.mock("./AISessionSidebar", () => ({
  AISessionSidebar: () => <div />,
}));
vi.mock("./AIProvider", () => ({
  useAI: () => ({ setMode }),
}));

import { AIFullScreen } from "./AIFullScreen";

beforeEach(() => {
  setMode.mockClear();
});

describe("AIFullScreen — Escape 로 사이드 패널 복귀 (WD-61)", () => {
  it("입력창에 포커스가 있을 때 Escape 를 누르면 사이드 패널로 전환한다", () => {
    render(<AIFullScreen />);
    const input = screen.getByLabelText("채팅 입력");
    input.focus();
    fireEvent.keyDown(input, { key: "Escape" });
    expect(setMode).toHaveBeenCalledWith("side");
  });

  it("포커스가 본문(body)에 있어도 Escape 로 사이드 패널로 전환한다", () => {
    render(<AIFullScreen />);
    fireEvent.keyDown(document.body, { key: "Escape" });
    expect(setMode).toHaveBeenCalledWith("side");
  });

  it("IME 조합 중(isComposing) Escape 는 무시한다", () => {
    render(<AIFullScreen />);
    fireEvent.keyDown(screen.getByLabelText("채팅 입력"), {
      key: "Escape",
      isComposing: true,
    });
    expect(setMode).not.toHaveBeenCalled();
  });

  it("IME 처리 중 keyCode 229 로 오는 Escape 도 무시한다", () => {
    render(<AIFullScreen />);
    fireEvent.keyDown(screen.getByLabelText("채팅 입력"), {
      key: "Escape",
      keyCode: 229,
    });
    expect(setMode).not.toHaveBeenCalled();
  });

  it("Escape 이외의 키에는 반응하지 않는다", () => {
    render(<AIFullScreen />);
    fireEvent.keyDown(document.body, { key: "Enter" });
    expect(setMode).not.toHaveBeenCalled();
  });

  it("열린 다이얼로그가 있으면 Escape 는 다이얼로그만 닫고 전체 화면을 유지한다", () => {
    // 실제 Radix AlertDialog 를 함께 띄운다 — Radix 가 Escape 를 소비(preventDefault)하는 실제 경로를 검증한다.
    const onOpenChange = vi.fn();
    render(
      <>
        <AIFullScreen />
        <AlertDialog open onOpenChange={onOpenChange}>
          <AlertDialogContent>
            <AlertDialogTitle>대화 삭제</AlertDialogTitle>
            <AlertDialogDescription>삭제할까요?</AlertDialogDescription>
          </AlertDialogContent>
        </AlertDialog>
      </>,
    );
    fireEvent.keyDown(document.activeElement ?? document.body, {
      key: "Escape",
    });
    expect(onOpenChange).toHaveBeenCalledWith(false);
    expect(setMode).not.toHaveBeenCalled();
  });
});
