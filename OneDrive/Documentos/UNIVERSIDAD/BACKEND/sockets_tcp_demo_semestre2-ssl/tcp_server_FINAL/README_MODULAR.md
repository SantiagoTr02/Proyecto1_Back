# TCP Server (Modularizado)

Este es el mismo servidor/cliente TCP del proyecto original, reorganizado para ser **más modular** sin cambiar su comportamiento.

## ¿Qué cambió?
- Se introdujo una capa de **protocolo** (`org.breaze.protocol`), con una interfaz `Protocol` y la implementación existente `ServerProtocol`.
- El código de **red** vive en `org.breaze.server` (`TCPServer`, `ClientWorker`).
- El **logging de auditoría** vive en `org.breaze.logging` (`AuditLogger`).
- El arranque de la app está en `org.breaze.app.Main`, y ahora permite configurar el puerto desde `configuration.properties` con `SERVER_PORT` (por defecto 2020).
- **No se modificó la lógica funcional** de `ServerProtocol` (sigue leyendo los mismos CSV/FASTA, mismos comandos y respuestas).

## Estructura
```
org/breaze/app/Main.java
org/breaze/server/TCPServer.java
org/breaze/server/ClientWorker.java
org/breaze/protocol/Protocol.java
org/breaze/protocol/ServerProtocol.java
org/breaze/logging/AuditLogger.java
src/main/data_storage/... (igual que antes)
```

## Configuración
En `configuration.properties` ahora puedes definir opcionalmente:
```
SERVER_PORT=2020
SSL_CERTIFICATE_ROUTE=...
SSL_PASSWORD=...
```
> Si `SERVER_PORT` no existe, arranca en 2020 como antes.

## Cómo ejecutar
Con Maven (igual que el original):
```
mvn -q -DskipTests package
java -jar target/tcp_server-1.0-SNAPSHOT.jar
```
o desde IDE ejecuta `org.breaze.app.Main`.

## Próximos pasos (si quieres aún más modularidad)
- Separar las operaciones del protocolo en **handlers** por comando (p.ej. `RegisterUserHandler`, `QueryUserHandler`, etc.) y enrutar desde `ServerProtocol`.
- Extraer el acceso a CSV en una capa `repository` y la lógica de negocio en una capa `service`.
- Validaciones y parsing en utilidades dedicadas.
