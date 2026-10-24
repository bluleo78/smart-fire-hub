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
from typing import Any, Callable, Dict, List, Optional, Tuple

from app.config import Settings
from app.db.connection import get_connection
from app.schemas.responses import PythonExecuteResponse
from app.services.db_utils import insert_batch
from app.services.sql_executor import pg_error_info
from app.tenant import resolve_read_db_url, resolve_schema

logger = logging.getLogger(__name__)


def execute_python(
    script: str,
    timeout: Optional[int],
    settings: Settings,
    *,
    tenant_id: int,
    read_slot: int,
    output_table: Optional[str] = None,
    column_type_map: Optional[dict] = None,
) -> PythonExecuteResponse:
    """사용자 Python 스크립트를 실행한다. ``tenant_id``·``read_slot`` 은 **키워드 전용 필수** 인자다.

    키워드 전용인 이유: 위치 인자로 두면 이관하지 않은 호출부가 값을 ``output_table`` 자리에
    조용히 넣을 수 있다. 키워드 전용이면 누락이 곧 ``TypeError`` 다.

    이 함수가 자식 프로세스에 넘기는 ``DB_URL``/``DB_SCHEMA`` 는 **테넌트별**이다 — 이 경로에는
    SQL 문장 검증기가 없어서(사용자 코드가 psycopg2 로 임의 문장을 실행한다) 격리를 강제하는
    유일한 수단이 접속 롤의 권한이다.

    ``DB_URL`` 은 테넌트 실행 롤이 아니라 **읽기 슬롯 롤**(``pipeline_py_t{id}_s{k}``)로 직접 로그인한다
    (WD-29). 슬롯 롤은 등급 위치 1..k 데이터셋만 SELECT 할 수 있어 실행 주체의 등급 밖 데이터를 읽지
    못한다. SET ROLE 이 아니라 직접 로그인인 이유는 스크립트가 RESET ROLE 로 되돌릴 수 있어서다.
    슬롯이 잘못되면 스크립트를 띄우기 전에 ``TenantResolutionError`` — 테넌트 롤 폴백은 없다(fail-closed).
    stdout JSON 출력 적재만 테넌트 롤 커넥션(``get_connection``)으로 executor 가 한다.
    """
    # nsjail 경로와 비nsjail 경로가 **같은 값**을 쓰도록 한 번만 파생한다.
    # (두 경로가 각자 조립하면 배포 설정에 따라 접속 주체가 조용히 갈린다 — #270 주석이
    #  "동작이 일치한다"고 보장하는 지점이므로 한쪽만 바꾸면 그 보장이 깨진다.)
    # 스크립트는 실행 주체 슬롯 롤로 **직접 로그인**한다(WD-29). 슬롯 검증 실패는 여기서 예외 —
    # 스크립트를 띄우기 전에 끝난다. 테넌트 롤은 아래 출력 적재(get_connection)에만 쓴다(폴백 아님).
    script_db_url = resolve_read_db_url(tenant_id, read_slot, settings)
    tenant_schema = resolve_schema(tenant_id)

    def command(script_path: str) -> Tuple[List[str], Optional[Dict[str, str]]]:
        """PG 실행 명령·환경 — 스크립트 DB 접속은 읽기 슬롯 롤 DB_URL 하나(nsjail·비nsjail 같은 값)."""
        if not settings.nsjail_enabled:
            # 운영 환경에서 nsjail 비활성화는 보안 취약점 — #88/#89
            # Python 스크립트가 호스트 OS 명령 및 환경변수(DB 자격증명)에 접근 가능
            logger.warning(
                "SECURITY WARNING: nsjail is disabled. Python scripts run with host OS access. "
                "Set NSJAIL_ENABLED=true in production."
            )
            # nsjail 비활성 환경. DB_URL만 전달하고 개별 자격증명 키(DB_PASSWORD 등)는 제외.
            # DB_URL에도 패스워드가 포함되나, 개별 키 노출보다 공격 표면을 최소화. (#89)
            env = {
                # nsjail 경로와 **같은** 파생값을 쓴다(위 주석 참고).
                "DB_URL": script_db_url,
                "DB_SCHEMA": tenant_schema,
                "PATH": "/usr/bin:/usr/local/bin",
                "HOME": "/tmp",
                "PYTHONDONTWRITEBYTECODE": "1",
            }
            return ["python3", script_path], env
        cmd = _nsjail_base_cmd(settings, script_path)
        cmd += [
            # DB 접근은 DB_URL 하나로 충분(psycopg2.connect(os.environ["DB_URL"])).
            # 개별 자격증명 키(DB_USER/DB_PASSWORD/DB_HOST/...)는 공격 표면을 늘릴 뿐이라 주입하지 않는다.
            # nsjail 비활성 경로(위)도 동일하게 DB_URL 만 제공하므로 동작이 일치한다. (#270)
            # 자격증명은 공유 롤도 테넌트 실행 롤도 아닌 **실행 주체의 읽기 슬롯 롤**이다(P3-b1 → WD-29).
            "--env", f"DB_URL={script_db_url}",
            "--env", f"DB_SCHEMA={tenant_schema}",
            "--env", f"PYTHONPATH=/opt/python-packages",
            "--env", "PATH=/usr/bin:/usr/local/bin",
            "--env", "HOME=/tmp",
            "--env", "LD_LIBRARY_PATH=/usr/local/lib",
            "--", "/usr/local/bin/python3", "/script.py",
        ]
        # PG nsjail 경로는 env 인자 없이 실행한다(기존 그대로) — nsjail 이 --env 로 적은 값만 자식에게 넘긴다.
        return cmd, None

    def load_rows(rows: List[Dict[str, Any]]) -> int:
        """stdout JSON 행을 타입 변환해 테넌트 롤 연결로 출력 테이블에 넣는다."""
        _apply_type_conversion(rows, column_type_map)
        # 적재도 테넌트 롤로 — 스크립트가 남의 스키마에 쓰지 못하게 한다.
        with get_connection(tenant_id, settings) as conn:
            insert_batch(conn, output_table, rows)
            conn.commit()
        return len(rows)

    effective_timeout = timeout if timeout is not None else settings.python_timeout
    return _run_script(
        script,
        effective_timeout,
        command=command,
        output_table=output_table,
        column_type_map=column_type_map,
        load_rows=load_rows,
    )


def _pg_load_failure(error: Exception) -> Tuple[str, Optional[Any]]:
    """PG 적재 실패 문구(WD-211) — 원문은 로그에만 두고 일반 안내 문구와 pg_error(SQLState 22 사유는 api 가 만든다)를 돌려준다."""
    return LOAD_FAILED_MESSAGE, pg_error_info(error)


def _tibero_load_failure(error: Exception) -> Tuple[str, Optional[Any]]:
    """Tibero 적재 실패 문구 — PG 와 같이 원문(원격 적재기·db-executor 응답, Tibero 오류 문장)은 로그에만 두고 일반 안내를 보인다(WD-211).

    시스템 키만 있는 행(``_TIBERO_ONLY_SYSTEM_KEYS``)은 이 실행기가 만든 한국어 사유라 그대로 싣는다(API 로컬 경로와 같은 문구).
    pg_error 는 없다(Tibero 오류는 PG SQLState 번역 대상이 아니다).
    """
    if isinstance(error, ValueError) and str(error) == _TIBERO_ONLY_SYSTEM_KEYS:
        return _TIBERO_ONLY_SYSTEM_KEYS, None
    return LOAD_FAILED_MESSAGE, None


def _no_redact(text: Optional[str]) -> Optional[str]:
    """PG 경로는 지울 비밀(스크립트 토큰)이 없다 — 문자열을 그대로 둔다."""
    return text


def _run_script(
    script: str,
    effective_timeout: int,
    *,
    command: Callable[[str], Tuple[List[str], Optional[Dict[str, str]]]],
    output_table: Optional[str],
    column_type_map: Optional[dict],
    load_rows: Callable[[List[Dict[str, Any]]], int],
    load_failure: Callable[[Exception], Tuple[str, Optional[Any]]] = None,
    redact: Callable[[Optional[str]], Optional[str]] = _no_redact,
) -> PythonExecuteResponse:
    """PG·Tibero 경로가 함께 쓰는 스크립트 실행 뼈대 — 임시 파일, 실행, 시간 초과 kill, stdout JSON 적재, 응답, 정리.

    두 경로가 각자 같은 뼈대를 들고 있으면 한쪽만 고쳐져 동작이 조용히 갈린다. 경로마다 다른 것만 주입한다.
    - ``command(script_path)``: 실행할 명령과 환경. 환경이 None 이면 ``env`` 인자 없이 실행한다(PG nsjail 경로, 기존 그대로).
    - ``load_rows(rows)``: stdout JSON 행을 출력에 넣고 적재 행 수를 돌려준다(PG 는 테넌트 롤 연결, Tibero 는 원격 적재). 행 모양·키
      검사(WD-211)는 그 전에 이 뼈대가 두 경로 공통으로 한다.
    - ``load_failure(e)``: 적재 예외를 (사용자 문구, pg_error) 로 바꾼다. 기본은 PG 규칙(WD-211 — 원문은 로그에만, 문구는
      ``LOAD_FAILED_MESSAGE``, PG 데이터 오류는 pg_error 로 api 가 사유를 만든다).
    - ``redact``: 실행 로그·오류에서 비밀을 지운다(Tibero 는 스크립트 토큰, PG 는 그대로). 시간 초과 문구는 고정이라 지우지 않는다.
    출력 규칙은 두 경로가 같다: 출력 테이블이 있으면 stderr, 없으면 stdout+stderr.
    """
    if load_failure is None:
        load_failure = _pg_load_failure
    script_path = None
    start = time.perf_counter()

    try:
        with tempfile.NamedTemporaryFile(suffix=".py", dir="/tmp", delete=False) as tmp:
            script_path = tmp.name
            tmp.write(script.encode())

        cmd, env = command(script_path)
        run_kwargs: Dict[str, Any] = {"capture_output": True, "text": True, "timeout": effective_timeout}
        if env is not None:
            run_kwargs["env"] = env
        result = subprocess.run(cmd, **run_kwargs)

        elapsed_ms = int((time.perf_counter() - start) * 1000)
        stdout_text = result.stdout or ""
        stderr_text = result.stderr or ""
        success = result.returncode == 0

        rows_loaded = 0
        if success and output_table:
            try:
                rows = _parse_stdout_json(stdout_text)
            except StdoutNotJsonError as e:
                # 출력 테이블이 있는데 stdout 이 JSON 이 아니면 적재 전에 실패시킨다(WD-210). 예전엔 조용히 0행
                # "완료"라 REPLACE 출력이 옛 데이터로 남아 갱신이 멈춘 걸 알 수 없었다. 적재(get_connection) 전이라
                # 출력 테이블은 건드리지 않는다. 반환 형식은 아래 적재 실패 경로와 같다.
                return PythonExecuteResponse(
                    success=False,
                    output=stderr_text,
                    exit_code=0,
                    error=str(e),
                    execution_time_ms=elapsed_ms,
                    rows_loaded=0,
                )
            if rows:
                try:
                    # 행 모양·컬럼 이름은 DB 에 닿기 전에 확인한다(WD-211) — 어느 행·어느 키가 문제인지 정확히 짚고,
                    # 출력 테이블(REPLACE 면 임시 테이블, Tibero 면 스테이징)은 건드리지 않는다. PG·Tibero 가 같은 문구를 쓴다.
                    _require_rows_are_objects(rows)
                    _require_known_columns(rows[0], column_type_map)
                    _require_first_row_keys(rows)
                except OutputRowsError as e:
                    return PythonExecuteResponse(
                        success=False,
                        output=redact(stderr_text),
                        exit_code=0,
                        error=str(e),
                        execution_time_ms=elapsed_ms,
                        rows_loaded=0,
                    )
                try:
                    rows_loaded = load_rows(rows)
                except Exception as e:
                    # 원문(내부 테이블·SQL)은 로그에만 남긴다(WD-211). 사용자 문구는 일반 안내이고, PG 데이터 오류(22xxx)면
                    # API 가 pg_error 로 한국어 사유 문구를 만든다(DataExceptionTranslator — 번역 규칙을 두 벌 두지 않는다).
                    logger.warning("Python output insert failed: table=%s", output_table, exc_info=True)
                    message, pg_error = load_failure(e)
                    return PythonExecuteResponse(
                        success=False,
                        output=redact(stderr_text),
                        exit_code=0,
                        error=message,
                        execution_time_ms=elapsed_ms,
                        rows_loaded=0,
                        pg_error=pg_error,
                    )

        # output field: stderr only when output_table is set, otherwise stdout+stderr (legacy)
        output_text = redact(stderr_text if output_table else (stdout_text + stderr_text))

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
            error=TIMEOUT_MESSAGE.format(seconds=effective_timeout),
            execution_time_ms=elapsed_ms,
        )

    except Exception:
        elapsed_ms = int((time.perf_counter() - start) * 1000)
        # 스크립트 오류가 아닌 실행기 쪽 예외(임시 파일·프로세스 기동 실패 등). traceback 은 사용자 문구(error)에 넣지 않고
        # 로그와 output(실행 로그 칸)에만 남긴다(WD-211) — 예전엔 내부 경로가 담긴 traceback 전체가 스텝 오류로 보였다.
        tb = traceback.format_exc()
        logger.exception("Unexpected error while executing python script")
        return PythonExecuteResponse(
            success=False,
            output=redact(tb),
            exit_code=-1,
            error=UNEXPECTED_ERROR_MESSAGE,
            execution_time_ms=elapsed_ms,
        )

    finally:
        if script_path:
            try:
                os.unlink(script_path)
            except Exception:
                pass


def _nsjail_base_cmd(settings: Settings, script_path: str) -> List[str]:
    """nsjail 샌드박스 바닥 인자(``--env`` 앞까지). PG·Tibero 경로가 **같은 격리**를 쓰도록 한 곳에서 만든다.

    환경 변수(접속 정보)만 경로마다 다르고, 시간·메모리·프로세스 제한, /proc 차단, 읽기 전용 마운트, 패키지 경로는 같아야 한다 —
    두 경로가 각자 조립하면 한쪽만 바뀌어 격리 수준이 조용히 갈린다.
    """
    cmd = [
        settings.nsjail_path, "--mode", "o",
        "--time_limit", str(settings.nsjail_time_limit),
        "--rlimit_as", str(settings.nsjail_rlimit_as),
        "--rlimit_nproc", str(settings.nsjail_rlimit_nproc),
        "--disable_proc",
        "--disable_clone_newnet",
        # --really_quiet 가 아니라 --quiet 다. --really_quiet 는 FATAL 만 남기는데,
        # nsjail 의 FATAL 은 "runChild(): Launching child process failed" 처럼 **무엇이
        # 실패했는지만** 말하고 **왜**는 바로 앞의 ERROR 줄에 있다. 그 줄이 잘리면 스텝
        # 오류 메시지가 원인을 전혀 담지 못한다 — 2026-09-18 운영에서 AppArmor 가 mount 를
        # 막아 샌드박스가 뜨지 못했는데, 로그만으로는 알 수 없어 파드에 직접 들어가
        # 같은 명령을 재현해야 했다. --quiet 는 ERROR 를 남긴다.
        # 대가는 nsjail 이 매 실행 남기는 UID/GID 경고 두 줄이 스텝 로그에 섞이는 것인데,
        # 재현 불가능한 실패보다 알아볼 수 있는 상수 잡음이 낫다.
        "--quiet",
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
    ]
    return cmd


# ---------------------------------------------------------------------------
# Tibero 테넌트 경로(WD-78)
# ---------------------------------------------------------------------------

# 실행 로그·오류에서 토큰 자리에 남기는 표시.
_REDACTED = "****"
TIBERO_TOKEN_MISSING = "Tibero 저장소 워크스페이스의 Python 실행에 필요한 스크립트 토큰이 없습니다"
TIBERO_TOKEN_TENANT_MISMATCH = "스크립트 토큰의 테넌트가 요청 테넌트와 다릅니다"
# 리포 안 도우미 위치(apps/firehub-executor/sandbox). 이미지에는 없고(/opt/python-packages 에 복사된다) 개발 실행기에만 있다.
_REPO_SANDBOX_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "sandbox")
# db-executor 주소가 없을 때의 응답 문구(코드리뷰 3). 이 문구는 api 스텝 오류로 사용자 화면에 나가므로 환경 변수 이름을 싣지 않는다 —
# 어떤 설정이 비었는지는 실행기 WARN 로그에만 남긴다(api 의 PythonScriptExecutor.TIBERO_SETUP_MISSING 과 같은 문구).
_TIBERO_DBX_MISSING = "Python 조회 설정이 준비되지 않아 실행할 수 없습니다. 운영자에게 문의하세요."


# 데이터셋 물리 테이블이 자동으로 갖는 시스템 컬럼(api ``DataTableStore.SYSTEM_COLUMNS`` 와 같은 이름, 대소문자 그대로).
# PG 출력 테이블은 이 컬럼을 물리적으로 가져 ``SELECT *`` 모양 출력의 이 키들을 그대로 받지만, Tibero 스테이징은 사용자 컬럼만 갖는다
# (시스템 컬럼은 api 의 옮기기가 채운다) — 그래서 Tibero 적재는 이 키만 빼고 보낸다(코드리뷰 1). 빼는 일은 원격 적재기가 청크마다 행을
# 복사할 때 한다(``drop_keys``) — 전체 결과의 사본을 따로 만들지 않는다. 시스템 이름이 **아닌** 여분 키는 남긴다 — PG 도 출력에 없는 키는
# "column does not exist" 로 실패하므로 Tibero 도 db-executor 거부로 실패해야 같다.
# 알려진 차이: PG 는 행이 준 id·import_id·created_at 값을 그대로 저장하지만 Tibero 는 옮길 때 새로 채운다(_updated_at 은 두 쪽 모두
# 트리거가 덮어쓴다).
_SYSTEM_COLUMN_KEYS = frozenset({"id", "import_id", "created_at", "_updated_at"})
_TIBERO_ONLY_SYSTEM_KEYS = (
    "출력 행에 시스템 컬럼(id·import_id·created_at·_updated_at) 말고 적재할 컬럼이 없습니다"
)


def _has_loadable_key(row: Dict[str, Any]) -> bool:
    """행에 시스템 컬럼 말고 적재할 키가 하나라도 있는가 — 시스템 키를 뺀 사본을 만들지 않고 본다."""
    return any(k not in _SYSTEM_COLUMN_KEYS for k in row)


def _redact(text: Optional[str], secret: str) -> Optional[str]:
    """``text`` 에서 ``secret`` 을 지운다. 스크립트가 토큰을 찍어도(실수·악의) 스텝 실행 로그·오류에 남지 않게 한다."""
    if not text or not secret:
        return text
    return text.replace(secret, _REDACTED)


def _token_tenant_matches(token: str, tenant_id: int) -> bool:
    """토큰 ``v1.<tenantId>.<slot>.<epoch>.<exp>.<sig>`` 의 테넌트 필드가 ``tenant_id`` 의 정확한 표기와 같은가.

    서명 검증은 db-executor 몫이다. 여기서는 모양과 테넌트만 본다 — 스크립트 읽기는 토큰의 테넌트로, 출력 적재는 요청 테넌트로 가므로 둘이 다르면
    한 실행이 남의 테넌트 데이터를 읽어 자기 테넌트에 쓰는 조합이 생긴다(심층 방어, api 는 언제나 같은 값을 싣는다).
    """
    parts = token.split(".")
    return len(parts) == 6 and parts[0] == "v1" and parts[1].isascii() and parts[1] == str(tenant_id)


def _sandbox_pythonpath(settings: Settings) -> str:
    """nsjail 없는(개발) 실행의 ``PYTHONPATH`` — 샌드박스 패키지 경로 + 리포 도우미 위치(있으면).

    운영 이미지는 도우미를 ``/opt/python-packages`` 에 복사하지만, 개발 실행기의 ``python_packages_dir`` 기본값(dist-packages)에는 없다.
    리포의 ``sandbox`` 디렉터리를 덧붙여 ``import genia_db`` 가 개발 환경에서도 되게 한다.
    """
    paths = [settings.python_packages_dir]
    if os.path.isfile(os.path.join(_REPO_SANDBOX_DIR, "genia_db.py")):
        paths.append(_REPO_SANDBOX_DIR)
    return os.pathsep.join(paths)


def rejected_response(error: str) -> PythonExecuteResponse:
    """스크립트를 띄우기 전의 거부 응답 — 저장소 게이트 거부와 같은 모양(HTTP 200 + success=False). 라우터와 이 모듈이 함께 쓴다."""
    return PythonExecuteResponse(success=False, output="", exit_code=1, error=error, execution_time_ms=0)


def execute_python_tibero(
    script: str,
    timeout: Optional[int],
    settings: Settings,
    *,
    tenant_id: int,
    script_token: Optional[str],
    loader: Any,
    output_table: Optional[str] = None,
    column_type_map: Optional[dict] = None,
) -> PythonExecuteResponse:
    """Tibero 저장소 테넌트의 Python 스크립트를 실행한다(WD-78, 스펙 §5.2·§5.3).

    PG 경로(``execute_python``)와 다른 점
    - 스크립트에 DB 접속 정보(``DB_URL``·``DB_SCHEMA``)를 주지 않는다. 샌드박스에는 Tibero 드라이버가 없고, 스크립트는 조회 도우미
      ``genia_db``(샌드박스 패키지 경로)로 db-executor 슬롯 조회를 부른다. 주는 값은 ``GENIA_DB_URL``(db-executor 주소)과
      ``GENIA_DB_TOKEN``(api 가 서명한 실행 1회용 스크립트 토큰 — 테넌트·슬롯·세대·만료가 담겨 있고 슬롯 조회 창구만 허용) 둘뿐이다.
    - 토큰은 nsjail 명령줄에 값으로 싣지 않는다(``--env GENIA_DB_TOKEN`` 이름만, 값은 nsjail 프로세스 환경). 명령줄은 ``ps`` 와
      ``TimeoutExpired`` 문구로 새어 나갈 수 있다. 실행 로그·오류에서도 토큰 문자열을 지운다.
    - stdout JSON 출력은 PG 연결이 아니라 ``loader``(``RemoteLoader``)로 db-executor ``/execute/load`` 에 보낸다. 대상은 api 가 만든
      스테이징(``STG_<24HEX>``)뿐이고, 출력 테이블 반영(옮기기)은 api 가 한 Tibero 트랜잭션으로 한다. 값 변환·직렬화는 ``loader`` 가
      api_call 과 같은 경로(``_apply_type_conversion`` → ``to_wire_value``)로 하므로 여기서는 원시 행을 넘긴다.

    ``loader`` 는 호출자(라우터)가 만든다 — ``app.db.backends.remote`` 가 이 모듈의 변환 함수를 가져다 쓰므로 여기서 가져오면 순환 import 다.
    토큰 누락·출력 대상 스테이징 이름 검사는 라우터가 스크립트를 띄우기 전에 한다(``store_gate.require_output_table``). 여기서는 토큰의
    테넌트가 요청 테넌트와 다르거나(빈 토큰 포함) db-executor 주소가 없으면 스크립트를 띄우지 않고 실패한다(fail-closed). 스테이징이 아닌
    이름은 원격 적재기도 다시 거부한다.
    """
    token = (script_token or "").strip()
    # 토큰 누락·스테이징 이름 검사는 라우터가 먼저 한다(같은 거부 모양). 여기서는 라우터에 없는 검사만 한다 — 빈 토큰이 와도 테넌트 대조에서
    # 막힌다(fail-closed).
    if not _token_tenant_matches(token, tenant_id):
        # 고정 문구 — 토큰 값은 싣지 않는다.
        return rejected_response(TIBERO_TOKEN_TENANT_MISMATCH)
    dbx_url = (settings.db_executor_base_url or "").strip().rstrip("/")
    if not dbx_url:
        logger.warning("Tibero Python 실행 불가: EXECUTOR_DB_EXECUTOR_BASE_URL 이 설정되지 않았습니다")
        return rejected_response(_TIBERO_DBX_MISSING)

    def command(script_path: str) -> Tuple[List[str], Optional[Dict[str, str]]]:
        """Tibero 실행 명령·환경 — 스크립트가 받는 것은 db-executor 주소와 스크립트 토큰뿐(토큰은 명령줄에 싣지 않는다)."""
        if settings.nsjail_enabled:
            cmd = _nsjail_base_cmd(settings, script_path)
            cmd += [
                # 스크립트가 받는 것은 db-executor 주소와 스크립트 토큰뿐이다(PG 의 DB_URL·DB_SCHEMA 없음).
                "--env", f"GENIA_DB_URL={dbx_url}",
                # 값 없는 --env 는 nsjail 이 자기 환경에서 같은 이름의 값을 가져간다(nsjail addEnv) — 토큰을 명령줄에 싣지 않는다.
                "--env", "GENIA_DB_TOKEN",
                "--env", "PYTHONPATH=/opt/python-packages",
                "--env", "PATH=/usr/bin:/usr/local/bin",
                "--env", "HOME=/tmp",
                "--env", "LD_LIBRARY_PATH=/usr/local/lib",
                "--", "/usr/local/bin/python3", "/script.py",
            ]
            # nsjail 은 위에 적은 이름만 자식에게 넘긴다(그 밖의 실행기 환경은 자식에 닿지 않는다).
            nsjail_env = dict(os.environ)
            nsjail_env["GENIA_DB_TOKEN"] = token
            return cmd, nsjail_env
        # nsjail 비활성(개발) — 상속 환경 없이 필요한 값만. 도우미는 샌드박스 패키지 경로에 있다.
        env = {
            "GENIA_DB_URL": dbx_url,
            "GENIA_DB_TOKEN": token,
            "PYTHONPATH": _sandbox_pythonpath(settings),
            "PATH": "/usr/bin:/usr/local/bin",
            "HOME": "/tmp",
            "PYTHONDONTWRITEBYTECODE": "1",
        }
        return ["python3", script_path], env

    def load_rows(rows: List[Dict[str, Any]]) -> int:
        """stdout JSON 행을 db-executor 로 원격 적재한다(스테이징 대상, 시스템 키는 적재기가 뺀다)."""
        # SELECT * 모양 출력의 시스템 컬럼 키는 스테이징에 없다 — 적재기가 청크마다 빼고 보낸다(코드리뷰 1). 첫 행에 남는 키가 없으면 빈 행을
        # 보내 db-executor 의 모호한 거부를 받는 대신 분명한 문구로 실패한다.
        if not _has_loadable_key(rows[0]):
            raise ValueError(_TIBERO_ONLY_SYSTEM_KEYS)
        return loader.load(tenant_id, output_table, rows, column_type_map, drop_keys=_SYSTEM_COLUMN_KEYS)

    effective_timeout = timeout if timeout is not None else settings.python_timeout
    return _run_script(
        script,
        effective_timeout,
        command=command,
        output_table=output_table,
        column_type_map=column_type_map,
        load_rows=load_rows,
        load_failure=_tibero_load_failure,
        redact=lambda text: _redact(text, token),
    )


# ---------------------------------------------------------------------------
# stdout JSON parsing
# ---------------------------------------------------------------------------

# stdout 이 JSON 이 아닐 때의 사용자 문구(WD-210). API 로컬 경로(LocalPythonOutputLoader.NOT_JSON_MESSAGE)와
# 글자 단위로 같아야 한다 — 두 경로가 같은 스크립트에 같은 오류를 보이게 하려는 것이다.
NOT_JSON_MESSAGE = (
    "스크립트 출력이 JSON 이 아니어서 결과를 적재하지 않았습니다. 출력 데이터셋을 쓰는 스크립트는 결과 JSON 한 번만 print 해야"
    " 합니다(디버그 출력은 sys.stderr 로). 출력 앞부분: \"{excerpt}\""
)

# ---------------------------------------------------------------------------
# 적재 실패·타임아웃·예기치 못한 오류 사용자 문구(WD-211)
# ---------------------------------------------------------------------------
# API 로컬 경로(firehub-api PythonStepErrorMessages 의 같은 이름 상수)와 글자 단위로 같아야 한다 — 두 경로가 같은
# 스크립트에 같은 오류를 보이게 하려는 것이다. 접두 "Python 실행 실패: " 는 API 러너가 붙이므로 여기엔 없다.
# 데이터 오류(SQLState 22) 사유 문장은 여기서 만들지 않고 pg_error 로 보내 API 가 만든다.

# 첫 행의 키가 출력 데이터셋 컬럼(시스템 컬럼 제외)에 없을 때.
UNKNOWN_COLUMN_MESSAGE = "출력 JSON 의 '{key}' 가 출력 데이터셋 컬럼에 없습니다. 데이터셋 컬럼 이름과 같은 키로 출력해 주세요."

# N번째(1부터) 행이 JSON 객체가 아닐 때. 값은 싣지 않는다(Java·Python 값 표기가 달라 문구가 갈리고, 사용자 데이터가 섞인다).
ROW_NOT_OBJECT_MESSAGE = "출력 JSON 의 {row}번째 행이 객체가 아닙니다. 모든 행을 컬럼 이름을 키로 하는 JSON 객체로 출력해 주세요."

# N번째(1부터) 행에 첫 행의 키가 없을 때 — 적재 컬럼은 첫 행의 키로 정해진다.
ROW_MISSING_KEY_MESSAGE = "출력 JSON 의 {row}번째 행에 첫 행의 키 '{key}' 가 없습니다. 모든 행에 첫 행과 같은 키를 넣어 주세요."

# 그 밖의 적재 실패(원문은 로그에만).
LOAD_FAILED_MESSAGE = "출력 결과를 데이터셋에 적재하지 못했습니다. 출력 JSON 의 키와 값 형식을 확인해 주세요."

# 실행 시간 제한 초과.
TIMEOUT_MESSAGE = "실행 시간 제한({seconds}초)을 넘어 스크립트를 중단했습니다."

# 스크립트 오류가 아닌 실행기 쪽 예기치 못한 예외.
UNEXPECTED_ERROR_MESSAGE = "스크립트 실행 중 예기치 못한 오류가 발생했습니다. 잠시 후 다시 실행하고, 계속되면 관리자에게 문의해 주세요."


# 데이터셋 물리 테이블이 자동으로 갖는 시스템 컬럼 — API 의 DataTableStore.SYSTEM_COLUMNS 와 같은 집합이어야 한다.
_SYSTEM_COLUMNS = frozenset({"id", "import_id", "created_at", "_updated_at"})


class OutputRowsError(ValueError):
    """출력 JSON 행 모양·키 오류 — 메시지가 곧 사용자 문구다. 적재 전에 나므로 DB 는 건드리지 않았다."""


def _require_rows_are_objects(rows: List[Any]) -> None:
    """모든 행이 dict 인지 확인한다. 첫 번째로 객체가 아닌 행 번호(1부터)를 짚는다."""
    for i, row in enumerate(rows, start=1):
        if not isinstance(row, dict):
            raise OutputRowsError(ROW_NOT_OBJECT_MESSAGE.format(row=i))


def _require_known_columns(first_row: Dict[str, Any], column_type_map: Optional[dict]) -> None:
    """첫 행의 키(= 적재 컬럼)가 모두 출력 데이터셋 컬럼인지 확인한다.

    시스템 컬럼(id·import_id·created_at·_updated_at)은 데이터셋 컬럼 목록에 없지만 물리 테이블에 있어 예전처럼 DB
    판단에 맡긴다. column_type_map 이 비었거나 없으면 확인할 근거가 없어 건너뛴다. API 로컬 경로
    (LocalPythonOutputLoader.requireKnownColumns)와 같은 규칙이다.
    """
    if not column_type_map:
        return
    for key in first_row:
        if key not in column_type_map and key not in _SYSTEM_COLUMNS:
            raise OutputRowsError(UNKNOWN_COLUMN_MESSAGE.format(key=key))


def _require_first_row_keys(rows: List[Dict[str, Any]]) -> None:
    """첫 행의 키가 모든 행에 있는지 확인한다(insert_batch 의 row[c] KeyError 를 행 번호 있는 문구로)."""
    columns = list(rows[0].keys())
    for i, row in enumerate(rows, start=1):
        for c in columns:
            if c not in row:
                raise OutputRowsError(ROW_MISSING_KEY_MESSAGE.format(row=i, key=c))


# 오류 문구에 인용하는 stdout 앞부분 최대 길이(문자 = 코드 포인트). 비밀이 섞일 수 있어 짧게 둔다.
NOT_JSON_EXCERPT_MAX = 100


class StdoutNotJsonError(ValueError):
    """출력 테이블이 있는 스텝의 stdout 이 JSON 으로 읽히지 않음 — 메시지가 곧 사용자 문구다."""


def _parse_stdout_json(stdout: str) -> Optional[List[Dict[str, Any]]]:
    """stdout 의 JSON 행 배열을 읽는다.

    - 비었거나 공백뿐이면 None(0행 — 기존 동작).
    - JSON 으로 읽히지 않으면 ``StdoutNotJsonError``(WD-210). 앞에 디버그 print 가 있거나 뒤에 다른 내용이
      붙은 경우다. 추측 파싱(마지막 줄만 시도 등)은 하지 않는다 — 무엇이 결과인지 스크립트가 정해야 한다.
    - JSON 이지만 "dict 로 시작하는 비어 있지 않은 list" 가 아니면 None(오류 아님 — ``[]`` 는 정상 빈 결과).
    """
    if not stdout or not stdout.strip():
        return None
    stripped = stdout.strip()
    try:
        data = json.loads(stripped)
    except ValueError as e:  # json.JSONDecodeError 는 ValueError 의 하위 클래스
        raise StdoutNotJsonError(
            NOT_JSON_MESSAGE.format(excerpt=stripped[:NOT_JSON_EXCERPT_MAX])
        ) from e
    if isinstance(data, list) and len(data) > 0 and isinstance(data[0], dict):
        return data
    if isinstance(data, (list, dict)):
        logger.warning(
            "stdout JSON parsed but is not a list of dicts, ignoring: type=%s",
            type(data).__name__,
        )
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


