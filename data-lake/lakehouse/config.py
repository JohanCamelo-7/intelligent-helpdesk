"""Configuración del lakehouse (variables de entorno)."""
from __future__ import annotations

import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class LakeConfig:
    bucket: str = field(default_factory=lambda: os.getenv("LAKE_BUCKET", "lake"))
    s3_endpoint: str = field(default_factory=lambda: os.getenv("S3_ENDPOINT", "http://seaweedfs:8333"))
    s3_region: str = field(default_factory=lambda: os.getenv("S3_REGION", "us-east-1"))
    s3_access_key: str = field(default_factory=lambda: os.getenv("S3_ACCESS_KEY", "lake"))
    s3_secret_key: str = field(default_factory=lambda: os.getenv("S3_SECRET_KEY", ""))
    catalog_uri: str = field(default_factory=lambda: os.getenv("LAKE_CATALOG_URI", "jdbc:postgresql://postgres:5432/lake_catalog"))
    catalog_user: str = field(default_factory=lambda: os.getenv("LAKE_DB_USER", "lake"))
    catalog_password: str = field(default_factory=lambda: os.getenv("LAKE_DB_PASSWORD", ""))
    helpdesk_dsn: str = field(default_factory=lambda: os.getenv("HELPDESK_DB_DSN", ""))
    timezone: str = field(default_factory=lambda: os.getenv("LAKE_TIMEZONE", "America/Bogota"))
    pseudonym_salt: str = field(default_factory=lambda: os.getenv("PSEUDONYM_SALT", "cambia-sal-seudonimos"))
    jars_dir: str = field(default_factory=lambda: os.getenv("SPARK_JARS_DIR", "/opt/spark-jars"))
    # Modo local (pruebas): tablas Parquet y Landing en carpetas del disco
    local_dir: str | None = field(default_factory=lambda: os.getenv("LAKE_LOCAL_DIR"))
    landing_retention_days: int = 90
    reincidence_days: int = 30
    snapshot_max_days: int = 400

    @property
    def warehouse(self) -> str:
        return f"s3://{self.bucket}/warehouse"


config = LakeConfig()
