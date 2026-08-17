from pydantic_settings import BaseSettings, SettingsConfigDict
from functools import lru_cache


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="EXECUTOR_", env_file=".env", extra="ignore")

    # Database
    db_host: str = "localhost"
    db_port: int = 5432
    db_name: str = "firehub"
    db_user: str = "pipeline_executor"
    db_password: str = "pipeline_exec_pwd"  # V32/V70 마이그레이션의 기본값과 일치 (로컬 dev 편의)

    # Auth — 기본값 없음: 미설정 시 Pydantic ValidationError로 기동 실패
    internal_service_token: str

    # 테넌트별 파이프라인 롤 비밀번호 파생 secret.
    # **기본값을 두지 않는다** — 미설정 시 기동이 실패한다(EXECUTOR_ROLE_PASSWORD_SECRET).
    # Java 쪽은 프로파일이 있어서 dev 기본값(application.yml)과 prod 필수(application-prod.yml,
    # `${PIPELINE_ROLE_PASSWORD_SECRET}` 무기본값)를 나눌 수 있지만, 이 Settings 에는 프로파일
    # 분기가 없다. 즉 "dev 편의 기본값" 을 하나 두면 prod 가 그 값을 **조용히 물려받는다** —
    # 그러면 모든 배포가 같은 예측 가능한 비밀번호로 테넌트 롤에 접속하게 되어 이 밴드의 격리가
    # 무의미해진다. 그래서 로컬에서도 반드시 명시하게 만든다(Java dev 기본값과 같은 값을 넣어야
    # 비밀번호가 맞는다: tenant-pipeline-role-dev-secret).
    role_password_secret: str

    # nsjail
    nsjail_enabled: bool = False
    nsjail_time_limit: int = 1800
    nsjail_rlimit_as: int = 512
    nsjail_rlimit_nproc: int = 64
    nsjail_path: str = "/usr/sbin/nsjail"

    # Python execution
    python_timeout: int = 1800
    python_packages_dir: str = "/usr/local/lib/python3.11/dist-packages"

    # Connection pool — 레거시 공유 롤(pipeline_executor) 풀. 현재는 readiness 프로브 전용이며
    # 실행 경로는 전부 테넌트별 풀을 쓴다. 이 풀과 레거시 롤은 P3-b2 에서 함께 은퇴한다.
    db_pool_min: int = 2
    db_pool_max: int = 10

    # 테넌트별 커넥션 풀. 풀 개수 상한을 두는 이유: 테넌트가 늘 때마다 풀이 무한히 늘면
    # PostgreSQL max_connections 를 고갈시킨다(스위트를 겹쳐 돌릴 때 이미 겪은 실패 형태).
    # 풀당 커넥션 수는 작게 두고, 개수는 LRU 로 상한을 지킨다.
    tenant_pool_min: int = 1
    tenant_pool_max: int = 4
    tenant_pool_limit: int = 8


@lru_cache
def get_settings() -> Settings:
    return Settings()
