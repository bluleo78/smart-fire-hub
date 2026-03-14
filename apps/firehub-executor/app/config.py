from pydantic_settings import BaseSettings, SettingsConfigDict
from functools import lru_cache


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="EXECUTOR_", env_file=".env", extra="ignore")

    # Database
    db_host: str = "localhost"
    db_port: int = 5432
    db_name: str = "firehub"
    db_user: str = "pipeline_executor"
    db_password: str = ""

    # Auth
    internal_service_token: str = "changeme"

    # nsjail
    nsjail_enabled: bool = False
    nsjail_time_limit: int = 1800
    nsjail_rlimit_as: int = 512
    nsjail_rlimit_nproc: int = 64
    nsjail_path: str = "/usr/sbin/nsjail"

    # Python execution
    python_timeout: int = 1800
    python_packages_dir: str = "/usr/local/lib/python3.11/dist-packages"

    # Connection pool
    db_pool_min: int = 2
    db_pool_max: int = 10


@lru_cache
def get_settings() -> Settings:
    return Settings()
