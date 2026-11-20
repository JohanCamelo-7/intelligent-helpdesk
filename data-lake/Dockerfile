# Airflow 3 + Spark (PySpark) + Iceberg en un solo contenedor (modo standalone).
FROM apache/airflow:3.3.2-python3.12

USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends default-jre-headless curl \
 && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/usr/lib/jvm/default-java

# Librerías Java de Iceberg (versiones fijas) para Spark 4.1 / Scala 2.13
ARG ICEBERG=1.11.0
RUN mkdir -p /opt/spark-jars && cd /opt/spark-jars \
 && curl -fsSLO https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-spark-runtime-4.1_2.13/${ICEBERG}/iceberg-spark-runtime-4.1_2.13-${ICEBERG}.jar \
 && curl -fsSLO https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-aws-bundle/${ICEBERG}/iceberg-aws-bundle-${ICEBERG}.jar \
 && curl -fsSLO https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar \
 && mkdir -p /opt/lakehouse && chown -R airflow:root /opt/lakehouse /opt/spark-jars

USER airflow
COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir "apache-airflow==${AIRFLOW_VERSION}" -r /tmp/requirements.txt
COPY --chown=airflow:root lakehouse /opt/lakehouse/lakehouse
COPY --chown=airflow:root quality /opt/lakehouse/quality
COPY --chown=airflow:root dags /opt/airflow/dags
ENV PYTHONPATH=/opt/lakehouse SPARK_JARS_DIR=/opt/spark-jars SPARK_DRIVER_MEMORY=1g

# La contraseña del usuario "admin" de Airflow viene de AIRFLOW_ADMIN_PASSWORD.
CMD ["bash", "-c", "printf '{\"admin\": \"%s\"}' \"$AIRFLOW_ADMIN_PASSWORD\" > /opt/airflow/simple_auth_passwords.json && exec airflow standalone"]
