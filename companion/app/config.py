from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    jellyfin_url: str = "http://jellyfin:8096"
    jellyfin_api_key: str = ""
    allerflux_api_key: str = ""
    allerflux_webhook_key: str = ""
    database_url: str = "sqlite:////data/allerflux.db"
    poll_interval_seconds: int = 20
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

settings = Settings()
