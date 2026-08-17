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
from app.tenant import resolve_db_url, resolve_schema

logger = logging.getLogger(__name__)


def execute_python(
    script: str,
    timeout: Optional[int],
    settings: Settings,
    *,
    tenant_id: int,
    output_table: Optional[str] = None,
    column_type_map: Optional[dict] = None,
) -> PythonExecuteResponse:
    """사용자 Python 스크립트를 실행한다. ``tenant_id`` 는 **키워드 전용 필수** 인자다.

    키워드 전용인 이유: 위치 인자로 두면 이관하지 않은 호출부가 값을 ``output_table`` 자리에
    조용히 넣을 수 있다. 키워드 전용이면 누락이 곧 ``TypeError`` 다.

    이 함수가 자식 프로세스에 넘기는 ``DB_URL``/``DB_SCHEMA`` 는 **테넌트별**이다 — 이 경로에는
    SQL 문장 검증기가 없어서(사용자 코드가 psycopg2 로 임의 문장을 실행한다) 격리를 강제하는
    유일한 수단이 접속 롤의 권한이다.
    """
    effective_timeout = timeout if timeout is not None else settings.python_timeout

    # nsjail 경로와 비nsjail 경로가 **같은 값**을 쓰도록 한 번만 파생한다.
    # (두 경로가 각자 조립하면 배포 설정에 따라 접속 주체가 조용히 갈린다 — #270 주석이
    #  "동작이 일치한다"고 보장하는 지점이므로 한쪽만 바꾸면 그 보장이 깨진다.)
    tenant_db_url = resolve_db_url(tenant_id, settings)
    tenant_schema = resolve_schema(tenant_id)

    script_path = None
    start = time.perf_counter()

    try:
        with tempfile.NamedTemporaryFile(suffix=".py", dir="/tmp", delete=False) as tmp:
            script_path = tmp.name
            tmp.write(script.encode())

        if not settings.nsjail_enabled:
            # 운영 환경에서 nsjail 비활성화는 보안 취약점 — #88/#89
            # Python 스크립트가 호스트 OS 명령 및 환경변수(DB 자격증명)에 접근 가능
            logger.warning(
                "SECURITY WARNING: nsjail is disabled. Python scripts run with host OS access. "
                "Set NSJAIL_ENABLED=true in production."
            )

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
                # DB 접근은 DB_URL 하나로 충분(psycopg2.connect(os.environ["DB_URL"])).
                # 개별 자격증명 키(DB_USER/DB_PASSWORD/DB_HOST/...)는 공격 표면을 늘릴 뿐이라 주입하지 않는다.
                # nsjail 비활성 경로(아래)도 동일하게 DB_URL 만 제공하므로 동작이 일치한다. (#270)
                # 자격증명은 공유 롤이 아니라 **요청 테넌트의 롤**이다(P3-b1).
                "--env", f"DB_URL={tenant_db_url}",
                "--env", f"DB_SCHEMA={tenant_schema}",
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
            # nsjail 비활성 환경. DB_URL만 전달하고 개별 자격증명 키(DB_PASSWORD 등)는 제외.
            # DB_URL에도 패스워드가 포함되나, 개별 키 노출보다 공격 표면을 최소화. (#89)
            env = {
                # nsjail 경로와 **같은** 파생값을 쓴다(위 주석 참고).
                "DB_URL": tenant_db_url,
                "DB_SCHEMA": tenant_schema,
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
                    # 적재도 테넌트 롤로 — 스크립트가 남의 스키마에 쓰지 못하게 한다.
                    with get_connection(tenant_id, settings) as conn:
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


