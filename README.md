# Progreso 2 – Integración de Sistemas: Salud360
### Gestión de Citas Médicas con Apache Camel + RabbitMQ

---

## 1. Nombre del Estudiante

**Gabriel Argüello** — Universidad de las Américas (UDLA)
Materia: Integración de Sistemas | Progreso 2

---

## 2. Descripción Breve de la Solución

Solución de integración para la organización **Salud360**, que gestiona una red de centros médicos. El sistema expone una **API REST** para registrar solicitudes de cita médica y, mediante **Apache Camel**, orquesta automáticamente la distribución del evento a:

| Sistema Destino | Mecanismo |
|---|---|
| **Sistema de Facturación** | Cola RabbitMQ `billing.queue` (Point-to-Point) |
| **Sistema de Notificaciones** | Exchange fanout `appointments.events` → `notifications.queue` (Pub/Sub) |
| **Sistema de Analítica** | Exchange fanout `appointments.events` → `analytics.queue` (Pub/Sub) |
| **Sistema Legado de Auditoría** | Archivo CSV `data/outbox/auditoria-citas.csv` |

Los errores son registrados en `data/errors/citas-rechazadas.log`.

---

## 3. Tecnologías Utilizadas

| Tecnología | Versión | Propósito |
|---|---|---|
| Java | 17 | Lenguaje de programación |
| Spring Boot | 3.2.5 | Framework base de la aplicación |
| Apache Camel | 4.5.0 | Motor de rutas de integración |
| RabbitMQ | 3.13 | Broker de mensajería AMQP |
| SpringDoc OpenAPI | 2.5.0 | Generación automática de Swagger UI |
| Jackson | (Spring) | Serialización/deserialización JSON |
| Docker + Compose | - | Contenedor para RabbitMQ |
| Maven | 3.9+ | Gestión de dependencias y build |
| Lombok | - | Reducción de código boilerplate |

---

## 4. Instrucciones para Levantar RabbitMQ

### Prerequisito
Tener **Docker Desktop** instalado y en ejecución.

### Comando

```bash
# Desde la raíz del proyecto
docker-compose up -d
```

### Verificar que está corriendo

```bash
docker ps
# Debe aparecer: salud360-rabbitmq
```

### Panel de administración web

Abrir en el navegador:
```
http://localhost:15672
```
- **Usuario:** `guest`
- **Contraseña:** `guest`

### Detener RabbitMQ

```bash
docker-compose down
```

---

## 5. Instrucciones para Ejecutar la Aplicación

### Prerequisitos

1. Java 17+ instalado (`java -version`)
2. Maven 3.9+ instalado (`mvn -version`)
3. RabbitMQ corriendo (`docker-compose up -d`)

### Compilar y ejecutar

```bash
# Desde la raíz del proyecto
mvn spring-boot:run
```

### Verificar que arrancó correctamente

La consola debe mostrar:
```
Started Progreso2Application in X.XXX seconds
```

La aplicación corre en: `http://localhost:8080`

---

## 6. Endpoints Disponibles

| Método | URL | Descripción |
|---|---|---|
| `POST` | `http://localhost:8080/api/citas` | Registrar una nueva cita médica |
| `GET`  | `http://localhost:8080/api/citas` | Listar citas aceptadas en la sesión |

### Documentación interactiva (Swagger UI)

```
http://localhost:8080/swagger-ui/index.html
```

### Especificación OpenAPI (JSON)

```
http://localhost:8080/api-docs
```

### Swagger UI estático (sin servidor)

```
docs/swagger-ui.html   (abrir directamente en el navegador)
```

---

## 7. Ejemplo de Request Válido

```bash
curl -X POST http://localhost:8080/api/citas \
  -H "Content-Type: application/json" \
  -d '{
    "idCita":       "CITA-1001",
    "paciente":     "Ana Torres",
    "correo":       "ana.torres@email.com",
    "especialidad": "Cardiología",
    "fechaCita":    "2026-06-15",
    "sede":         "Centro Norte",
    "valor":        45.50
  }'
```

### Respuesta esperada (HTTP 202 Accepted)

```json
{
  "status": "ACEPTADA",
  "idCita": "CITA-1001",
  "mensaje": "Cita registrada y flujo de integración iniciado correctamente"
}
```

---

## 8. Ejemplos de Request Inválido

### 8.1 Campos faltantes (HTTP 400)

```bash
curl -X POST http://localhost:8080/api/citas \
  -H "Content-Type: application/json" \
  -d '{
    "idCita":   "",
    "paciente": "",
    "correo":   "no-es-email"
  }'
```

**Respuesta (HTTP 400 Bad Request):**
```json
{
  "status":  "RECHAZADA",
  "idCita":  "N/A",
  "errores": [
    "paciente: El campo paciente es obligatorio",
    "correo: El campo correo es obligatorio",
    "idCita: El campo idCita es obligatorio",
    "especialidad: El campo especialidad es obligatorio",
    "fechaCita: El campo fechaCita es obligatorio",
    "sede: El campo sede es obligatorio",
    "valor: El campo valor es obligatorio"
  ]
}
```

### 8.2 Fecha pasada (HTTP 422)

```bash
curl -X POST http://localhost:8080/api/citas \
  -H "Content-Type: application/json" \
  -d '{
    "idCita":       "CITA-9999",
    "paciente":     "Carlos Pérez",
    "correo":       "carlos@email.com",
    "especialidad": "Pediatría",
    "fechaCita":    "2020-01-01",
    "sede":         "Centro Sur",
    "valor":        30.00
  }'
```

**Respuesta (HTTP 422 Unprocessable Entity):**
```json
{
  "status": "RECHAZADA",
  "idCita": "CITA-9999",
  "error":  "La fechaCita [2020-01-01] no puede ser una fecha pasada"
}
```

### 8.3 Valor negativo (HTTP 400)

```json
{
  "idCita": "CITA-2000",
  "paciente": "María Gómez",
  "correo": "maria@email.com",
  "especialidad": "Neurología",
  "fechaCita": "2026-07-10",
  "sede": "Centro Este",
  "valor": -5.00
}
```

---

## 9. Explicación de Patrones de Integración

### 🔵 Point-to-Point Channel (RF2)

**Dónde:** Cola `billing.queue` en RabbitMQ con `exchangeType=direct`.

**Cómo funciona:** Cuando se registra una cita válida, la ruta `direct:enviarFacturacion` de Apache Camel envía un mensaje JSON al exchange direct `billing.queue`. Este mensaje es consumido **exclusivamente por un único consumidor** (la ruta `consumidor-facturacion`), garantizando que la orden de cobro se genere **una sola vez**.

```
API POST /api/citas
    └─► direct:enviarFacturacion (Camel)
            └─► billing.queue (RabbitMQ - direct)
                    └─► [Sistema de Facturación] ← ÚNICO CONSUMIDOR
```

**Mensaje de facturación:**
```json
{
  "idCita":       "CITA-1001",
  "paciente":     "Ana Torres",
  "especialidad": "Cardiología",
  "valor":        45.50,
  "tipoMensaje":  "COMANDO_FACTURAR_CITA"
}
```

---

### 🟢 Publish/Subscribe Channel (RF3)

**Dónde:** Exchange `appointments.events` de tipo `fanout` en RabbitMQ.

**Cómo funciona:** La ruta `direct:publicarEvento` de Apache Camel publica el evento `CITA_CONFIRMADA` en el exchange fanout. RabbitMQ replica automáticamente el mismo mensaje a **todas las colas vinculadas**: `notifications.queue` y `analytics.queue`. Cada sistema recibe su propia copia independiente.

```
API POST /api/citas
    └─► direct:publicarEvento (Camel)
            └─► appointments.events (RabbitMQ - fanout)
                    ├─► notifications.queue → [Sistema de Notificaciones]
                    └─► analytics.queue    → [Sistema de Analítica]
```

**Evento publicado:**
```json
{
  "idCita":       "CITA-1001",
  "paciente":     "Ana Torres",
  "correo":       "ana.torres@email.com",
  "especialidad": "Cardiología",
  "fechaCita":    "2026-06-15",
  "sede":         "Centro Norte",
  "tipoEvento":   "CITA_CONFIRMADA"
}
```

---

### 📄 Transferencia de Archivos – Sistema Legado (RF4)

**Dónde:** `data/outbox/auditoria-citas.csv`

**Cómo funciona:** La ruta `direct:generarCSV` de Apache Camel escribe (o agrega) una línea en el archivo CSV cada vez que se procesa una cita válida. El archivo se crea automáticamente si no existe, con la cabecera correspondiente.

**Formato del archivo:**
```
idCita,paciente,correo,especialidad,fechaCita,sede,valor
CITA-1001,Ana Torres,ana.torres@email.com,Cardiología,2026-06-15,Centro Norte,45.50
```

---

### ⚠️ Manejo de Errores (RF5)

**Dónde se manejan:**

| Nivel | Mecanismo |
|---|---|
| **Validación estructural** | Bean Validation (`@NotBlank`, `@Email`, etc.) en `CitaRequest` → HTTP 400 |
| **Validación de negocio** | `CitaValidationService.validar()` → HTTP 422 (ej: fecha pasada) |
| **Error en rutas Camel** | `onException(Exception.class)` global en `CitaIntegrationRoute` → HTTP 500 |

**Archivo de errores:** `data/errors/citas-rechazadas.log`

**Formato de cada entrada:**
```
[2026-06-04 10:23:45] | idCita: CITA-9999 | Motivo: La fechaCita [2020-01-01] no puede ser una fecha pasada | Payload: {"idCita":"CITA-9999",...}
```

---

## 10. Evidencia Esperada para Verificar el Funcionamiento

Para demostrar el correcto funcionamiento, se deben presentar las siguientes capturas en `docs/capturas/`:

| # | Evidencia | Descripción |
|---|---|---|
| 1 | `swagger-ui.png` | Interfaz Swagger UI abierta en el navegador |
| 2 | `request-valido.png` | Ejecución de POST con datos válidos → respuesta 202 |
| 3 | `request-invalido.png` | Ejecución de POST con datos inválidos → respuesta 400 |
| 4 | `rabbitmq-panel.png` | Panel RabbitMQ mostrando exchanges y colas creadas |
| 5 | `billing-queue.png` | Mensaje en `billing.queue` (Panel > Queues > billing.queue > Get Messages) |
| 6 | `notifications-queue.png` | Mensaje en `notifications.queue` |
| 7 | `analytics-queue.png` | Mensaje en `analytics.queue` |
| 8 | `auditoria-csv.png` | Contenido del archivo `data/outbox/auditoria-citas.csv` |
| 9 | `error-log.png` | Contenido del archivo `data/errors/citas-rechazadas.log` con al menos un error |
| 10 | `consola-camel.png` | Logs de consola mostrando las rutas Camel en acción |

---

## 11. Arquitectura del Sistema

```
┌──────────────────────────────────────────────────────────────────┐
│               Cliente (Swagger / curl / Postman)                  │
└────────────────────────┬─────────────────────────────────────────┘
                         │ POST /api/citas
                         ▼
┌──────────────────────────────────────────────────────────────────┐
│                    CitaController (Spring Boot)                   │
│  ① Validación Bean (@NotBlank, @Email, @DecimalMin)              │
│  ② Validación de negocio (CitaValidationService)                 │
│  ③ Dispara: producerTemplate.sendBody("direct:procesarCita")    │
└────────────────────────┬─────────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────────┐
│              CitaIntegrationRoute (Apache Camel)                  │
│                                                                  │
│  direct:procesarCita (Orquestador)                               │
│         ├─► direct:enviarFacturacion ──► billing.queue (P2P)     │
│         ├─► direct:publicarEvento    ──► appointments.events     │
│         │                                    ├─► notifications.queue │
│         │                                    └─► analytics.queue     │
│         └─► direct:generarCSV       ──► data/outbox/auditoria-citas.csv
└──────────────────────────────────────────────────────────────────┘
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
   [Facturación]  [Notificaciones] [Analítica]
   (consumidor)   (consumidor)    (consumidor)
```

---

## 12. Estructura del Proyecto

```
progreso2-integracion-salud360/
├── README.md
├── docker-compose.yml
├── pom.xml
├── src/
│   └── main/
│       ├── java/
│       │   └── edu/udla/integracion/progreso2/
│       │       ├── Progreso2Application.java
│       │       ├── controller/
│       │       │   └── CitaController.java
│       │       ├── model/
│       │       │   └── CitaRequest.java
│       │       ├── routes/
│       │       │   └── CitaIntegrationRoute.java
│       │       └── service/
│       │           └── CitaValidationService.java
│       └── resources/
│           └── application.properties
├── data/
│   ├── outbox/
│   │   └── auditoria-citas.csv
│   └── errors/
│       └── citas-rechazadas.log
└── docs/
    ├── openapi.yaml
    ├── swagger-ui.html
    └── capturas/
```
