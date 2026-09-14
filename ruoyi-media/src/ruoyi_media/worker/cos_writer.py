"""Tencent COS output writer; credentials are obtained only from process environment."""

from __future__ import annotations

import hashlib
import os
import re
from dataclasses import dataclass
from pathlib import Path

from .generation import OutputObjectWriter, StoredObject


_BUCKET = re.compile(r"[a-z0-9][a-z0-9.-]{1,62}")
_REGION = re.compile(r"ap-[a-z0-9-]{2,32}")


class CosConfigurationError(ValueError):
    """COS output writing has not been explicitly configured."""


class CosObjectStorageError(RuntimeError):
    """COS did not confirm an output object write."""


@dataclass(frozen=True)
class CosWriterSettings:
    secret_id: str
    secret_key: str
    region: str
    bucket: str
    session_token: str | None

    @classmethod
    def from_environment(cls) -> "CosWriterSettings":
        secret_id = os.environ.get("RUOYI_MEDIA_COS_SECRET_ID", "")
        secret_key = os.environ.get("RUOYI_MEDIA_COS_SECRET_KEY", "")
        region = os.environ.get("RUOYI_MEDIA_COS_REGION", "")
        bucket = os.environ.get("RUOYI_MEDIA_COS_BUCKET", "")
        session_token = os.environ.get("RUOYI_MEDIA_COS_SESSION_TOKEN") or None
        if not secret_id or not secret_key or any(character.isspace() for character in secret_id + secret_key):
            raise CosConfigurationError("COS credentials are not configured")
        if not _REGION.fullmatch(region) or not _BUCKET.fullmatch(bucket):
            raise CosConfigurationError("COS region or bucket is invalid")
        return cls(secret_id, secret_key, region, bucket, session_token)


class TencentCosObjectWriter(OutputObjectWriter):
    """Writes only Worker-assigned keys and returns the local immutable object ledger."""

    def __init__(self, settings: CosWriterSettings) -> None:
        try:
            from qcloud_cos import CosConfig, CosS3Client
        except ImportError as error:
            raise CosConfigurationError("COS SDK is not installed") from error
        config = CosConfig(
            Region=settings.region,
            SecretId=settings.secret_id,
            SecretKey=settings.secret_key,
            Token=settings.session_token,
            Scheme="https",
        )
        self._bucket = settings.bucket
        self._client = CosS3Client(config)

    @classmethod
    def from_environment(cls) -> "TencentCosObjectWriter":
        return cls(CosWriterSettings.from_environment())

    def upload(self, object_key: str, source: Path, content_type: str) -> StoredObject:
        if (
            object_key.startswith("/")
            or ".." in object_key.split("/")
            or not source.is_file()
            or content_type not in {"image/png", "application/json"}
        ):
            raise CosObjectStorageError("output object contract is invalid")
        try:
            with source.open("rb") as body:
                self._client.put_object(Bucket=self._bucket, Key=object_key, Body=body, ContentType=content_type)
        except Exception as error:
            raise CosObjectStorageError("COS output object write failed") from error
        return StoredObject(
            object_key=object_key,
            sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
            size_bytes=source.stat().st_size,
            content_type=content_type,
        )
