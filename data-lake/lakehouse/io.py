"""Sesión de Spark y acceso a tablas.

- Modo Iceberg (producción): catálogo JDBC en PostgreSQL, archivos Parquet en SeaweedFS (S3FileIO).
- Modo local (pruebas): mismas operaciones sobre carpetas Parquet en disco.
Las transformaciones no saben en qué modo corren.
"""
from __future__ import annotations

import glob
import json
import os
import shutil
from pathlib import Path

from pyspark.sql import DataFrame, SparkSession

from .config import LakeConfig, config

NAMESPACES = ("bronze", "silver", "gold", "ops")


def build_spark(cfg: LakeConfig = config, app: str = "lakehouse") -> SparkSession:
    b = (SparkSession.builder.appName(app)
         .master(os.getenv("SPARK_MASTER", "local[2]"))
         .config("spark.sql.session.timeZone", cfg.timezone)
         .config("spark.sql.shuffle.partitions", "4")
         .config("spark.ui.enabled", "false")
         .config("spark.driver.memory", os.getenv("SPARK_DRIVER_MEMORY", "1g")))
    if cfg.local_dir is None:
        jars = ",".join(sorted(glob.glob(f"{cfg.jars_dir}/*.jar")))
        b = (b.config("spark.jars", jars)
             .config("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
             .config("spark.sql.catalog.lake", "org.apache.iceberg.spark.SparkCatalog")
             .config("spark.sql.catalog.lake.catalog-impl", "org.apache.iceberg.jdbc.JdbcCatalog")
             .config("spark.sql.catalog.lake.uri", cfg.catalog_uri)
             .config("spark.sql.catalog.lake.jdbc.user", cfg.catalog_user)
             .config("spark.sql.catalog.lake.jdbc.password", cfg.catalog_password)
             .config("spark.sql.catalog.lake.jdbc.schema-version", "V1")
             .config("spark.sql.catalog.lake.warehouse", cfg.warehouse)
             .config("spark.sql.catalog.lake.io-impl", "org.apache.iceberg.aws.s3.S3FileIO")
             .config("spark.sql.catalog.lake.s3.endpoint", cfg.s3_endpoint)
             .config("spark.sql.catalog.lake.s3.path-style-access", "true")
             .config("spark.sql.catalog.lake.s3.access-key-id", cfg.s3_access_key)
             .config("spark.sql.catalog.lake.s3.secret-access-key", cfg.s3_secret_key)
             .config("spark.sql.catalog.lake.client.region", cfg.s3_region))
    return b.getOrCreate()


class LakeIO:
    def __init__(self, spark: SparkSession, cfg: LakeConfig = config) -> None:
        self.spark = spark
        self.cfg = cfg
        self.local = cfg.local_dir is not None
        if not self.local:
            for ns in NAMESPACES:
                spark.sql(f"CREATE NAMESPACE IF NOT EXISTS lake.{ns}")

    # ---------------------------------------------------------------- utilidades
    def _path(self, table: str) -> str:
        ns, name = table.split(".")
        return str(Path(self.cfg.local_dir) / "warehouse" / ns / name)

    def ident(self, table: str) -> str:
        return f"lake.{table}"

    def exists(self, table: str) -> bool:
        if self.local:
            return Path(self._path(table)).exists()
        return self.spark.catalog.tableExists(self.ident(table))

    # ---------------------------------------------------------------- lectura / escritura
    def read(self, table: str) -> DataFrame | None:
        if not self.exists(table):
            return None
        return self.spark.read.parquet(self._path(table)) if self.local else self.spark.table(self.ident(table))

    def overwrite(self, table: str, df: DataFrame) -> int:
        """Reemplaza la tabla completa (transacción atómica en Iceberg)."""
        df = df.localCheckpoint()  # materializa: evita leer y escribir la misma ruta a la vez
        n = df.count()
        if self.local:
            tmp = self._path(table) + "__tmp"
            df.write.mode("overwrite").parquet(tmp)
            shutil.rmtree(self._path(table), ignore_errors=True)
            os.makedirs(os.path.dirname(self._path(table)), exist_ok=True)
            os.replace(tmp, self._path(table))
            self.spark.catalog.refreshByPath(self._path(table))   # Spark guarda la lista de archivos anterior
        else:
            df.writeTo(self.ident(table)).using("iceberg").tableProperty("format-version", "2").createOrReplace()
        return n

    def append(self, table: str, df: DataFrame, partition_col: str | None = None) -> int:
        df = df.localCheckpoint()
        n = df.count()
        if n == 0 and self.exists(table):
            return 0
        if self.local:
            df.write.mode("append").parquet(self._path(table))
        elif self.exists(table):
            df.writeTo(self.ident(table)).append()
        else:
            w = df.writeTo(self.ident(table)).using("iceberg").tableProperty("format-version", "2")
            if partition_col:
                from pyspark.sql import functions as F
                w = w.partitionedBy(F.col(partition_col))
            w.create()
        return n


# ------------------------------------------------------------------------ Landing
class LandingSource:
    """Lista y lee archivos JSONL de la zona Landing (S3 o carpeta local)."""

    def __init__(self, cfg: LakeConfig = config, s3=None) -> None:
        self.cfg = cfg
        self.local = cfg.local_dir is not None
        self._s3 = s3

    @property
    def s3(self):
        if self._s3 is None:
            import boto3
            from botocore.config import Config
            self._s3 = boto3.client(
                "s3", endpoint_url=self.cfg.s3_endpoint, region_name=self.cfg.s3_region,
                aws_access_key_id=self.cfg.s3_access_key, aws_secret_access_key=self.cfg.s3_secret_key,
                config=Config(s3={"addressing_style": "path"}, request_checksum_calculation="when_required",
                              response_checksum_validation="when_required"))
        return self._s3

    def list(self, prefix: str) -> list[str]:
        if self.local:
            root = Path(self.cfg.local_dir)
            return sorted(str(p.relative_to(root)) for p in (root / prefix).rglob("*.jsonl")) if (root / prefix).exists() else []
        keys, token = [], None
        while True:
            kw = {"Bucket": self.cfg.bucket, "Prefix": prefix}
            if token:
                kw["ContinuationToken"] = token
            resp = self.s3.list_objects_v2(**kw)
            keys += [o["Key"] for o in resp.get("Contents", []) if o["Key"].endswith(".jsonl")]
            if not resp.get("IsTruncated"):
                return sorted(keys)
            token = resp["NextContinuationToken"]

    def read_lines(self, key: str) -> list[str]:
        if self.local:
            return (Path(self.cfg.local_dir) / key).read_text(encoding="utf-8").splitlines()
        body = self.s3.get_object(Bucket=self.cfg.bucket, Key=key)["Body"].read().decode("utf-8")
        return body.splitlines()

    def write(self, key: str, rows: list[dict]) -> None:
        data = "\n".join(json.dumps(r, ensure_ascii=False, default=str) for r in rows) + "\n"
        if self.local:
            p = Path(self.cfg.local_dir) / key
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(data, encoding="utf-8")
        else:
            self.s3.put_object(Bucket=self.cfg.bucket, Key=key, Body=data.encode(), ContentType="application/x-ndjson")

    def write_lines(self, key: str, lines: list[str]) -> None:
        """Escribe líneas tal cual (sin volver a serializar): usado por la purga para no alterar lo que conserva."""
        data = "\n".join(lines) + "\n"
        if self.local:
            p = Path(self.cfg.local_dir) / key
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(data, encoding="utf-8")
        else:
            self.s3.put_object(Bucket=self.cfg.bucket, Key=key, Body=data.encode(), ContentType="application/x-ndjson")

    def delete(self, keys: list[str]) -> None:
        if self.local:
            for k in keys:
                (Path(self.cfg.local_dir) / k).unlink(missing_ok=True)
            return
        for i in range(0, len(keys), 1000):
            self.s3.delete_objects(Bucket=self.cfg.bucket, Delete={"Objects": [{"Key": k} for k in keys[i:i + 1000]]})
