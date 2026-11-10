import httpx
from .config import settings

class JellyfinClient:
    def __init__(self):
        self.base = settings.jellyfin_url.rstrip("/")
        self.headers = {
            "Authorization": f'MediaBrowser Token="{settings.jellyfin_api_key}"'
        }

    async def get(self, path: str):
        async with httpx.AsyncClient(timeout=15) as client:
            response = await client.get(f"{self.base}{path}", headers=self.headers)
            response.raise_for_status()
            return response.json()

    async def sessions(self):
        return await self.get("/Sessions")

    async def users(self):
        return await self.get("/Users")

    async def item_counts(self):
        return await self.get("/Items/Counts")
