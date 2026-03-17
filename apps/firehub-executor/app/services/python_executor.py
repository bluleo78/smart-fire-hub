from __future__ import annotations

import json
import logging
import os
import subprocess
import tempfile
import time
import traceback
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation
from typing import Any, Dict, List, Optional

from app.config import Settings
from app.db.connection import get_connection
from app.schemas.responses import PythonExecuteResponse
from app.services.db_utils import insert_batch

logger = logging.getLogger(__name__)


def execute_python(
    script: str,
    timeout: Optional[int],
    settings: Settings,
    output_table: Optional[str] = None,
    column_type_map: Optional[dict] = None,
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
        stdout_text = result.stdout or ""
        stderr_text = result.stderr or ""
        success = result.returncode == 0

        rows_loaded = 0
        if success and output_table:
            rows = _parse_stdout_json(stdout_text)
            if rows:
                try:
                    _apply_type_conversion(rows, column_type_map)
                    with get_connection() as conn:
                        insert_batch(conn, output_table, rows)
                        conn.commit()
                    rows_loaded = len(rows)
                except Exception as e:
                    return PythonExecuteResponse(
                        success=False,
                        output=stderr_text,
                        exit_code=0,
                        error=f"Script succeeded but data insert failed: {e}",
                        execution_time_ms=elapsed_ms,
                        rows_loaded=0,
                    )

        # output field: stderr only when output_table is set, otherwise stdout+stderr (legacy)
        output_text = stderr_text if output_table else (stdout_text + stderr_text)

        return PythonExecuteResponse(
            success=success,
            output=output_text,
            exit_code=result.returncode,
            error=None if success else output_text,
            execution_time_ms=elapsed_ms,
            rows_loaded=rows_loaded,
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


# ---------------------------------------------------------------------------
# stdout JSON parsing
# ---------------------------------------------------------------------------

def _parse_stdout_json(stdout: str) -> Optional[List[Dict[str, Any]]]:
    """Parse JSON array from stdout. Returns None on failure."""
    if not stdout or not stdout.strip():
        return None
    try:
        data = json.loads(stdout.strip())
        if isinstance(data, list) and len(data) > 0 and isinstance(data[0], dict):
            return data
        if isinstance(data, (list, dict)):
            logger.warning(
                "stdout JSON parsed but is not a list of dicts, ignoring: type=%s",
                type(data).__name__,
            )
        return None
    except (json.JSONDecodeError, ValueError):
        return None


# ---------------------------------------------------------------------------
# Type conversion
# ---------------------------------------------------------------------------

def _apply_type_conversion(rows: List[Dict[str, Any]], column_type_map: Optional[dict]) -> None:
    """In-place type conversion for rows based on column_type_map."""
    if not column_type_map:
        return
    for row in rows:
        for col, val in row.items():
            if val is None:
                continue
            dtype = column_type_map.get(col)
            if not dtype:
                continue
            row[col] = _convert_single(val, dtype)


def _convert_single(value: Any, dtype: str) -> Any:
    """Convert a single value to the target data type."""
    if value is None:
        return None
    dtype_upper = dtype.upper()
    try:
        if dtype_upper in ("TEXT", "VARCHAR", "STRING"):
            return str(value)

        if dtype_upper == "INTEGER":
            return int(str(value).strip())

        if dtype_upper in ("DECIMAL", "NUMERIC", "FLOAT", "DOUBLE"):
            return Decimal(str(value).strip())

        if dtype_upper == "BOOLEAN":
            if isinstance(value, bool):
                return value
            return str(value).strip().lower() in ("true", "1", "yes")

        if dtype_upper == "DATE":
            if isinstance(value, date):
                return value
            return date.fromisoformat(str(value).strip())

        if dtype_upper in ("TIMESTAMP", "DATETIME"):
            if isinstance(value, datetime):
                return value
            return datetime.fromisoformat(str(value).strip().replace("Z", "+00:00"))

        # Default: return as-is
        return value

    except (ValueError, InvalidOperation, TypeError) as e:
        logger.warning("Type conversion failed for value=%r dtype=%s: %s", value, dtype, e)
        return None


