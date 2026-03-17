from typing import Optional

import os
import subprocess
import tempfile
import time
import traceback

from app.config import Settings
from app.schemas.responses import PythonExecuteResponse


def execute_python(
    script: str,
    timeout: Optional[int],
    settings: Settings,
) -> PythonExecuteResponse:
    effective_timeout = timeout if timeout is not None else settings.python_timeout

    script_path = None
    start = time.perf_counter()

    try:
        with tempfile.NamedTemporaryFile(suffix=".py", dir="/tmp", delete=False) as tmp:
            script_path = tmp.name
            tmp.write(script.encode())

        if settings.nsjail_enabled:
            cmd = [
                settings.nsjail_path, "--mode", "o",
                "--time_limit", str(settings.nsjail_time_limit),
                "--rlimit_as", str(settings.nsjail_rlimit_as),
                "--rlimit_nproc", str(settings.nsjail_rlimit_nproc),
                "--disable_proc",
                "--disable_clone_newnet",
                "--really_quiet",
                "-R", "/usr",
                "-R", "/lib",
            ]
            # /lib64 exists on x86_64, not on ARM64
            if os.path.isdir("/lib64"):
                cmd += ["-R", "/lib64"]
            cmd += [
                "-R", "/etc/resolv.conf",
                "-R", "/etc/hosts",
                "-R", "/etc/ssl",
                "-R", f"{settings.python_packages_dir}:/opt/python-packages",
                "-B", "/tmp",
                "-R", f"{script_path}:/script.py",
                "--env", f"DB_URL=postgresql://{settings.db_user}:{settings.db_password}@{settings.db_host}:{settings.db_port}/{settings.db_name}",
                "--env", f"DB_USER={settings.db_user}",
                "--env", f"DB_PASSWORD={settings.db_password}",
                "--env", f"DB_HOST={settings.db_host}",
                "--env", f"DB_PORT={settings.db_port}",
                "--env", f"DB_NAME={settings.db_name}",
                "--env", "DB_SCHEMA=data",
                "--env", f"PYTHONPATH=/opt/python-packages",
                "--env", "PATH=/usr/bin:/usr/local/bin",
                "--env", "HOME=/tmp",
                "--env", "LD_LIBRARY_PATH=/usr/local/lib",
                "--", "/usr/local/bin/python3", "/script.py",
            ]
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=effective_timeout,
            )
        else:
            env = {
                "DB_URL": f"postgresql://{settings.db_user}:{settings.db_password}@{settings.db_host}:{settings.db_port}/{settings.db_name}",
                "DB_USER": settings.db_user,
                "DB_PASSWORD": settings.db_password,
                "DB_HOST": settings.db_host,
                "DB_PORT": str(settings.db_port),
                "DB_NAME": settings.db_name,
                "DB_SCHEMA": "data",
                "PATH": "/usr/bin:/usr/local/bin",
                "HOME": "/tmp",
                "PYTHONDONTWRITEBYTECODE": "1",
            }
            result = subprocess.run(
                ["python3", script_path],
                env=env,
                capture_output=True,
                text=True,
                timeout=effective_timeout,
            )

        elapsed_ms = int((time.perf_counter() - start) * 1000)
        output = (result.stdout or "") + (result.stderr or "")
        success = result.returncode == 0

        return PythonExecuteResponse(
            success=success,
            output=output,
            exit_code=result.returncode,
            error=None if success else output,
            execution_time_ms=elapsed_ms,
        )

    except subprocess.TimeoutExpired as exc:
        elapsed_ms = int((time.perf_counter() - start) * 1000)
        try:
            if exc.process:
                exc.process.kill()
        except Exception:
            pass
        return PythonExecuteResponse(
            success=False,
            output="",
            exit_code=-1,
            error=f"Execution timed out after {effective_timeout}s",
            execution_time_ms=elapsed_ms,
        )

    except Exception:
        elapsed_ms = int((time.perf_counter() - start) * 1000)
        return PythonExecuteResponse(
            success=False,
            output="",
            exit_code=-1,
            error=traceback.format_exc(),
            execution_time_ms=elapsed_ms,
        )

    finally:
        if script_path:
            try:
                os.unlink(script_path)
            except Exception:
                pass
