import subprocess
from unittest.mock import MagicMock, patch

import pytest

from app.config import Settings
from app.services.python_executor import execute_python


def make_settings(**kwargs) -> Settings:
    defaults = dict(
        db_host="localhost",
        db_port=5432,
        db_name="firehub",
        db_user="pipeline_executor",
        db_password="secret",
        internal_service_token="changeme",
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
    with patch("app.services.python_executor.subprocess.run") as mock_run, \
         patch("app.services.python_executor.os.unlink"):
        mock_run.return_value = make_completed_process(stdout="hello\n", returncode=0)
        result = execute_python("print('hello')", None, settings)

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
    with patch("app.services.python_executor.subprocess.run") as mock_run, \
         patch("app.services.python_executor.os.unlink"):
        mock_run.return_value = make_completed_process(
            stdout="", stderr="NameError: name 'x' is not defined", returncode=1
        )
        result = execute_python("x", None, settings)

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

    with patch("app.services.python_executor.subprocess.run", side_effect=timeout_exc), \
         patch("app.services.python_executor.os.unlink"):
        result = execute_python("import time; time.sleep(999)", 5, settings)

    assert result.success is False
    assert result.exit_code == -1
    assert "timed out" in result.error.lower()
    mock_proc.kill.assert_called_once()


# ---------------------------------------------------------------------------
# test_nsjail_command_construction
# ---------------------------------------------------------------------------
def test_nsjail_command_construction():
    settings = make_settings(nsjail_enabled=True)
    with patch("app.services.python_executor.subprocess.run") as mock_run, \
         patch("app.services.python_executor.os.unlink"):
        mock_run.return_value = make_completed_process(stdout="ok\n", returncode=0)
        execute_python("print('ok')", None, settings)

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
    with patch("app.services.python_executor.subprocess.run") as mock_run, \
         patch("app.services.python_executor.os.unlink"):
        mock_run.return_value = make_completed_process(stdout="", returncode=0)
        execute_python("pass", None, settings)

    call_args = mock_run.call_args[0][0]
    assert call_args[0] == "python3"
    assert "nsjail" not in call_args


# ---------------------------------------------------------------------------
# test_fallback_env_isolation
# ---------------------------------------------------------------------------
def test_fallback_env_isolation():
    settings = make_settings(nsjail_enabled=False)
    with patch("app.services.python_executor.subprocess.run") as mock_run, \
         patch("app.services.python_executor.os.unlink"):
        mock_run.return_value = make_completed_process(stdout="", returncode=0)
        execute_python("pass", None, settings)

    call_kwargs = mock_run.call_args[1]
    env = call_kwargs["env"]

    allowed_keys = {
        "DB_URL", "DB_USER", "DB_PASSWORD", "DB_HOST", "DB_PORT",
        "DB_NAME", "DB_SCHEMA", "PATH", "HOME", "PYTHONDONTWRITEBYTECODE",
    }
    assert set(env.keys()) == allowed_keys
    assert env["DB_SCHEMA"] == "data"
    assert env["HOME"] == "/tmp"
    # Must NOT inherit host environment
    import os
    assert "VIRTUAL_ENV" not in env
    assert "USER" not in env


# ---------------------------------------------------------------------------
# test_temp_file_cleanup
# ---------------------------------------------------------------------------
def test_temp_file_cleanup():
    settings = make_settings(nsjail_enabled=False)
    with patch("app.services.python_executor.subprocess.run") as mock_run, \
         patch("app.services.python_executor.os.unlink") as mock_unlink:
        mock_run.return_value = make_completed_process(stdout="", returncode=0)
        execute_python("pass", None, settings)

    mock_unlink.assert_called_once()
    path_arg = mock_unlink.call_args[0][0]
    assert path_arg.startswith("/tmp/")
    assert path_arg.endswith(".py")


# ---------------------------------------------------------------------------
# test_temp_file_cleanup_on_exception
# ---------------------------------------------------------------------------
def test_temp_file_cleanup_on_exception():
    settings = make_settings(nsjail_enabled=False)
    with patch("app.services.python_executor.subprocess.run", side_effect=RuntimeError("boom")), \
         patch("app.services.python_executor.os.unlink") as mock_unlink:
        result = execute_python("pass", None, settings)

    assert result.success is False
    mock_unlink.assert_called_once()


# ---------------------------------------------------------------------------
# test_execution_time_measurement
# ---------------------------------------------------------------------------
def test_execution_time_measurement():
    settings = make_settings(nsjail_enabled=False)
    with patch("app.services.python_executor.subprocess.run") as mock_run, \
         patch("app.services.python_executor.os.unlink"):
        mock_run.return_value = make_completed_process(stdout="", returncode=0)
        result = execute_python("pass", None, settings)

    assert result.execution_time_ms >= 0
