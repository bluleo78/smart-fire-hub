package com.smartfirehub.pipeline.service.validator;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.exception.UnsafePythonScriptException;
import org.junit.jupiter.api.Test;

/**
 * PythonScriptValidator 단위 테스트 (#270).
 *
 * <p>핵심: 차단(malicious)뿐 아니라 <b>허용(legitimate)</b> 케이스를 함께 검증한다. false-positive 는 ETL 파이프라인을 깨뜨리므로
 * 정당 패턴(psycopg2/urllib/pandas/os.environ)이 통과함을 보장하는 것이 차단만큼 중요하다.
 */
class PythonScriptValidatorTest {

  private final PythonScriptValidator validator = new PythonScriptValidator();

  // ---------------------------------------------------------------------------
  // 허용 — 문서화된 정당 ETL 패턴 (false-positive 가드)
  // ---------------------------------------------------------------------------

  @Test
  void allows_psycopg2_db_input_read() {
    // Python 스텝의 유일한 입력 메커니즘 — DB self-read. 차단하면 모든 ETL 이 깨진다.
    String script =
        "import os, json, psycopg2\n"
            + "conn = psycopg2.connect(os.environ['DB_URL'])\n"
            + "cur = conn.cursor(); cur.execute('SELECT a FROM data.t')\n"
            + "print(json.dumps([{'a': r[0]} for r in cur.fetchall()]))";
    assertThatCode(() -> validator.validate(script)).doesNotThrowAnyException();
  }

  @Test
  void allows_urllib_external_fetch() {
    // step-types.md 에 문서화된 fetch_exchange_rate 예시 패턴.
    String script =
        "import json, urllib.request\n"
            + "data = json.loads(urllib.request.urlopen('https://api.example.com/r').read())\n"
            + "print(json.dumps(data))";
    assertThatCode(() -> validator.validate(script)).doesNotThrowAnyException();
  }

  @Test
  void allows_pandas_numpy_processing() {
    String script =
        "import pandas as pd, numpy as np\n"
            + "df = pd.DataFrame({'a': [1, 2]})\n"
            + "df['b'] = df.eval('a * 2')\n" // pandas 메서드 .eval() 은 builtin eval 이 아니다 → 허용
            + "print(df.to_json(orient='records'))";
    assertThatCode(() -> validator.validate(script)).doesNotThrowAnyException();
  }

  @Test
  void allows_os_environ_credential_read() {
    // DB_URL 등 자동 제공 env 읽기는 정당.
    assertThatCode(() -> validator.validate("import os\nurl = os.environ['DB_URL']\nprint('[]')"))
        .doesNotThrowAnyException();
  }

  @Test
  void allows_identifier_substrings_that_resemble_blocked_words() {
    // 'executor', 'evaluation', 'compiled' 같은 식별자는 차단 패턴과 겹쳐 보이지만 오탐이면 안 된다.
    String script = "executor = 1\nevaluation_score = 2\ncompiled_result = []\nprint('[]')";
    assertThatCode(() -> validator.validate(script)).doesNotThrowAnyException();
  }

  @Test
  void allows_empty_or_null_script() {
    // 빈 스크립트는 본 검증기 관심사 아님 (다른 단계에서 처리).
    assertThatCode(() -> validator.validate(null)).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate("   ")).doesNotThrowAnyException();
  }

  // ---------------------------------------------------------------------------
  // 차단 — ETL 에 정당하게 쓰일 일 없는 shell/동적실행 원시함수
  // ---------------------------------------------------------------------------

  @Test
  void blocks_subprocess_import() {
    assertThatThrownBy(() -> validator.validate("import subprocess\nsubprocess.run(['ls'])"))
        .isInstanceOf(UnsafePythonScriptException.class);
  }

  @Test
  void blocks_subprocess_call() {
    assertThatThrownBy(
            () -> validator.validate("import subprocess as sp\nsp.Popen(['sh', '-c', 'env'])"))
        .isInstanceOf(UnsafePythonScriptException.class);
  }

  @Test
  void blocks_os_system() {
    assertThatThrownBy(() -> validator.validate("import os\nos.system('cat /etc/passwd')"))
        .isInstanceOf(UnsafePythonScriptException.class);
  }

  @Test
  void blocks_os_popen() {
    assertThatThrownBy(() -> validator.validate("import os\nos.popen('whoami').read()"))
        .isInstanceOf(UnsafePythonScriptException.class);
  }

  @Test
  void blocks_builtin_eval() {
    assertThatThrownBy(() -> validator.validate("x = eval('__import__(\"os\").getcwd()')"))
        .isInstanceOf(UnsafePythonScriptException.class);
  }

  @Test
  void blocks_builtin_exec() {
    assertThatThrownBy(() -> validator.validate("exec('import os')"))
        .isInstanceOf(UnsafePythonScriptException.class);
  }

  @Test
  void blocks_dunder_import() {
    assertThatThrownBy(() -> validator.validate("m = __import__('subprocess')"))
        .isInstanceOf(UnsafePythonScriptException.class);
  }

  @Test
  void blocks_importlib() {
    assertThatThrownBy(() -> validator.validate("import importlib\nimportlib.import_module('os')"))
        .isInstanceOf(UnsafePythonScriptException.class);
  }

  @Test
  void blocks_ctypes() {
    assertThatThrownBy(() -> validator.validate("import ctypes\nctypes.CDLL('libc.so.6')"))
        .isInstanceOf(UnsafePythonScriptException.class);
  }

  @Test
  void blocks_compile_builtin() {
    assertThatThrownBy(() -> validator.validate("c = compile('1+1', '<s>', 'eval')"))
        .isInstanceOf(UnsafePythonScriptException.class);
  }

  @Test
  void blocks_dunder_import_reconstruction_bypass_attempt() {
    // getattr 재구성 같은 우회는 본 검증기가 완전히 막지 못함을 인지하되, 직접적인 __import__ 는 차단된다.
    assertThatThrownBy(() -> validator.validate("__import__('o'+'s').system('id')"))
        .isInstanceOf(UnsafePythonScriptException.class);
  }
}
