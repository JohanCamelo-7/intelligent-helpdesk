"""Validación del JWT emitido por helpdesk-api (HS256, mismo secreto)."""
from __future__ import annotations

from dataclasses import dataclass

import jwt
from fastapi import Header, HTTPException

from .config import settings

ISSUER = "helpdesk-api"


@dataclass(frozen=True)
class User:
    id: str
    rol: str
    nombre: str
    email: str
    token: str


def decode_token(token: str, secret: str | None = None) -> User:
    try:
        claims = jwt.decode(token, secret or settings.jwt_secret, algorithms=["HS256"], issuer=ISSUER,
                            options={"require": ["exp", "sub", "iss"]})
    except jwt.ExpiredSignatureError as exc:
        raise HTTPException(status_code=401, detail="La sesión expiró, inicia sesión de nuevo") from exc
    except jwt.PyJWTError as exc:
        raise HTTPException(status_code=401, detail="Token inválido") from exc
    roles = claims.get("roles") or ["USER"]
    return User(id=claims["sub"], rol=roles[0], nombre=claims.get("nombre", ""), email=claims.get("email", ""), token=token)


def current_user(authorization: str | None = Header(default=None)) -> User:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="Debes iniciar sesión")
    return decode_token(authorization.split(" ", 1)[1].strip())
