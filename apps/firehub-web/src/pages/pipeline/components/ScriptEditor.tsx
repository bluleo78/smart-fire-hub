import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { python } from '@codemirror/lang-python';
import { sql } from '@codemirror/lang-sql';
import { bracketMatching } from '@codemirror/language';
import { Annotation, Compartment, EditorState, Transaction } from '@codemirror/state';
import { Decoration, type DecorationSet, EditorView, keymap,lineNumbers, MatchDecorator, ViewPlugin,type ViewUpdate } from '@codemirror/view';
import { useTheme } from 'next-themes';
import { useEffect, useRef } from 'react';

const stepRefMatcher = new MatchDecorator({
  regexp: /\{\{#\d+\}\}/g,
  decoration: Decoration.mark({ class: 'cm-step-ref' }),
});

const stepRefHighlighter = ViewPlugin.fromClass(
  class {
    decorations: DecorationSet;
    constructor(view: EditorView) {
      this.decorations = stepRefMatcher.createDeco(view);
    }
    update(update: ViewUpdate) {
      this.decorations = stepRefMatcher.updateDeco(update, this.decorations);
    }
  },
  { decorations: (v) => v.decorations },
);

interface ScriptEditorProps {
  value: string;
  onChange: (value: string) => void;
  language: 'SQL' | 'PYTHON';
  readOnly?: boolean;
  insertTextRef?: React.MutableRefObject<((text: string) => void) | null>;
  /** 검증 오류 여부 — 실제 입력 요소(.cm-content)에 aria-invalid 로 단다(WD-560) */
  invalid?: boolean;
  /** 오류 문구 요소 id — 실제 입력 요소에 aria-describedby 로 단다(WD-560) */
  describedBy?: string;
  /**
   * 편집기가 만들어지면 포커스 핸들을, 사라지면 null 을 넘긴다(WD-560).
   * 지연 로드되는 편집기가 준비된 뒤에야 저장 실패 포커스 요청을 처리할 수 있어, 부모가 준비 시점을 알게 한다.
   */
  onReady?: (handle: ScriptEditorHandle | null) => void;
}

/** 부모가 편집기에 포커스를 줄 때 쓰는 핸들(WD-560) */
export interface ScriptEditorHandle {
  focus: () => void;
}

/**
 * 검증 오류 상태를 실제 입력 요소(.cm-content, role=textbox)에 단다(WD-560).
 * 포커스를 받는 것은 이 요소이고 ARIA 1.2 는 role=group 의 aria-invalid 를 지원하지 않으므로 래퍼가 아니라 여기에 둔다.
 */
function scriptEditorErrorAttributes(invalid: boolean, describedBy: string | undefined) {
  const attrs: Record<string, string> = {};
  if (invalid) attrs['aria-invalid'] = 'true';
  if (invalid && describedBy) attrs['aria-describedby'] = describedBy;
  return EditorView.contentAttributes.of(attrs);
}

/**
 * 편집 칸의 접근 가능한 이름(WD-420) — CodeMirror 의 실제 입력 요소(.cm-content, role=textbox)는 기본 이름이 없어
 * 스크린리더가 「편집 가능한 텍스트」로만 읽는다. 스텝 유형(SQL·Python)을 이름에 담아 무엇을 쓰는 칸인지 알린다.
 */
function scriptEditorLabel(language: 'SQL' | 'PYTHON') {
  return EditorView.contentAttributes.of({
    'aria-label': language === 'SQL' ? 'SQL 스크립트 편집' : 'Python 스크립트 편집',
  });
}

function buildStepRefTheme(isDark: boolean) {
  return EditorView.theme({
    '&': { minHeight: '200px', height: '100%', ...(isDark ? { backgroundColor: 'oklch(0.13 0.015 280)' } : {}) },
    '.cm-scroller': { overflow: 'auto' },
    '.cm-content': { fontFamily: 'monospace', fontSize: '13px' },
    '.cm-gutters': isDark ? { backgroundColor: 'oklch(0.15 0.015 280)' } : {},
    '.cm-activeLineGutter': isDark ? { backgroundColor: 'oklch(1 0 0 / 5%)' } : {},
    '.cm-activeLine': isDark ? { backgroundColor: 'oklch(1 0 0 / 5%)' } : {},
    '.cm-step-ref': {
      background: isDark ? 'rgba(167, 139, 250, 0.22)' : 'rgba(124, 58, 237, 0.15)',
      borderRadius: '3px',
      padding: '1px 2px',
      fontWeight: '600',
    },
  });
}

/**
 * 편집 상태(value)를 뷰에 반영하는 동기화 트랜잭션 표시(WD-560).
 * 이 변경을 onChange 로 되돌려 보내면 다른 스텝을 선택하는 것만으로 UPDATE_STEP 이 나가
 * 그 스텝의 검증 오류가 지워지고 isDirty 가 켜진다 — 값은 이미 편집 상태와 같으므로 알릴 필요가 없다.
 */
const externalSync = Annotation.define<boolean>();

export default function ScriptEditor({
  value,
  onChange,
  language,
  readOnly = false,
  insertTextRef,
  invalid = false,
  describedBy,
  onReady,
}: ScriptEditorProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const languageCompartment = useRef(new Compartment());
  const readOnlyCompartment = useRef(new Compartment());
  const themeCompartment = useRef(new Compartment());
  // 검증 오류 aria 속성 칸막이 — 오류가 생기고 사라질 때 편집기를 다시 만들지 않고 바꾼다(WD-560)
  const errorAttrsCompartment = useRef(new Compartment());
  // 실행 취소 기록 칸막이 — 외부 값 동기화(스텝 전환) 때 history 를 뺐다 다시 넣어 이전 스텝의 기록을 비운다(WD-560)
  const historyCompartment = useRef(new Compartment());
  const onReadyRef = useRef(onReady);
  const onChangeRef = useRef(onChange);
  // 에디터는 마운트 시 한 번만 만들어지므로, updateListener 가 readOnly 를 클로저로 잡으면
  // 보기 모드(readOnly=true)로 마운트된 뒤 수정 모드로 바뀌어도 계속 true 로 판단해 onChange 를 삼킨다(#740).
  // onChange 와 마찬가지로 ref 로 최신 값을 읽게 한다.
  const readOnlyRef = useRef(readOnly);
  // readOnly 전환 시 뷰 문서를 편집 상태(value)와 맞추기 위해 최신 value 를 ref 로 둔다(#751).
  const valueRef = useRef(value);
  const { resolvedTheme } = useTheme();

  // Keep onChange ref current without recreating editor
  onChangeRef.current = onChange;
  readOnlyRef.current = readOnly;
  valueRef.current = value;
  onReadyRef.current = onReady;

  // Create editor on mount
  useEffect(() => {
    if (!containerRef.current) return;

    const langExtension = language === 'SQL' ? sql() : python();

    const state = EditorState.create({
      doc: value,
      extensions: [
        lineNumbers(),
        historyCompartment.current.of(history()),
        bracketMatching(),
        keymap.of([...defaultKeymap, ...historyKeymap]),
        // 언어 확장과 이름을 한 칸막이에 두어, 스텝 유형이 바뀔 때 함께 바뀌게 한다(WD-420).
        languageCompartment.current.of([langExtension, scriptEditorLabel(language)]),
        readOnlyCompartment.current.of([
          EditorView.editable.of(!readOnly),
          EditorState.readOnly.of(!!readOnly),
        ]),
        stepRefHighlighter,
        EditorView.updateListener.of((update) => {
          // 마운트 시점 값이 아니라 현재 readOnly 로 판정한다 — 보기→수정 전환 후 입력도 편집 상태에 반영된다
          if (
            !readOnlyRef.current &&
            update.docChanged &&
            !update.transactions.some((tr) => tr.annotation(externalSync))
          ) {
            onChangeRef.current(update.state.doc.toString());
          }
        }),
        themeCompartment.current.of(buildStepRefTheme(resolvedTheme === 'dark')),
        errorAttrsCompartment.current.of(scriptEditorErrorAttributes(invalid, describedBy)),
      ],
    });

    const view = new EditorView({ state, parent: containerRef.current });
    viewRef.current = view;
    // 편집기가 준비됐음을 알린다 — 대기 중인 저장 실패 포커스 요청이 있으면 부모가 이때 처리한다(WD-560)
    onReadyRef.current?.({ focus: () => view.focus() });

    return () => {
      onReadyRef.current?.(null);
      view.destroy();
      viewRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Bind insertText function to ref
  useEffect(() => {
    if (!insertTextRef) return;
    insertTextRef.current = (text: string) => {
      const view = viewRef.current;
      if (!view) return;
      // EditorState.readOnly/editable 은 사용자 입력만 막고 프로그램적 dispatch 는 막지 않는다.
      // 읽기 전용에서 삽입하면 updateListener 가 onChange 를 부르지 않아 뷰 문서만 바뀌고 편집 상태와 어긋나,
      // 수정 모드 전환 후 첫 입력 때 유령 텍스트까지 저장된다(#751). 읽기 전용이면 아무것도 하지 않는다.
      if (readOnlyRef.current) return;
      // changes 만 dispatch 하면 CodeMirror 가 커서를 삽입 "앞"에 매핑해, 이어서 입력한 글자가
      // 삽입한 {{#N}} 앞에 들어간다(#743). replaceSelection 은 선택 영역을 교체(선택 없으면 커서에 삽입)하고
      // 커서를 삽입 끝으로 옮긴 트랜잭션 스펙을 만들어 준다. 버튼 클릭으로 잃은 포커스도 돌려준다.
      view.dispatch({
        ...view.state.replaceSelection(text),
        scrollIntoView: true,
      });
      view.focus();
    };
    return () => {
      if (insertTextRef) insertTextRef.current = null;
    };
  }, [insertTextRef]);

  // Reconfigure language when it changes
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const langExtension = language === 'SQL' ? sql() : python();
    view.dispatch({ effects: languageCompartment.current.reconfigure([langExtension, scriptEditorLabel(language)]) });
  }, [language]);

  // Reconfigure readOnly when it changes
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    view.dispatch({
      effects: readOnlyCompartment.current.reconfigure([
        EditorView.editable.of(!readOnly),
        EditorState.readOnly.of(!!readOnly),
      ]),
    });
    // 방어적 동기화(#751): 읽기 전용 동안 프로그램적 경로로 뷰 문서가 편집 상태와 어긋났다면,
    // 모드가 바뀌는 시점에 편집 상태(value) 기준으로 되돌려 유령 텍스트가 저장으로 이어지지 않게 한다.
    const current = view.state.doc.toString();
    if (current !== valueRef.current) {
      view.dispatch({ changes: { from: 0, to: current.length, insert: valueRef.current } });
    }
  }, [readOnly]);

  // 검증 오류 상태가 바뀌면 입력 요소의 aria-invalid·aria-describedby 를 맞춘다(WD-560)
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    view.dispatch({
      effects: errorAttrsCompartment.current.reconfigure(scriptEditorErrorAttributes(invalid, describedBy)),
    });
  }, [invalid, describedBy]);

  // Reconfigure theme when dark/light changes
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    view.dispatch({ effects: themeCompartment.current.reconfigure(buildStepRefTheme(resolvedTheme === 'dark')) });
  }, [resolvedTheme]);

  // Sync external value changes
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const current = view.state.doc.toString();
    if (current !== value) {
      view.dispatch({
        changes: { from: 0, to: current.length, insert: value },
        // 실행 취소 기록에 넣지 않는다(WD-560) — 넣으면 스텝을 바꾼 직후 Ctrl/Cmd+Z 가 이 동기화를 되돌려
        // 이전 스텝의 스크립트가 새 스텝에 들어가고, 되돌림 트랜잭션엔 externalSync 표시가 없어 그대로 저장 대상이 된다.
        annotations: [externalSync.of(true), Transaction.addToHistory.of(false)],
        // 기록에서 빼는 것만으로는 부족하다 — 이전 스텝의 편집 기록이 남아 이 교체를 거쳐 매핑되므로,
        // A 에서 지운 뒤 B 로 바꾸고 되돌리면 삭제의 역연산(삽입)이 B 스크립트에 끼어들어 B 로 저장된다.
        // 같은 트랜잭션에서 history 확장을 빼 기록 상태(StateField)를 버리고, 아래에서 새 history() 로 다시 넣어 빈 기록으로 시작한다.
        // 편집기를 새로 만들지 않으므로 다른 확장·칸막이(오류 aria 등)·포커스 핸들은 그대로다.
        effects: historyCompartment.current.reconfigure([]),
      });
      view.dispatch({ effects: historyCompartment.current.reconfigure(history()) });
    }
  }, [value]);

  return (
    <div
      ref={containerRef}
      className="border rounded-md overflow-hidden w-full min-w-0"
      style={{ minHeight: '200px', maxWidth: '100%' }}
    />
  );
}
