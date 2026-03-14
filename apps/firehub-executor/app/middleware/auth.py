from typing import Optional

import hmac

from fastapi import Depends, Header, HTTPException, status

from app.config import Settings, get_settings


async def verify_internal_auth(
    authorization: str = Header(..., alias="Authorization"),
    x_on_behalf_of: Optional[str] = Header(None, alias="X-On-Behalf-Of"),
    settings: Settings = Depends(get_settings),
) -> str:
    """Validate Internal auth token and return the acting user ID.

    Raises HTTP 401 if the token is missing or invalid.
    Returns the user ID from X-On-Behalf-Of, or "system" if absent.
    """
    if not authorization.startswith("Internal "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid authorization scheme — expected 'Internal <token>'",
        )

    provided_token = authorization[len("Internal "):]
    expected_token = settings.internal_service_token

    if not hmac.compare_digest(provided_token.encode(), expected_token.encode()):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid internal service token",
        )

    return x_on_behalf_of if x_on_behalf_of else "system"
