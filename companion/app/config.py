from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    jellyfin_url: str = "http://jellyfin:8096"
    jellyfin_api_key: str = ""
    allerflux_api_key: str = ""
    allerflux_webhook_key: str = ""
    allerflux_encryption_key: str = ""
    database_url: str = "sqlite:////data/allerflux.db"
    poll_interval_seconds: int = 20
    idle_alert_threshold_seconds: int = 1800
    alert_cooldown_minutes: int = 60
    alert_webhook_url: str = ""
    snapshot_retention_days: int = 365
    event_retention_days: int = 730
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

settings = Settings()
