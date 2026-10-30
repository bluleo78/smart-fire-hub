import json
import os
import shutil
import subprocess
from unittest.mock import MagicMock, patch

import pytest

from app.config import Settings
from app.tenant import TenantResolutionError
from app.services.python_executor import (
    StdoutNotJsonError,
    _convert_single,
    _parse_stdout_json,
    execute_python,
)
from app.services.value_conversion import ConversionFailed


def make_settings(**kwargs) -> Settings:
    defaults = dict(
        db_host="localhost",
        db_port=5432,
        db_name="firehub",
        db_user="pipeline_executor",
        db_password="secret",
        internal_service_token="changeme",
        role_password_secret="test-tenant-pipeline-secret",
        nsjail_enabled=False,
        nsjail_time_limit=1800,
        nsjail_rlimit_as=512,
        nsjail_rlimit_nproc=64,
        nsjail_path="/usr/sbin/nsjail",
        python_timeout=1800,
        python_packages_dir="/usr/local/lib/python3.11/dist-packages",
        db_pool_min=2,
        db_pool_max=10,
    )
    defaults.update(kwargs)
    return Settings.model_construct(**defaults)


@pytest.fixture(autouse=True)
def fake_chown():
    """nsjail 활성 경로는 실행별 폴더를 샌드박스 uid(65534) 소유로 바꾼다(WD-408). 테스트는 root 가 아니라 chown 이 실패하므로
    가짜로 바꾸고, 호출 인자는 이 대역으로 확인한다."""
    with patch("app.services.python_executor.os.chown") as mock_chown:
        yield mock_chown


def make_completed_process(stdout="", stderr="", returncode=0):
    proc = MagicMock()
    proc.stdout = stdout
    proc.stderr = stderr
    proc.returncode = returncode
    return proc


# ---------------------------------------------------------------------------
# test_simple_print_success
# ---------------------------------------------------------------------------
def test_simple_print_success():
    settings = make_settings(nsjail_enabled=False)
    with patch("app.services.python_executor.subprocess.run") as mock_run:
        mock_run.return_value = make_completed_process(stdout="hello\n", returncode=0)
        result = execute_python("print('hello')", None, settings, tenant_id=1, read_slot=2)

    assert result.success is True
    assert "hello" in result.output
    assert result.exit_code == 0
    assert result.error is None
    assert result.execution_time_ms >= 0


# ---------------------------------------------------------------------------
# test_script_failure_nonzero_exit
# ---------------------------------------------------------------------------
def test_script_failure_nonzero_exit():
    settings = make_settings(nsjail_enabled=False)
    with patch("app.services.python_executor.subprocess.run") as mock_run:
        mock_run.return_value = make_completed_process(
            stdout="", stderr="NameError: name 'x' is not defined", returncode=1
        )
        result = execute_python("x", None, settings, tenant_id=1, read_slot=2)

    assert result.success is False
    assert result.exit_code == 1
    assert result.error is not None
    assert "NameError" in result.error


# ---------------------------------------------------------------------------
# test_timeout_handling
# ---------------------------------------------------------------------------
def test_timeout_handling():
    settings = make_settings(nsjail_enabled=False, python_timeout=5)
    mock_proc = MagicMock()
    timeout_exc = subprocess.TimeoutExpired(cmd=["python3"], timeout=5)
    timeout_exc.process = mock_proc

    with patch("app.services.python_executor.subprocess.run", side_effect=timeout_exc):
        result = execute_python("import time; time.sleep(999)", 5, settings, tenant_id=1, read_slot=2)

    assert result.success is False
    assert result.exit_code == -1
    # WD-211: 한국어 문구 — API 로컬 경로(PythonStepErrorMessages.TIMEOUT_MESSAGE)와 같은 문장이다
    assert result.error == "실행 시간 제한(5초)을 넘어 스크립트를 중단했습니다."
    mock_proc.kill.assert_called_once()


# ---------------------------------------------------------------------------
# test_nsjail_command_construction
# ---------------------------------------------------------------------------
def test_nsjail_command_construction():
    settings = make_settings(nsjail_enabled=True)
    with patch("app.services.python_executor.subprocess.run") as mock_run:
        mock_run.return_value = make_completed_process(stdout="ok\n", returncode=0)
        execute_python("print('ok')", None, settings, tenant_id=1, read_slot=2)

    call_args = mock_run.call_args[0][0]  # positional first arg = cmd list

    assert call_args[0] == settings.nsjail_path
    assert "--disable_proc" in call_args
    assert "--rlimit_as" in call_args
    rlimit_as_idx = call_args.index("--rlimit_as")
    assert call_args[rlimit_as_idx + 1] == "512"
    assert "--rlimit_nproc" in call_args
    rlimit_nproc_idx = call_args.index("--rlimit_nproc")
    assert call_args[rlimit_nproc_idx + 1] == "64"
    assert "--time_limit" in call_args
    assert "/usr/local/bin/python3" in call_args
    assert "/script.py" in call_args


# ---------------------------------------------------------------------------
# test_fallback_mode_no_nsjail
# ---------------------------------------------------------------------------
def test_fallback_mode_no_nsjail():
    settings = make_settings(nsjail_enabled=False)
    with patch("app.services.python_executor.subprocess.run") as mock_run:
        mock_run.return_value = make_completed_process(stdout="", returncode=0)
        execute_python("pass", None, settings, tenant_id=1, read_slot=2)

    call_args = mock_run.call_args[0][0]
    assert call_args[0] == "python3"
    assert "nsjail" not in call_args


# ---------------------------------------------------------------------------
# test_fallback_env_isolation
# ---------------------------------------------------------------------------
def test_fallback_env_isolation():
    settings = make_settings(nsjail_enabled=False)
    with patch("app.services.python_executor.subprocess.run") as mock_run:
        mock_run.return_value = make_completed_process(stdout="", returncode=0)
        execute_python("pass", None, settings, tenant_id=1, read_slot=2)

    call_kwargs = mock_run.call_args[1]
    env = call_kwargs["env"]

    # DB 접근은 DB_URL 하나로 충분 — 개별 자격증명 키는 노출하지 않는다 (#89, #270).
    allowed_keys = {
        "DB_URL", "DB_SCHEMA", "PATH", "HOME", "PYTHONDONTWRITEBYTECODE",
    }
    assert set(env.keys()) == allowed_keys
    assert env["DB_SCHEMA"] == "data"
    assert env["HOME"] == "/tmp"
    # 개별 자격증명 키 미노출 (#270)
    for k in ("DB_USER", "DB_PASSWORD", "DB_HOST", "DB_PORT", "DB_NAME"):
        assert k not in env
    # Must NOT inherit host environment
    assert "VIRTUAL_ENV" not in env
    assert "USER" not in env


# ---------------------------------------------------------------------------
# test_nsjail_env_no_discrete_credentials (#270)
#   nsjail 경로도 fallback 과 동일하게 DB_URL/DB_SCHEMA 만 주입하고,
#   개별 자격증명 키(DB_PASSWORD 등)는 스크립트 env 에 노출하지 않는다.
# ---------------------------------------------------------------------------
def test_nsjail_env_no_discrete_credentials():
    settings = make_settings(nsjail_enabled=True)
    with patch("app.services.python_executor.subprocess.run") as mock_run:
        mock_run.return_value = make_completed_process(stdout="ok\n", returncode=0)
        execute_python("print('ok')", None, settings, tenant_id=1, read_slot=2)

    cmd = mock_run.call_args[0][0]
    # cmd 리스트에서 "--env" 다음에 오는 "KEY=VALUE" 들의 KEY 를 수집
    env_keys = {
        cmd[i + 1].split("=", 1)[0]
        for i, tok in enumerate(cmd)
        if tok == "--env" and i + 1 < len(cmd)
    }
    assert "DB_URL" in env_keys
    assert "DB_SCHEMA" in env_keys
    for k in ("DB_USER", "DB_PASSWORD", "DB_HOST", "DB_PORT", "DB_NAME"):
        assert k not in env_keys, f"개별 자격증명 키 {k} 가 nsjail env 에 노출됨 (#270)"


# ---------------------------------------------------------------------------
# 스크립트 자격증명 주입 (P3-b1 → WD-29)
#   nsjail·비nsjail **양쪽** 경로가 같은 자격증명을 넘겨야 한다. 한쪽만 바꾸면
#   배포의 NSJAIL_ENABLED 설정에 따라 접속 주체가 조용히 갈린다.
#   WD-29 부터 스크립트는 테넌트 실행 롤이 아니라 **읽기 슬롯 롤**(pipeline_py_t{id}_s{k})로 직접 로그인한다.
# ---------------------------------------------------------------------------
# 기대값은 구현 함수(resolve_read_db_url)로 만들지 않고 리터럴로 적는다 — 같은 함수로 만들면 공허하다.
# 값: hmac_sha256("test-tenant-pipeline-secret", "pipeline_py_t2_s2").hexdigest()[:32] (python3 -I 로 1회 계산)
TENANT_2_SLOT_2_PASSWORD = "b77262beba276e68ed4c0a248e52d6e4"
EXPECTED_TENANT_2_URL = (
    f"postgresql://pipeline_py_t2_s2:{TENANT_2_SLOT_2_PASSWORD}@localhost:5432/firehub"
)


def test_fallback_env_uses_read_slot_role_credentials():
    settings = make_settings(nsjail_enabled=False)
    with patch("app.services.python_executor.subprocess.run") as mock_run:
        mock_run.return_value = make_completed_process(stdout="", returncode=0)
        execute_python("pass", None, settings, tenant_id=2, read_slot=2)

    env = mock_run.call_args[1]["env"]
    assert env["DB_URL"] == EXPECTED_TENANT_2_URL
    # P3-b2 — 테넌트 2 는 data_t2 를 받는다(테넌트 1 만 레거시 data 를 유지).
    assert env["DB_SCHEMA"] == "data_t2"
    # 공유 롤 자격증명(settings.db_user/db_password)이 스크립트로 새지 않는다.
    assert "pipeline_executor_t" not in env["DB_URL"]
    assert settings.db_password not in env["DB_URL"]


def test_nsjail_env_uses_read_slot_role_credentials():
    settings = make_settings(nsjail_enabled=True)
    with patch("app.services.python_executor.subprocess.run") as mock_run:
        mock_run.return_value = make_completed_process(stdout="ok\n", returncode=0)
        execute_python("print('ok')", None, settings, tenant_id=2, read_slot=2)

    cmd = mock_run.call_args[0][0]
    envs = dict(
        cmd[i + 1].split("=", 1)
        for i, tok in enumerate(cmd)
        if tok == "--env" and i + 1 < len(cmd)
    )
    assert envs["DB_URL"] == EXPECTED_TENANT_2_URL
    # P3-b2 — 테넌트 2 는 data_t2 를 받는다(테넌트 1 만 레거시 data 를 유지).
    assert envs["DB_SCHEMA"] == "data_t2"
    assert settings.db_password not in envs["DB_URL"]


def test_both_branches_inject_identical_db_url():
    """두 경로의 주입값이 **같음**을 직접 비교한다 — 한쪽만 고치는 회귀를 잡는다."""
    urls = {}
    for nsjail in (False, True):
        settings = make_settings(nsjail_enabled=nsjail)
        with patch("app.services.python_executor.subprocess.run") as mock_run:
            mock_run.return_value = make_completed_process(stdout="", returncode=0)
            execute_python("pass", None, settings, tenant_id=7, read_slot=2)
        if nsjail:
            cmd = mock_run.call_args[0][0]
            urls[nsjail] = dict(
                cmd[i + 1].split("=", 1)
                for i, tok in enumerate(cmd)
                if tok == "--env" and i + 1 < len(cmd)
            )["DB_URL"]
        else:
            urls[nsjail] = mock_run.call_args[1]["env"]["DB_URL"]

    assert urls[True] == urls[False]
    assert "pipeline_py_t7_s2" in urls[True]
    # 테넌트 실행 롤이 스크립트로 새지 않는다(WD-29 — 슬롯 롤만).
    assert "pipeline_executor_t" not in urls[True]


def test_missing_tenant_id_is_a_type_error():
    """이관되지 않은 호출부가 조용히 통과하지 않는다(키워드 전용 필수 인자)."""
    settings = make_settings(nsjail_enabled=False)
    with pytest.raises(TypeError):
        execute_python("pass", None, settings)


# ---------------------------------------------------------------------------
# 읽기 슬롯 필수·fail-closed (WD-29)
# ---------------------------------------------------------------------------
def test_missing_read_slot_is_a_type_error():
    """read_slot 은 키워드 전용 필수 — 누락이 기본 슬롯·테넌트 롤로 조용히 떨어지지 않는다."""
    settings = make_settings()
    with pytest.raises(TypeError):
        execute_python("pass", None, settings, tenant_id=1)  # type: ignore[call-arg]


@pytest.mark.parametrize("bad", [0, 11, True])
def test_invalid_read_slot_fails_before_running_script(bad):
    """범위 밖·bool 슬롯은 스크립트를 띄우기 전에 예외 — 테넌트 롤로 대신 실행하지 않는다."""
    settings = make_settings(nsjail_enabled=False)
    with patch("app.services.python_executor.subprocess.run") as mock_run:
        with pytest.raises(TenantResolutionError):
            execute_python("pass", None, settings, tenant_id=1, read_slot=bad)
    mock_run.assert_not_called()


def test_script_env_never_contains_tenant_executor_role():
    """스크립트에 넘기는 어떤 값에도 테넌트 실행 롤이 없다(fail-closed — 폴백 금지 고정)."""
    for nsjail in (False, True):
        settings = make_settings(nsjail_enabled=nsjail)
        with patch("app.services.python_executor.subprocess.run") as mock_run:
            mock_run.return_value = make_completed_process(stdout="", returncode=0)
            execute_python("pass", None, settings, tenant_id=3, read_slot=5)
        blob = repr(mock_run.call_args)
        assert "pipeline_py_t3_s5" in blob
        assert "pipeline_executor_t" not in blob


def test_stdout_rows_still_loaded_with_tenant_role_connection():
    """출력 적재는 executor 가 테넌트 롤 커넥션으로 한다 — 스크립트 슬롯과 무관(스펙 §4.1 마지막 항)."""
    settings = make_settings(nsjail_enabled=False)
    mock_conn = MagicMock()
    with patch("app.services.python_executor.subprocess.run") as mock_run, \
         patch("app.services.python_executor.insert_batch") as mock_insert, \
         patch("app.services.python_executor.get_connection") as mock_get_conn:
        mock_get_conn.return_value.__enter__ = MagicMock(return_value=mock_conn)
        mock_get_conn.return_value.__exit__ = MagicMock(return_value=False)
        mock_run.return_value = make_completed_process(stdout='[{"a": 1}]', returncode=0)
        result = execute_python("pass", None, settings, tenant_id=4, read_slot=1, output_table="t")
    assert result.success is True
    assert result.rows_loaded == 1
    # 적재 커넥션은 테넌트 4 의 것 — 슬롯 번호(1)가 커넥션 선택에 끼어들지 않는다.
    assert mock_get_conn.call_args[0][0] == 4
    mock_insert.assert_called_once_with(mock_conn, "t", [{"a": 1}])


# ---------------------------------------------------------------------------
# 실행별 폴더 정리(WD-408) — 성공·비 0 종료·시간 초과·실행기 예외 모두 폴더째 지운다
# ---------------------------------------------------------------------------
def _host_script_path(cmd):
    """실행 명령에서 바깥(실행기) 스크립트 경로를 꺼낸다 — nsjail 은 ``-R <경로>:/script.py``, 개발 경로는 마지막 인자다."""
    for part in cmd:
        if part.endswith(":/script.py"):
            return part[: -len(":/script.py")]
    return cmd[-1]


def _has_pair(cmd, flag, value):
    """명령에 ``flag value`` 가 연속 인자로 있는지."""
    return any(cmd[i] == flag and cmd[i + 1] == value for i in range(len(cmd) - 1))


def _tmp_bind_dir(cmd):
    """nsjail 명령의 ``-B`` 가 정확히 하나이고 ``<폴더>:/tmp`` 모양인지 확인한 뒤 그 바깥 폴더를 돌려준다.

    하나뿐이어야 한다 — 맨 ``-B /tmp``(실행기 /tmp 전체)가 섞이면 다른 실행의 스크립트를 읽고 고칠 수 있다.
    """
    binds = [cmd[i + 1] for i in range(len(cmd) - 1) if cmd[i] == "-B"]
    assert len(binds) == 1 and binds[0].endswith(":/tmp"), binds
    return binds[0][: -len(":/tmp")]


def _run_capturing_script_path(settings, side_effect, chown=None):
    """subprocess.run 을 대역으로 바꿔 실행 중에 받은 스크립트 경로·폴더 상태를 기록하고 돌린다."""
    seen = {}

    def fake_run(cmd, **kwargs):
        script_path = _host_script_path(cmd)
        seen["script_path"] = script_path
        seen["mode"] = os.stat(script_path).st_mode & 0o777
        seen["dir_mode"] = os.stat(os.path.dirname(script_path)).st_mode & 0o777
        # 샌드박스가 뜨기 **전에** 폴더 소유자가 바뀌어 있어야 한다(사후 assert_called 로는 순서를 못 본다).
        seen["chowned_before_run"] = chown.called if chown is not None else None
        with open(script_path) as f:
            seen["content"] = f.read()
        if isinstance(side_effect, BaseException):
            raise side_effect
        return side_effect

    with patch("app.services.python_executor.subprocess.run", side_effect=fake_run):
        result = execute_python("print('x')", 5, settings, tenant_id=1, read_slot=2)
    return result, seen


@pytest.mark.parametrize("nsjail_enabled", [False, True], ids=["dev", "nsjail"])
@pytest.mark.parametrize(
    "outcome",
    [
        make_completed_process(stdout="ok\n", returncode=0),
        make_completed_process(stderr="NameError", returncode=1),
        subprocess.TimeoutExpired(cmd=["python3"], timeout=5),
        RuntimeError("boom"),
    ],
    ids=["success", "nonzero", "timeout", "executor-error"],
)
def test_run_dir_removed_after_success_failure_and_timeout(outcome, nsjail_enabled, fake_chown):
    """nsjail 경로(chown 이 끼는 경로)와 개발 경로 모두, 실행이 어떻게 끝나든 실행별 폴더를 지운다.

    nsjail 경로는 샌드박스를 띄우기 **전에** 폴더를 샌드박스 uid/gid(65534) 소유로 넘기고, 개발 경로는 chown 하지 않는다(맥 개발
    환경에서 chown 이 실패한다).
    """
    result, seen = _run_capturing_script_path(make_settings(nsjail_enabled=nsjail_enabled), outcome, chown=fake_chown)

    assert seen["content"] == "print('x')"
    run_dir = os.path.dirname(seen["script_path"])
    assert not os.path.exists(run_dir), "실행이 어떻게 끝나든 실행별 폴더는 남지 않는다"
    if nsjail_enabled:
        assert seen["chowned_before_run"] is True
        fake_chown.assert_called_once_with(run_dir, 65534, 65534)
        # 폴더는 mkdtemp 가 만든 0700 그대로다 — 샌드박스 uid 말고는 다른 실행·사용자가 들어오지 못하게 하는 불변식이고,
        # 누가 폴더를 넓게(예: 0o755/0o777) 바꾸는 회귀를 잡는다.
        assert seen["dir_mode"] == 0o700
    else:
        fake_chown.assert_not_called()
    if isinstance(outcome, BaseException):
        assert result.success is False


def test_script_written_inside_private_run_dir():
    _, first = _run_capturing_script_path(make_settings(nsjail_enabled=False), make_completed_process())
    _, second = _run_capturing_script_path(make_settings(nsjail_enabled=False), make_completed_process())

    for seen in (first, second):
        run_dir = os.path.dirname(seen["script_path"])
        assert os.path.basename(run_dir).startswith("pyrun-")
        assert seen["script_path"] == os.path.join(run_dir, "script.py")
        # 샌드박스 uid(65534)가 읽을 수 있어야 한다 — NamedTemporaryFile 의 0600 root 파일은 못 읽는다.
        assert seen["mode"] == 0o644
    assert os.path.dirname(first["script_path"]) != os.path.dirname(second["script_path"])


# ---------------------------------------------------------------------------
# test_execution_time_measurement
# ---------------------------------------------------------------------------
def test_execution_time_measurement():
    settings = make_settings(nsjail_enabled=False)
    with patch("app.services.python_executor.subprocess.run") as mock_run:
        mock_run.return_value = make_completed_process(stdout="", returncode=0)
        result = execute_python("pass", None, settings, tenant_id=1, read_slot=2)

    assert result.execution_time_ms >= 0


# ---------------------------------------------------------------------------
# test_stdout_json_auto_insert
# ---------------------------------------------------------------------------
def test_stdout_json_auto_insert():
    settings = make_settings(nsjail_enabled=False)
    rows_json = '[{"name": "Alice", "age": 30}, {"name": "Bob", "age": 25}]'

    mock_conn = MagicMock()

    with patch("app.services.python_executor.subprocess.run") as mock_run, \
         patch("app.services.python_executor.insert_batch") as mock_insert, \
         patch("app.services.python_executor.get_connection") as mock_get_conn:
        mock_get_conn.return_value.__enter__ = MagicMock(return_value=mock_conn)
        mock_get_conn.return_value.__exit__ = MagicMock(return_value=False)
        mock_run.return_value = make_completed_process(stdout=rows_json, returncode=0)
        result = execute_python(
            "print('[...]')", None, settings, tenant_id=1, read_slot=2,
            output_table="my_table",
        )

    assert result.success is True
    assert result.rows_loaded == 2
    mock_conn.commit.assert_called_once()
    mock_insert.assert_called_once_with(mock_conn, "my_table", [{"name": "Alice", "age": 30}, {"name": "Bob", "age": 25}])


# ---------------------------------------------------------------------------
# WD-210: 출력 테이블이 있는데 stdout 이 JSON 이 아니면 적재 전에 실패한다
#   (예전엔 조용히 "완료·0행" — REPLACE 출력이 옛 데이터로 남아 갱신이 멈춘 걸 알 수 없었다)
# ---------------------------------------------------------------------------

# API 로컬 경로(LocalPythonOutputLoaderTest)와 같은 입력·같은 기대 문구 — 두 경로 문구 패리티의 증거다.
DEBUG_THEN_JSON_STDOUT = 'start\n[{"a": 1}]\n'
DEBUG_THEN_JSON_MESSAGE = (
    "스크립트 출력이 JSON 이 아니어서 결과를 적재하지 않았습니다. 출력 데이터셋을 쓰는 스크립트는 결과 JSON 한 번만 print 해야"
    ' 합니다(디버그 출력은 sys.stderr 로). 출력 앞부분: "start\n[{"a": 1}]"'
)


def _run_with_output_table(stdout, stderr=""):
    """출력 테이블이 있는 실행 — 적재 커넥션·insert_batch 호출 여부를 함께 돌려준다."""
    settings = make_settings(nsjail_enabled=False)
    with patch("app.services.python_executor.subprocess.run") as mock_run, \
         patch("app.services.python_executor.insert_batch") as mock_insert, \
         patch("app.services.python_executor.get_connection") as mock_get_conn:
        mock_get_conn.return_value.__enter__ = MagicMock(return_value=MagicMock())
        mock_get_conn.return_value.__exit__ = MagicMock(return_value=False)
        mock_run.return_value = make_completed_process(stdout=stdout, stderr=stderr, returncode=0)
        result = execute_python(
            "...", None, settings, tenant_id=1, read_slot=2, output_table="my_table",
        )
    return result, mock_get_conn, mock_insert


def test_stdout_debug_print_then_json_fails_before_insert():
    result, mock_get_conn, mock_insert = _run_with_output_table(
        DEBUG_THEN_JSON_STDOUT, stderr="dbg\n"
    )

    assert result.success is False
    assert result.error == DEBUG_THEN_JSON_MESSAGE
    assert result.rows_loaded == 0
    # 반환 형식은 적재 실패 경로와 같다 — 로그는 stderr, exit_code 는 스크립트의 0
    assert result.exit_code == 0
    assert result.output == "dbg\n"
    # 적재 전 실패 — 출력 테이블(REPLACE 면 임시 테이블)에 손대지 않는다
    mock_get_conn.assert_not_called()
    mock_insert.assert_not_called()


def test_stdout_plain_text_fails():
    result, mock_get_conn, mock_insert = _run_with_output_table("plain text output\n")

    assert result.success is False
    assert result.error.startswith("스크립트 출력이 JSON 이 아니어서 결과를 적재하지 않았습니다.")
    assert result.error.endswith('출력 앞부분: "plain text output"')
    mock_insert.assert_not_called()


def test_stdout_not_json_excerpt_is_capped_at_100_chars():
    # 비밀이 섞일 수 있어 앞부분 인용은 100자(코드 포인트) 이하 — 말줄임표 없음
    secret_tail = "SECRET_AFTER_100"
    stdout = "가" * 100 + secret_tail
    result, _, _ = _run_with_output_table(stdout)

    assert result.success is False
    assert result.error.endswith('출력 앞부분: "' + "가" * 100 + '"')
    assert secret_tail not in result.error


@pytest.mark.parametrize("stdout", ["", "   \n\t\n"])
def test_stdout_empty_or_whitespace_keeps_zero_rows(stdout):
    result, mock_get_conn, mock_insert = _run_with_output_table(stdout)

    assert result.success is True
    assert result.rows_loaded == 0
    assert result.error is None
    mock_insert.assert_not_called()


@pytest.mark.parametrize("stdout", ["[]", "[1, 2]", '{"a": 1}', "123"])
def test_stdout_valid_json_but_not_rows_keeps_zero_rows(stdout):
    # JSON 으로는 읽힌다 — 기존 규칙대로 0행(오류 아님). [] 는 정상 빈 결과(#685 가 지키는 경우)다.
    result, _, mock_insert = _run_with_output_table(stdout)

    assert result.success is True
    assert result.rows_loaded == 0
    mock_insert.assert_not_called()


def test_stdout_not_json_without_output_table_is_not_checked():
    # 출력 테이블이 없는 스텝은 지금처럼 stdout 을 검사하지 않는다
    settings = make_settings(nsjail_enabled=False)
    with patch("app.services.python_executor.subprocess.run") as mock_run:
        mock_run.return_value = make_completed_process(stdout=DEBUG_THEN_JSON_STDOUT, returncode=0)
        result = execute_python("...", None, settings, tenant_id=1, read_slot=2)

    assert result.success is True
    assert result.error is None
    assert result.output == DEBUG_THEN_JSON_STDOUT


# ---------------------------------------------------------------------------
# test_stdout_json_no_output_table
# ---------------------------------------------------------------------------
def test_stdout_json_no_output_table():
    settings = make_settings(nsjail_enabled=False)
    rows_json = '[{"id": 1}]'

    with patch("app.services.python_executor.subprocess.run") as mock_run:
        mock_run.return_value = make_completed_process(stdout=rows_json, returncode=0)
        result = execute_python("print('[...]')", None, settings, tenant_id=1, read_slot=2)

    assert result.success is True
    assert result.rows_loaded == 0
    # Legacy behavior: stdout included in output
    assert rows_json in result.output


# ---------------------------------------------------------------------------
# test_stderr_as_execution_log
# ---------------------------------------------------------------------------
def test_stderr_as_execution_log():
    settings = make_settings(nsjail_enabled=False)
    mock_conn = MagicMock()

    with patch("app.services.python_executor.subprocess.run") as mock_run, \
         patch("app.services.python_executor.insert_batch"), \
         patch("app.services.python_executor.get_connection") as mock_get_conn:
        mock_get_conn.return_value.__enter__ = MagicMock(return_value=mock_conn)
        mock_get_conn.return_value.__exit__ = MagicMock(return_value=False)
        mock_run.return_value = make_completed_process(
            stdout='[{"x": 1}]',
            stderr="step 1 done\nstep 2 done\n",
            returncode=0,
        )
        result = execute_python(
            "...", None, settings, tenant_id=1, read_slot=2,
            output_table="tbl",
        )

    assert result.success is True
    assert "step 1 done" in result.output
    assert "step 2 done" in result.output


# ---------------------------------------------------------------------------
# test_script_failure_no_insert
# ---------------------------------------------------------------------------
def test_script_failure_no_insert():
    settings = make_settings(nsjail_enabled=False)

    with patch("app.services.python_executor.subprocess.run") as mock_run:
        mock_run.return_value = make_completed_process(
            stdout='[{"id": 1}]',
            stderr="SyntaxError: invalid syntax",
            returncode=1,
        )
        result = execute_python(
            "bad script", None, settings, tenant_id=1, read_slot=2,
            output_table="tbl",
        )

    assert result.success is False
    assert result.rows_loaded == 0


# ---------------------------------------------------------------------------
# test_insert_failure_returns_error
# ---------------------------------------------------------------------------
def test_insert_failure_returns_error():
    settings = make_settings(nsjail_enabled=False)

    with patch("app.services.python_executor.subprocess.run") as mock_run, \
         patch("app.services.python_executor.insert_batch", side_effect=Exception("relation does not exist")), \
         patch("app.services.python_executor.get_connection") as mock_get_conn:
        mock_conn = MagicMock()
        mock_get_conn.return_value.__enter__ = MagicMock(return_value=mock_conn)
        mock_get_conn.return_value.__exit__ = MagicMock(return_value=False)
        mock_run.return_value = make_completed_process(
            stdout='[{"id": 1}]',
            stderr="",
            returncode=0,
        )
        result = execute_python(
            "...", None, settings, tenant_id=1, read_slot=2,
            output_table="nonexistent_table",
        )

    assert result.success is False
    assert result.rows_loaded == 0
    # WD-211: 원문(내부 테이블·SQL)은 사용자 문구에 싣지 않는다 — 일반 한국어 안내만. PG 오류가 아니라 pg_error 도 없다.
    assert result.error == LOAD_FAILED_TEXT
    assert "relation does not exist" not in result.error
    assert result.pg_error is None


# ---------------------------------------------------------------------------
# WD-211: 적재 실패 분류 — API 로컬 경로(LocalPythonOutputLoaderTest)와 같은 입력·같은 문구
# ---------------------------------------------------------------------------
LOAD_FAILED_TEXT = "출력 결과를 데이터셋에 적재하지 못했습니다. 출력 JSON 의 키와 값 형식을 확인해 주세요."


def _run_load(stdout, column_type_map=None, insert_side_effect=None):
    """출력 테이블·컬럼 타입 맵이 있는 실행 — 적재 커넥션·insert_batch 목을 함께 돌려준다."""
    settings = make_settings(nsjail_enabled=False)
    with patch("app.services.python_executor.subprocess.run") as mock_run, \
         patch("app.services.python_executor.insert_batch", side_effect=insert_side_effect) as mock_insert, \
         patch("app.services.python_executor.get_connection") as mock_get_conn:
        mock_get_conn.return_value.__enter__ = MagicMock(return_value=MagicMock())
        mock_get_conn.return_value.__exit__ = MagicMock(return_value=False)
        mock_run.return_value = make_completed_process(stdout=stdout, stderr="log\n", returncode=0)
        result = execute_python(
            "...", None, settings, tenant_id=1, read_slot=2, output_table="r8_out",
            column_type_map=column_type_map,
        )
    return result, mock_get_conn, mock_insert


def test_unknown_column_key_fails_before_insert():
    result, mock_get_conn, mock_insert = _run_load('[{"nocol": "a"}]', {"name": "TEXT"})

    assert result.success is False
    assert result.error == "출력 JSON 의 'nocol' 가 출력 데이터셋 컬럼에 없습니다. 데이터셋 컬럼 이름과 같은 키로 출력해 주세요."
    assert result.output == "log\n"
    assert result.exit_code == 0
    mock_get_conn.assert_not_called()
    mock_insert.assert_not_called()


def test_system_column_key_is_left_to_db():
    # 시스템 컬럼은 데이터셋 컬럼 목록에 없지만 물리 테이블에 있다 — 예전처럼 적재를 시도한다(동작 불변)
    result, _, mock_insert = _run_load('[{"name": "a", "created_at": "2026-01-01"}]', {"name": "TEXT"})

    assert result.success is True
    assert result.rows_loaded == 1
    mock_insert.assert_called_once()


def test_row_not_object_reports_row_number():
    result, mock_get_conn, mock_insert = _run_load('[{"name": "a"}, {"name": "b"}, 3]', {"name": "TEXT"})

    assert result.success is False
    assert result.error == "출력 JSON 의 3번째 행이 객체가 아닙니다. 모든 행을 컬럼 이름을 키로 하는 JSON 객체로 출력해 주세요."
    mock_get_conn.assert_not_called()
    mock_insert.assert_not_called()


def test_row_missing_first_row_key_reports_row_number():
    result, mock_get_conn, mock_insert = _run_load('[{"name": "a"}, {"other": "b"}]', {"name": "TEXT", "other": "TEXT"})

    assert result.success is False
    assert result.error == "출력 JSON 의 2번째 행에 첫 행의 키 'name' 가 없습니다. 모든 행에 첫 행과 같은 키를 넣어 주세요."
    mock_get_conn.assert_not_called()
    mock_insert.assert_not_called()


# ---------------------------------------------------------------------------
# WD-431: 값 변환 실패 — API 로컬 경로(LocalPythonOutputLoaderTest)와 같은 입력·같은 문구(러너 접두 없이)
# ---------------------------------------------------------------------------


def test_value_conversion_failure_reports_row_key_value_type():
    # 예전엔 경고 로그 후 None 으로 넣고 「완료」였다 — 이제 적재 전에(커넥션도 열지 않고) 실패한다
    result, mock_get_conn, mock_insert = _run_load(
        '[{"name": "a", "n": 1}, {"name": "b", "n": 2}, {"name": "c", "n": "not-a-number"}]',
        {"name": "TEXT", "n": "INTEGER"},
    )

    assert result.success is False
    assert result.rows_loaded == 0
    assert result.error == (
        "출력 JSON 의 3번째 행 'n' 값 'not-a-number' 을(를) 정수(INTEGER)로 변환할 수 없습니다."
        " 출력 데이터셋 컬럼 타입에 맞는 값으로 출력해 주세요."
    )
    assert result.pg_error is None
    mock_get_conn.assert_not_called()
    mock_insert.assert_not_called()


@pytest.mark.parametrize(
    "dtype, json_value, expected",
    [
        ("DECIMAL", '"1,2x"', "'v' 값 '1,2x' 을(를) 소수(DECIMAL)로 변환할 수 없습니다."),
        ("DATE", '"2024-13-40"', "'v' 값 '2024-13-40' 을(를) 날짜(DATE)로 변환할 수 없습니다."),
        ("TIMESTAMP", '"nope"', "'v' 값 'nope' 을(를) 날짜시간(TIMESTAMP)으로 변환할 수 없습니다."),
        ("BOOLEAN", '"예"', "'v' 값 '예' 을(를) 참/거짓(BOOLEAN)으로 변환할 수 없습니다(허용 값: true/false/1/0/yes/no)."),
        ("BOOLEAN", '"Y"', "'v' 값 'Y' 을(를) 참/거짓(BOOLEAN)으로"),
        # 값은 str() 표기 — API 로컬 경로의 pyStr 과 같은 글자
        ("INTEGER", "true", "'v' 값 'True' 을(를) 정수(INTEGER)로"),
        ("INTEGER", "1.5", "'v' 값 '1.5' 을(를) 정수(INTEGER)로"),
    ],
)
def test_value_conversion_failure_messages_per_type(dtype, json_value, expected):
    result, mock_get_conn, _ = _run_load('[{"v": ' + json_value + "}]", {"v": dtype})
    assert result.success is False
    assert result.error.startswith("출력 JSON 의 1번째 행 ")
    assert expected in result.error
    mock_get_conn.assert_not_called()


def test_value_conversion_failure_truncates_long_value():
    long_value = "x" * 49 + "\U0001F600" + "TAIL"
    result, _, _ = _run_load(json.dumps([{"v": long_value}]), {"v": "INTEGER"})
    assert "'v' 값 '" + "x" * 49 + "\U0001F600…' 을(를)" in result.error
    assert "TAIL" not in result.error


@pytest.mark.parametrize(
    "raw, expected",
    [("yes", True), (" TRUE ", True), ("1", True), (1, True), (1.0, True), (True, True),
     ("false", False), ("0", False), ("no", False), (0, False), (False, False), ("", None), ("  ", None)],
)
def test_boolean_accepts_only_known_tokens_and_blank_is_null(raw, expected):
    # WD-431: true/false/1/0/yes/no·JSON 불리언·숫자 1/0 만 받고, 빈 문자열은 None(예전엔 거짓)
    assert _convert_single(raw, "BOOLEAN") is expected


@pytest.mark.parametrize("raw", ["Y", "N", "예", "아니오", 2, -1, float("nan")])
def test_boolean_unknown_values_fail_instead_of_false(raw):
    with pytest.raises(ConversionFailed):
        _convert_single(raw, "BOOLEAN")


@pytest.mark.parametrize("dtype", ["INTEGER", "DECIMAL", "NUMERIC", "DATE", "TIMESTAMP", "DATETIME"])
def test_blank_string_is_null_for_non_text_types(dtype):
    assert _convert_single("", dtype) is None
    assert _convert_single("  ", dtype) is None
    assert _convert_single("", "TEXT") == ""


@pytest.mark.parametrize("raw, expected", [(3.0, 3), ("3.0", 3), ("1e2", 100), (1e2, 100), (" +1_000 ", 1000), (7, 7)])
def test_integer_accepts_integral_float_forms(raw, expected):
    # WD-431: 정수 값인 실수 표기는 받는다(API 로컬 경로 ValueConversion.toLongStrict 와 같은 규칙)
    assert _convert_single(raw, "INTEGER") == expected


@pytest.mark.parametrize("raw", [1.5, "1.5", 1e20, "1e20", 2 ** 63, "9223372036854775808", True, "1e999999999", "nan"])
def test_integer_rejects_fraction_and_out_of_range(raw):
    with pytest.raises(ConversionFailed):
        _convert_single(raw, "INTEGER")


def test_json_null_stays_null_not_an_error():
    result, _, mock_insert = _run_load('[{"n": null, "b": null}]', {"n": "INTEGER", "b": "BOOLEAN"})
    assert result.success is True
    assert mock_insert.call_args[0][2] == [{"n": None, "b": None}]


class _FakePgError(Exception):
    """psycopg2 오류 흉내 — pg_error_info 는 pgcode·diag 속성으로만 판별한다."""

    def __init__(self, pgcode, column=None, message=None):
        super().__init__(f'ERROR: {message}\nLINE 1: INSERT INTO "r8_out" ...')
        self.pgcode = pgcode
        self.diag = MagicMock(
            message_primary=message, message_detail=None, message_hint=None, column_name=column,
        )


def test_db_data_error_sends_pg_error_without_raw_sql():
    exc = _FakePgError("22001", column="name", message="value too long for type character varying(3)")
    result, _, _ = _run_load('[{"name": "abcdef"}]', {"name": "TEXT"}, insert_side_effect=exc)

    assert result.success is False
    # 사용자 문구는 일반 안내 — 데이터 오류 사유 문장은 API 가 pg_error 로 만든다(DataExceptionTranslator)
    assert result.error == LOAD_FAILED_TEXT
    assert "INSERT INTO" not in result.error
    assert result.pg_error is not None
    assert result.pg_error.sql_state == "22001"
    assert result.pg_error.column == "name"


def test_unexpected_error_keeps_traceback_out_of_user_message():
    settings = make_settings(nsjail_enabled=False)
    with patch("app.services.python_executor.subprocess.run", side_effect=OSError("disk exploded")):
        result = execute_python("print(1)", None, settings, tenant_id=1, read_slot=2)

    assert result.success is False
    assert result.exit_code == -1
    assert result.error == "스크립트 실행 중 예기치 못한 오류가 발생했습니다. 잠시 후 다시 실행하고, 계속되면 관리자에게 문의해 주세요."
    assert "Traceback" not in result.error
    # traceback 은 실행 로그 칸(output)과 로그에만 남는다
    assert "Traceback" in result.output
    assert "disk exploded" in result.output


# ---------------------------------------------------------------------------
# test_parse_stdout_json_unit
# ---------------------------------------------------------------------------
def test_parse_stdout_json_valid():
    data = _parse_stdout_json('[{"a": 1}, {"a": 2}]')
    assert data == [{"a": 1}, {"a": 2}]


def test_parse_stdout_json_empty_string():
    assert _parse_stdout_json("") is None


def test_parse_stdout_json_plain_text():
    with pytest.raises(StdoutNotJsonError):
        _parse_stdout_json("hello world")


def test_parse_stdout_json_trailing_content_is_not_json():
    # json.loads 의 "Extra data" — 마지막 줄만 시도하는 식의 추측 파싱은 하지 않는다
    with pytest.raises(StdoutNotJsonError):
        _parse_stdout_json('[{"a": 1}]\ndone')


def test_parse_stdout_json_empty_array():
    assert _parse_stdout_json("[]") is None


def test_parse_stdout_json_non_dict_items():
    assert _parse_stdout_json('[1, 2, 3]') is None


# ---------------------------------------------------------------------------
# nsjail 진단 로그 보존
#   nsjail 의 FATAL 은 "무엇이 실패했는지"만 말하고 "왜"는 바로 앞 ERROR 줄에 있다.
#   --really_quiet 는 그 ERROR 를 지워 스텝 오류 메시지를 무의미하게 만든다.
# ---------------------------------------------------------------------------
def test_nsjail_keeps_error_level_diagnostics():
    settings = make_settings(nsjail_enabled=True)
    with patch("app.services.python_executor.subprocess.run") as mock_run:
        mock_run.return_value = make_completed_process(stdout="ok\n", returncode=0)
        execute_python("print('ok')", None, settings, tenant_id=1, read_slot=2)

    cmd = mock_run.call_args[0][0]
    assert "--really_quiet" not in cmd, (
        "--really_quiet 는 FATAL 만 남겨 실패 원인(ERROR 줄)이 잘린다 — "
        "AppArmor 가 mount 를 막은 장애에서 로그만으로 원인을 알 수 없었다"
    )
    assert "--quiet" in cmd


# ---------------------------------------------------------------------------
# 비루트 매핑·실행별 /tmp(WD-408)
#   매핑이 없으면 샌드박스 uid 0 이 컨테이너 root 로 매핑된다. 한 값(-u 65534)만 주면 바깥이 호출자 uid(0)라 그대로다.
#   -B /tmp 는 실행기 /tmp 전체(동시에 도는 다른 테넌트 스크립트 포함)를 쓰기 가능으로 붙였다.
# ---------------------------------------------------------------------------
def _nsjail_cmd(settings=None):
    settings = settings or make_settings(nsjail_enabled=True)
    with patch("app.services.python_executor.subprocess.run") as mock_run:
        mock_run.return_value = make_completed_process(stdout="ok\n", returncode=0)
        execute_python("print('ok')", None, settings, tenant_id=1, read_slot=2)
    return mock_run.call_args[0][0]


def test_nsjail_cmd_maps_sandbox_to_unprivileged_uid():
    cmd = _nsjail_cmd()
    assert _has_pair(cmd, "-u", "65534:65534:1")
    assert _has_pair(cmd, "-g", "65534:65534:1")


def test_nsjail_cmd_binds_only_run_dir_as_tmp():
    cmd = _nsjail_cmd()
    run_dir = _tmp_bind_dir(cmd)
    assert os.path.basename(run_dir).startswith("pyrun-")
    # /script.py 는 그 폴더 안 파일을 읽기 전용으로 붙인다.
    assert _has_pair(cmd, "-R", f"{os.path.join(run_dir, 'script.py')}:/script.py")


def test_run_dir_removal_failure_is_logged_not_raised(caplog):
    """폴더 정리 실패는 실행 결과를 바꾸지 않지만 경로를 담은 WARN 로그를 남긴다(테넌트 스크립트·데이터가 남았다는 신호)."""
    seen = {}

    def fake_run(cmd, **kwargs):
        seen["run_dir"] = os.path.dirname(_host_script_path(cmd))
        return make_completed_process(stdout="ok\n")

    with patch("app.services.python_executor.subprocess.run", side_effect=fake_run), \
         patch("app.services.python_executor.shutil.rmtree", side_effect=PermissionError("denied")), \
         caplog.at_level("WARNING", logger="app.services.python_executor"):
        result = execute_python("print('SECRET-SCRIPT-BODY')", None, make_settings(), tenant_id=1, read_slot=2)

    assert result.success is True
    warnings = [r.getMessage() for r in caplog.records if r.levelname == "WARNING"]
    assert any(seen["run_dir"] in m for m in warnings)
    assert not any("SECRET-SCRIPT-BODY" in m for m in warnings)
    # 실제 폴더는 대역 때문에 남았으므로 테스트가 치운다.
    shutil.rmtree(seen["run_dir"], ignore_errors=True)


# ===========================================================================
# Tibero 테넌트 경로(WD-78) — 조회 도우미 환경·원격 적재·토큰 비노출
# ===========================================================================
import json as _json
import os
from datetime import datetime as _dt, timezone as _tz

import httpx as _httpx

from app.db.backends.remote import RemoteLoadError, RemoteLoader
from app.services.python_executor import execute_python_tibero

SCRIPT_TOKEN = "v1.4.3.7.1999999999.U0lHTkFUVVJFLWZvci10ZXN0LW9ubHktbm90LXJlYWw"
DBX_URL = "http://db-executor:8090"
STG = "STG_0123456789ABCDEF01234567"


def make_tibero_settings(**kwargs):
    values = dict(db_executor_base_url=DBX_URL, db_executor_load_token="load-token-for-test")
    values.update(kwargs)
    return make_settings(**values)


class _RecordingLoader:
    """RemoteLoader 대역 — 받은 인자를 기록하고 행 수를 돌려준다."""

    def __init__(self, error=None):
        self.calls = []
        self.drop_keys = []
        self._error = error

    def load(self, tenant_id, table, rows, column_type_map, timeout_seconds=None, drop_keys=frozenset()):
        self.calls.append((tenant_id, table, rows, column_type_map))
        self.drop_keys.append(drop_keys)
        if self._error:
            raise self._error
        return len(rows)


def _run_tibero(settings, *, stdout="", stderr="", returncode=0, loader=None, **kwargs):
    """PG 전용 경로(슬롯 롤 URL·스키마·PG 연결)를 부르면 실패하도록 막고 Tibero 경로를 돌린다."""
    loader = loader or _RecordingLoader()
    with patch("app.services.python_executor.subprocess.run") as mock_run, \
         patch("app.services.python_executor.resolve_read_db_url", side_effect=AssertionError("PG 슬롯 롤 금지")), \
         patch("app.services.python_executor.resolve_schema", side_effect=AssertionError("PG 스키마 금지")), \
         patch("app.services.python_executor.get_connection", side_effect=AssertionError("PG 연결 금지")):
        mock_run.return_value = make_completed_process(stdout=stdout, stderr=stderr, returncode=returncode)
        args = dict(tenant_id=4, script_token=SCRIPT_TOKEN, loader=loader)
        args.update(kwargs)
        result = execute_python_tibero("print(1)", None, settings, **args)
    return result, mock_run, loader


def test_tibero_fallback_env_has_helper_settings_and_no_pg_url():
    result, mock_run, _ = _run_tibero(make_tibero_settings(nsjail_enabled=False))
    assert result.success is True
    env = mock_run.call_args.kwargs["env"]
    assert env["GENIA_DB_URL"] == DBX_URL
    assert env["GENIA_DB_TOKEN"] == SCRIPT_TOKEN
    # 패키지 경로 + 리포의 sandbox(도우미 위치) — nsjail 없는 개발 실행기에서도 genia_db 를 import 할 수 있다(M7).
    paths = env["PYTHONPATH"].split(os.pathsep)
    assert paths[0] == "/usr/local/lib/python3.11/dist-packages"
    assert any(os.path.isfile(os.path.join(p, "genia_db.py")) for p in paths)
    assert "DB_URL" not in env and "DB_SCHEMA" not in env
    # 스크립트가 받는 비밀은 토큰 하나뿐 — 적재 토큰·실행기 토큰은 넘기지 않는다.
    assert "load-token-for-test" not in repr(env)
    assert "changeme" not in repr(env)


def test_tibero_nsjail_passes_token_by_name_only_never_in_argv():
    result, mock_run, _ = _run_tibero(make_tibero_settings(nsjail_enabled=True))
    assert result.success is True
    cmd = mock_run.call_args.args[0]
    assert cmd[0] == "/usr/sbin/nsjail"
    # 토큰 값은 명령줄(ps·TimeoutExpired 문구)에 나타나지 않는다 — nsjail 이 자기 환경에서 이름으로 가져간다.
    assert all(SCRIPT_TOKEN not in part for part in cmd)
    env_values = [cmd[i + 1] for i, part in enumerate(cmd) if part == "--env"]
    assert "GENIA_DB_TOKEN" in env_values
    assert f"GENIA_DB_URL={DBX_URL}" in env_values
    assert "PYTHONPATH=/opt/python-packages" in env_values
    assert not any(v.startswith("DB_URL=") or v.startswith("DB_SCHEMA=") for v in env_values)
    assert mock_run.call_args.kwargs["env"]["GENIA_DB_TOKEN"] == SCRIPT_TOKEN
    # 샌드박스 바닥 설정(proc 차단·패키지 경로)은 PG 경로와 같다.
    assert "--disable_proc" in cmd
    assert "/usr/local/lib/python3.11/dist-packages:/opt/python-packages" in cmd


def test_tibero_nsjail_base_args_match_pg_path():
    """두 경로가 같은 샌드박스 바닥(--env 앞까지)을 쓴다 — 한쪽만 바뀌면 격리 수준이 갈린다."""
    settings = make_tibero_settings(nsjail_enabled=True)
    _, tibero_run, _ = _run_tibero(settings)
    with patch("app.services.python_executor.subprocess.run") as pg_run:
        pg_run.return_value = make_completed_process(stdout="", returncode=0)
        execute_python("print(1)", None, settings, tenant_id=4, read_slot=3)
    t_cmd, p_cmd = tibero_run.call_args.args[0], pg_run.call_args.args[0]

    # 실행별 폴더(/tmp 로 붙는 폴더·그 안 스크립트)는 실행마다 다르므로 그 마운트 인자는 빼고 비교한다.
    def strip(c):
        return [x for x in c[: c.index("--env")] if not x.endswith((":/script.py", ":/tmp"))]

    assert strip(t_cmd) == strip(p_cmd)


_BLAS_THREAD_ENV = ["OPENBLAS_NUM_THREADS=4", "OMP_NUM_THREADS=4", "MKL_NUM_THREADS=4"]


def test_both_nsjail_paths_cap_native_thread_pools():
    """BLAS·OpenMP 스레드 상한은 PG·Tibero 두 경로 모두에 들어간다 — 비루트라 nproc 64 가 걸려 다코어 노드에서 import 가 죽는다."""
    pg_cmd = _nsjail_cmd()
    _, tibero_run, _ = _run_tibero(make_tibero_settings(nsjail_enabled=True))
    for cmd in (pg_cmd, tibero_run.call_args.args[0]):
        env_values = [cmd[i + 1] for i, part in enumerate(cmd) if part == "--env"]
        for value in _BLAS_THREAD_ENV:
            assert value in env_values


def test_tibero_nsjail_run_dir_owned_by_sandbox_and_mapped(fake_chown):
    """Tibero 경로도 실행별 폴더를 65534 소유로 넘기고, 같은 비루트 매핑·실행별 /tmp 를 쓴다(WD-408 리뷰 I-1).

    소유자 결정은 공용 실행 뼈대(``_run_script``)에 있지만, PG 테스트만 있으면 Tibero 경로가 그 뼈대를 벗어나도 녹색이다. 그러면
    폴더가 root 소유 0700 으로 남아 nsjail 이 /script.py 를 붙이지 못하고(매핑된 65534 가 폴더를 못 연다) 운영의 모든 Tibero
    Python 스텝이 실패한다.
    """
    result, mock_run, _ = _run_tibero(make_tibero_settings(nsjail_enabled=True))
    assert result.success is True
    cmd = mock_run.call_args.args[0]

    run_dir = _tmp_bind_dir(cmd)
    assert os.path.basename(run_dir).startswith("pyrun-")
    fake_chown.assert_called_once_with(run_dir, 65534, 65534)
    assert _has_pair(cmd, "-u", "65534:65534:1") and _has_pair(cmd, "-g", "65534:65534:1")
    assert _has_pair(cmd, "-R", f"{os.path.join(run_dir, 'script.py')}:/script.py")
    assert not os.path.exists(run_dir)


@pytest.mark.parametrize("token", [None, "", "   "])
def test_tibero_missing_token_still_fails_closed_when_called_directly(token):
    """토큰 누락 문구는 라우터가 낸다(라우트 테스트가 고정). 서비스를 직접 불러도 빈 토큰은 테넌트 대조에서 막혀 스크립트를 띄우지 않는다."""
    result, mock_run, loader = _run_tibero(make_tibero_settings(), script_token=token, output_table=STG)
    assert result.success is False
    assert result.error == "스크립트 토큰의 테넌트가 요청 테넌트와 다릅니다"
    assert result.exit_code == 1 and result.execution_time_ms == 0
    mock_run.assert_not_called()
    assert loader.calls == []


def test_tibero_missing_db_executor_url_fails_before_running():
    result, mock_run, _ = _run_tibero(make_tibero_settings(db_executor_base_url=""))
    assert result.success is False
    # 코드리뷰 3: 응답 오류는 api 스텝 오류로 사용자에게 보이므로 환경 변수 이름을 싣지 않는다(서버 WARN 로그에만).
    assert result.error == "Python 조회 설정이 준비되지 않아 실행할 수 없습니다. 운영자에게 문의하세요."
    assert "DB_EXECUTOR_" not in result.error
    mock_run.assert_not_called()


def test_tibero_non_staging_output_table_is_refused_by_remote_loader_when_called_directly():
    """스테이징 이름 검사는 라우터가 띄우기 전에 한다(라우트 테스트가 고정). 서비스를 직접 불러도 원격 적재기가 다시 거부해 아무것도 보내지 않는다."""
    sent = []

    def handler(request):
        sent.append(request)
        return _httpx.Response(200, json={"success": True, "rows_loaded": 1})

    settings = make_tibero_settings()
    loader = RemoteLoader(settings, client=_httpx.Client(transport=_httpx.MockTransport(handler)))
    result, _, _ = _run_tibero(settings, stdout='[{"a": 1}]', output_table="orders", loader=loader)
    assert result.success is False
    # 원문(이름 거부 사유)은 로그에만 — 사용자 문구는 PG 와 같은 일반 적재 실패 안내다(WD-211).
    assert result.error == "출력 결과를 데이터셋에 적재하지 못했습니다. 출력 JSON 의 키와 값 형식을 확인해 주세요."
    assert sent == []


def test_tibero_stdout_rows_go_to_remote_loader_with_staging_table():
    ctm = {"a": "INTEGER"}
    result, _, loader = _run_tibero(
        make_tibero_settings(),
        stdout='[{"a": "1"}, {"a": "2"}]',
        stderr="log line\n",
        output_table=STG,
        column_type_map=ctm,
    )
    assert result.success is True
    assert result.rows_loaded == 2
    assert result.output == "log line\n"  # 출력 테이블이 있으면 stderr 만(PG 와 같은 규칙)
    # 타입 변환은 적재 전에 _run_script 가 모든 행에 한다(WD-431) — 적재기는 변환된 행을 받아 시스템 키 빼기·직렬화만 한다.
    assert loader.calls == [(4, STG, [{"a": 1}, {"a": 2}], ctm)]
    assert loader.drop_keys == [frozenset({"id", "import_id", "created_at", "_updated_at"})]


def test_tibero_system_column_keys_are_not_converted():
    # WD-431: 적재기가 빼는 시스템 컬럼 키는 적재하지 않으므로 변환·검증하지 않는다 — 타입 맵에 있어도 실패하지 않는다
    ctm = {"a": "INTEGER", "id": "INTEGER"}
    result, _, loader = _run_tibero(
        make_tibero_settings(),
        stdout='[{"a": "1", "id": "not-a-number"}]',
        output_table=STG,
        column_type_map=ctm,
    )
    assert result.success is True
    assert loader.calls[0][2] == [{"a": 1, "id": "not-a-number"}]


def test_tibero_conversion_failure_uses_python_wording_and_sends_nothing():
    result, _, loader = _run_tibero(
        make_tibero_settings(),
        stdout='[{"a": 1}, {"a": 1.5}]',
        output_table=STG,
        column_type_map={"a": "INTEGER"},
    )
    assert result.success is False
    assert result.error.startswith("출력 JSON 의 2번째 행 'a' 값 '1.5' 을(를) 정수(INTEGER)로 변환할 수 없습니다.")
    assert loader.calls == []


def test_tibero_no_output_table_does_not_load():
    result, _, loader = _run_tibero(make_tibero_settings(), stdout='[{"a": 1}]', stderr="e")
    assert result.success is True and result.rows_loaded == 0
    assert result.output == '[{"a": 1}]e'
    assert loader.calls == []


def test_tibero_script_failure_does_not_load():
    result, _, loader = _run_tibero(
        make_tibero_settings(), stdout='[{"a": 1}]', stderr="Traceback", returncode=1, output_table=STG
    )
    assert result.success is False and result.exit_code == 1
    assert loader.calls == []


def test_tibero_remote_load_failure_returns_insert_failed_error():
    loader = _RecordingLoader(error=RemoteLoadError("Tibero 적재(db-executor) 응답 시간이 초과되었습니다"))
    result, _, _ = _run_tibero(make_tibero_settings(), stdout='[{"a": 1}]', output_table=STG, loader=loader)
    assert result.success is False
    # 원격 적재 실패 원문은 로그에만 두고 PG 와 같은 일반 안내를 보인다(WD-211).
    assert result.error == "출력 결과를 데이터셋에 적재하지 못했습니다. 출력 JSON 의 키와 값 형식을 확인해 주세요."
    assert result.rows_loaded == 0


def test_tibero_token_is_redacted_from_captured_output_and_error():
    """D3: 스크립트가 토큰을 찍어도(실수·악의) 실행 로그·오류에는 남지 않는다."""
    leak = f"token={SCRIPT_TOKEN}\n"
    ok, _, _ = _run_tibero(make_tibero_settings(), stdout=leak, stderr=leak)
    assert SCRIPT_TOKEN not in ok.output
    failed, _, _ = _run_tibero(make_tibero_settings(), stdout=leak, stderr=leak, returncode=1)
    assert SCRIPT_TOKEN not in failed.output and SCRIPT_TOKEN not in (failed.error or "")


def test_tibero_timeout_message_has_no_token():
    settings = make_tibero_settings(nsjail_enabled=True)
    with patch("app.services.python_executor.subprocess.run") as mock_run:
        mock_run.side_effect = subprocess.TimeoutExpired(cmd=["nsjail"], timeout=5)
        result = execute_python_tibero(
            "x", 5, settings, tenant_id=4, script_token=SCRIPT_TOKEN, loader=_RecordingLoader()
        )
    assert result.success is False
    # 시간 초과 문구는 PG 와 같은 한국어 고정 문구다(WD-211).
    assert result.error == "실행 시간 제한(5초)을 넘어 스크립트를 중단했습니다."
    assert SCRIPT_TOKEN not in repr(result)


def test_tibero_unexpected_error_traceback_is_redacted():
    settings = make_tibero_settings()
    with patch("app.services.python_executor.subprocess.run") as mock_run:
        mock_run.side_effect = RuntimeError(f"boom {SCRIPT_TOKEN}")
        result = execute_python_tibero(
            "x", None, settings, tenant_id=4, script_token=SCRIPT_TOKEN, loader=_RecordingLoader()
        )
    assert result.success is False
    # 사용자 문구는 PG 와 같은 일반 안내(WD-211), traceback 은 실행 로그 칸(output)에만 — 거기서도 토큰은 지워진다.
    assert result.error == "스크립트 실행 중 예기치 못한 오류가 발생했습니다. 잠시 후 다시 실행하고, 계속되면 관리자에게 문의해 주세요."
    assert "boom" in result.output and SCRIPT_TOKEN not in result.output


def test_tibero_kst_aware_datetime_lands_as_same_instant():
    """D2: 스크립트가 KST 오프셋 시각을 내도 db-executor 에는 같은 순간으로 간다.

    규약: 오프셋이 있는 시각은 오프셋을 단 ISO 로 보낸다 — db-executor LoadValues 가 UTC 로 환산해 저장한다(공용 벡터
    python-remote-load-wire.json 이 이미 +09:00 → 01:02:03 UTC 를 양쪽에서 고정). 오프셋 없는 시각은 api_call 의 UTC-naive
    규약대로 UTC 로 읽힌다.
    """
    captured = []

    def handler(request):
        captured.append(_json.loads(request.content))
        return _httpx.Response(200, json={"success": True, "rows_loaded": len(captured[-1]["rows"])})

    settings = make_tibero_settings()
    loader = RemoteLoader(settings, client=_httpx.Client(transport=_httpx.MockTransport(handler)))
    stdout = _json.dumps([{"ts": "2026-10-24T09:30:00+09:00", "naive": "2026-10-24T00:30:00"}])
    result, _, _ = _run_tibero(
        settings,
        stdout=stdout,
        output_table=STG,
        column_type_map={"ts": "TIMESTAMP", "naive": "TIMESTAMP"},
        loader=loader,
    )
    assert result.success is True and result.rows_loaded == 1
    (row,) = captured[0]["rows"]
    sent = _dt.fromisoformat(row["ts"])
    assert sent.utcoffset() is not None  # 오프셋을 잃지 않는다(잃으면 09:30 이 UTC 로 읽혀 9시간 밀린다)
    assert sent.astimezone(_tz.utc) == _dt(2026, 10, 24, 0, 30, tzinfo=_tz.utc)
    # 오프셋 없는 값은 UTC 벽시계 그대로 — 위 KST 값과 같은 순간이다.
    assert _dt.fromisoformat(row["naive"]).replace(tzinfo=_tz.utc) == sent.astimezone(_tz.utc)


@pytest.mark.parametrize(
    "token",
    [
        "v1.5.3.7.1999999999.U0lHTkFUVVJF",  # 다른 테넌트
        "v1.04.3.7.1999999999.U0lHTkFUVVJF",  # 표기가 다른 같은 숫자도 거부(서명 메시지 표기가 갈린다)
        "garbage",
        "v1.\u0664.3.7.1999999999.U0lHTkFUVVJF",  # 비 ASCII 숫자
    ],
)
def test_tibero_token_tenant_must_match_request_tenant(token):
    """M8: 토큰의 테넌트 필드가 요청 테넌트와 다르면 띄우지 않는다 — 읽기(토큰 테넌트)와 적재(요청 테넌트)가 갈리지 않게."""
    result, mock_run, loader = _run_tibero(make_tibero_settings(), script_token=token, output_table=STG)
    assert result.success is False
    assert result.error == "스크립트 토큰의 테넌트가 요청 테넌트와 다릅니다"
    assert token not in result.error
    mock_run.assert_not_called()
    assert loader.calls == []


def test_tibero_select_star_rows_drop_system_keys_before_remote_load():
    """코드리뷰 1: ``SELECT *`` 모양 행(시스템 컬럼 id·import_id·created_at·_updated_at 포함)도 Tibero 에 적재된다.

    PG 는 출력 테이블이 시스템 컬럼을 물리적으로 가져서 이 키들을 그대로 받는다. Tibero 스테이징은 사용자 컬럼만 가지므로(시스템 컬럼은 옮길 때
    채운다) 이 네 키만 빼고 보낸다. 시스템 이름이 아닌 키는 그대로 둔다 — 출력에 없는 키는 PG 처럼 적재 실패여야 한다.
    """
    stdout = _json.dumps([
        {"id": 1, "name": "a", "import_id": None, "created_at": "2026-10-24T00:00:00", "_updated_at": "2026-10-24T00:00:00"},
        {"id": 2, "name": "b", "import_id": 9, "created_at": "2026-10-24T00:00:00", "_updated_at": "2026-10-24T00:00:00"},
    ])
    ctm = {"name": "TEXT"}
    captured = []

    def handler(request):
        captured.append(_json.loads(request.content))
        return _httpx.Response(200, json={"success": True, "rows_loaded": len(captured[-1]["rows"])})

    settings = make_tibero_settings()
    loader = RemoteLoader(settings, client=_httpx.Client(transport=_httpx.MockTransport(handler)))
    result, _, _ = _run_tibero(settings, stdout=stdout, output_table=STG, column_type_map=ctm, loader=loader)
    assert result.success is True and result.rows_loaded == 2
    # 빼는 일은 적재기가 청크를 복사할 때 한다 — db-executor 로 가는 행에는 시스템 키가 없다.
    assert [c["rows"] for c in captured] == [[{"name": "a"}, {"name": "b"}]]


def test_tibero_non_system_extra_key_fails_before_load_like_pg():
    """시스템 이름이 아닌 여분 키는 걸러내지 않는다 — PG 와 같이 적재 전 컬럼 확인(WD-211)에서 한국어 문구로 실패하고 db-executor 로는 보내지 않는다."""
    captured = []

    def handler(request):
        captured.append(_json.loads(request.content))
        return _httpx.Response(200, json={"success": True, "rows_loaded": len(captured[-1]["rows"])})

    settings = make_tibero_settings()
    loader = RemoteLoader(settings, client=_httpx.Client(transport=_httpx.MockTransport(handler)))
    result, _, _ = _run_tibero(
        settings,
        stdout='[{"id": 1, "name": "a", "foo": 1}]',
        output_table=STG,
        column_type_map={"name": "TEXT"},
        loader=loader,
    )
    assert result.success is False
    assert result.error == "출력 JSON 의 'foo' 가 출력 데이터셋 컬럼에 없습니다. 데이터셋 컬럼 이름과 같은 키로 출력해 주세요."
    assert captured == []


def test_tibero_rows_with_only_system_keys_fail_with_clear_message():
    """시스템 키만 있는 행은 적재할 컬럼이 없다 — 빈 행을 보내 db-executor 의 모호한 거부를 받는 대신 분명한 문구로 실패한다."""
    result, _, loader = _run_tibero(
        make_tibero_settings(), stdout='[{"id": 1, "created_at": "2026-10-24"}]', output_table=STG
    )
    assert result.success is False
    # 이 실행기가 만든 한국어 사유라 그대로 싣는다(API 로컬 경로 ONLY_SYSTEM_KEYS 와 같은 문구).
    assert result.error.startswith("출력 행에 시스템 컬럼")
    assert loader.calls == []
