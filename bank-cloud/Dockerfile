# syntax=docker/dockerfile:1.7
# ===========================================================================
# Imagenes de los microservicios de bank-cloud
# ===========================================================================
# UN Dockerfile para todos, con dos etapas:
#
#   build     compila el proyecto Maven completo UNA vez y separa en capas el
#             jar de cada servicio ejecutable.
#   servicio  toma las capas de UN modulo (build-arg MODULO) sobre una JRE.
#
# docker-compose.yaml construye cada imagen con target 'servicio' y su MODULO.
# BuildKit comparte la etapa 'build' entre todas: no depende de MODULO, asi que
# Maven corre una sola vez aunque se construyan diez imagenes.
#
# Por que compilar DENTRO de Docker y no copiar un jar hecho afuera: un clon
# limpio del repositorio se construye con 'docker compose build' en cualquier
# maquina que tenga Docker, sin JDK ni Maven instalados. Es lo que permite
# levantar el ecosistema en una VM de la nube tal como sale de GitHub.
# ===========================================================================

# ---------------------------------------------------------------------------
# Etapa 1: compilacion
# ---------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src

COPY . .

# La cache de ~/.m2 sobrevive entre construcciones: la primera baja las
# dependencias (varios minutos), las siguientes solo recompilan.
# 'clean' no es decorativo: ver la nota de Eclipse JDT en el README.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q clean package -DskipTests

# Spring Boot 3.3+ separa un jar en capas que cambian con distinta frecuencia:
# dependencias (casi nunca), el cargador, snapshots y la aplicacion (cada
# cambio). Docker cachea cada capa por separado, asi que tocar una clase no
# obliga a volver a subir los ~80 MB de dependencias.
RUN set -e; \
    for m in config-server eureka-server broker-mensajeria auth-server \
             ms-cuentas ms-transferencias ms-auditoria \
             bff-web bff-movil bff-cajero; do \
        java -Djarmode=tools -jar "$m/target/$m-0.0.1-SNAPSHOT.jar" \
             extract --layers --launcher --destination "/capas/$m"; \
    done

# ---------------------------------------------------------------------------
# Etapa 2: imagen de un servicio
# ---------------------------------------------------------------------------
# JRE y no JDK: en ejecucion no se compila nada, y la imagen pesa la mitad.
FROM eclipse-temurin:21-jre AS servicio

ARG MODULO
LABEL org.opencontainers.image.title="bank-cloud/${MODULO}" \
      org.opencontainers.image.description="PBY2203 Experiencia 3 - ${MODULO}"

# curl lo usa el HEALTHCHECK que declara docker-compose.yaml sobre
# /actuator/health. Se instala explicito para no depender de lo que traiga o
# deje de traer la imagen base.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Usuario sin privilegios: si el proceso queda comprometido, no es root dentro
# del contenedor.
RUN groupadd --system banco && useradd --system --gid banco --home-dir /app banco

WORKDIR /app

# Las rutas relativas de la configuracion (./certs, ./datos-broker,
# ./basedatos) quedan bajo /app. Se crean aqui, con dueno 'banco', porque un
# volumen con nombre copia el dueno de la carpeta de la imagen al montarse; si
# la carpeta no existiera, el volumen quedaria de root y el servicio no podria
# escribir.
RUN mkdir -p certs datos-broker basedatos && chown -R banco:banco /app

# Una capa por COPY, de la que menos cambia a la que mas.
COPY --from=build --chown=banco:banco /capas/${MODULO}/dependencies/          ./
COPY --from=build --chown=banco:banco /capas/${MODULO}/spring-boot-loader/    ./
COPY --from=build --chown=banco:banco /capas/${MODULO}/snapshot-dependencies/ ./
COPY --from=build --chown=banco:banco /capas/${MODULO}/application/           ./

USER banco

# La memoria la fija docker-compose.yaml con un limite por contenedor, y la JVM
# dimensiona el heap como porcentaje de ESE limite. Es la misma idea que el
# -Xmx por servicio de comun.ps1, pero decidido por el orquestador: si el
# limite sube, el heap sube solo. Sin limite, la JVM tomaria 1/4 de la RAM de
# la maquina en cada contenedor.
#   ExitOnOutOfMemoryError: un servicio sin memoria termina en vez de quedar
#   vivo a medias; 'restart' de compose lo levanta de nuevo limpio.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
