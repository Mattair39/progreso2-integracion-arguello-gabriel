package edu.udla.integracion.progreso2.routes;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.udla.integracion.progreso2.model.CitaRequest;
import edu.udla.integracion.progreso2.service.CitaValidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  CitaIntegrationRoute — Rutas de integración Apache Camel para Salud360
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  Implementa los siguientes flujos (RFs) usando "spring-rabbitmq:" (Camel 4.x):
 *
 *  [RUTA 1]  direct:procesarCita
 *            Orquesta todo el flujo a partir de una CitaRequest válida:
 *            → RF2: Point-to-Point → billing.queue (direct exchange)
 *            → RF3: Publish/Subscribe → appointments.events (fanout exchange)
 *            → RF4: CSV legado → data/outbox/auditoria-citas.csv
 *
 *  [RUTA 2]  direct:enviarFacturacion
 *            RF2: Publica comando de facturación en billing.queue (P2P).
 *
 *  [RUTA 3]  direct:publicarEvento
 *            RF3: Publica evento CITA_CONFIRMADA en appointments.events (Pub/Sub).
 *
 *  [RUTA 4]  direct:generarCSV
 *            RF4: Escribe línea en auditoria-citas.csv para sistema legado.
 *
 *  [RUTA 5]  spring-rabbitmq:billing (consumidor Point-to-Point)
 *            Simula el sistema de Facturación.
 *
 *  [RUTA 6]  spring-rabbitmq:appointments.events?queues=notifications.queue (consumidor Pub/Sub)
 *            Simula el sistema de Notificaciones.
 *
 *  [RUTA 7]  spring-rabbitmq:appointments.events?queues=analytics.queue (consumidor Pub/Sub)
 *            Simula el sistema de Analítica.
 *
 *  Topología RabbitMQ declarada via Beans de Spring AMQP (@Bean):
 *    - Exchange direct "billing"        → cola "billing.queue"
 *    - Exchange fanout "appointments.events" → "notifications.queue" + "analytics.queue"
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CitaIntegrationRoute extends RouteBuilder {

    private final ObjectMapper objectMapper;
    private final CitaValidationService validationService;

    @Value("${salud360.data.outbox}")
    private String outboxDir;

    @Value("${salud360.data.auditoria.filename}")
    private String auditoriaFilename;

    // ════════════════════════════════════════════════════════════════════════
    //  Declaración de topología RabbitMQ mediante Spring AMQP Beans
    //  (Camel 4.x spring-rabbitmq usa la infraestructura AMQP de Spring)
    // ════════════════════════════════════════════════════════════════════════

    /** Cola de Facturación — Point-to-Point (un único consumidor) */
    @Bean
    public Queue billingQueue() {
        return QueueBuilder.durable("billing.queue").build();
    }

    /** Exchange directo para Facturación */
    @Bean
    public DirectExchange billingExchange() {
        return new DirectExchange("billing", true, false);
    }

    /** Binding: billing exchange → billing.queue */
    @Bean
    public Binding billingBinding(Queue billingQueue, DirectExchange billingExchange) {
        return BindingBuilder.bind(billingQueue).to(billingExchange).with("billing.queue");
    }

    /** Exchange fanout para Notificaciones + Analítica — Publish/Subscribe */
    @Bean
    public FanoutExchange appointmentsEventsExchange() {
        return new FanoutExchange("appointments.events", true, false);
    }

    /** Cola de Notificaciones */
    @Bean
    public Queue notificationsQueue() {
        return QueueBuilder.durable("notifications.queue").build();
    }

    /** Cola de Analítica */
    @Bean
    public Queue analyticsQueue() {
        return QueueBuilder.durable("analytics.queue").build();
    }

    /** Binding: appointments.events fanout → notifications.queue */
    @Bean
    public Binding notificationsBinding(Queue notificationsQueue, FanoutExchange appointmentsEventsExchange) {
        return BindingBuilder.bind(notificationsQueue).to(appointmentsEventsExchange);
    }

    /** Binding: appointments.events fanout → analytics.queue */
    @Bean
    public Binding analyticsBinding(Queue analyticsQueue, FanoutExchange appointmentsEventsExchange) {
        return BindingBuilder.bind(analyticsQueue).to(appointmentsEventsExchange);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Rutas Apache Camel
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public void configure() throws Exception {

        // ── Manejo global de excepciones en rutas Camel ──────────────────────
        onException(Exception.class)
            .log(LoggingLevel.ERROR,
                 "[CAMEL ERROR] Error en ruta de integración: ${exception.message}")
            .process(exchange -> {
                Exception ex = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
                CitaRequest cita = exchange.getIn().getBody(CitaRequest.class);
                String idCita = (cita != null) ? cita.getIdCita() : "DESCONOCIDO";
                String payload = (cita != null) ? toJson(cita) : "N/A";
                validationService.registrarRechazo(
                    idCita,
                    "Error en ruta Camel: " + (ex != null ? ex.getMessage() : "desconocido"),
                    payload
                );
            })
            .handled(true);

        // ════════════════════════════════════════════════════════════════════
        //  RUTA 1: Orquestador principal
        //  Disparado por CitaController via producerTemplate.sendBody("direct:procesarCita", cita)
        // ════════════════════════════════════════════════════════════════════
        from("direct:procesarCita")
            .routeId("ruta-orquestador-cita")
            .log(LoggingLevel.INFO,
                 ">>> [ORQUESTADOR] Iniciando flujo de integración para idCita=${body.idCita}")

            // Prepara los JSONs de cada sistema destino
            .process(exchange -> {
                CitaRequest cita = exchange.getIn().getBody(CitaRequest.class);

                // JSON para sistema de Facturación (RF2 - P2P)
                Map<String, Object> billing = new LinkedHashMap<>();
                billing.put("idCita",       cita.getIdCita());
                billing.put("paciente",     cita.getPaciente());
                billing.put("especialidad", cita.getEspecialidad());
                billing.put("valor",        cita.getValor());
                billing.put("tipoMensaje",  "COMANDO_FACTURAR_CITA");
                exchange.setProperty("billingJson", toJson(billing));

                // JSON para evento Pub/Sub (RF3 - Notificaciones + Analítica)
                Map<String, Object> evento = new LinkedHashMap<>();
                evento.put("idCita",       cita.getIdCita());
                evento.put("paciente",     cita.getPaciente());
                evento.put("correo",       cita.getCorreo());
                evento.put("especialidad", cita.getEspecialidad());
                evento.put("fechaCita",    cita.getFechaCita());
                evento.put("sede",         cita.getSede());
                evento.put("tipoEvento",   "CITA_CONFIRMADA");
                exchange.setProperty("eventoJson", toJson(evento));

                // Guarda referencia a la cita original para el CSV
                exchange.setProperty("citaOriginal", cita);
                exchange.setProperty("idCita",       cita.getIdCita());
            })

            // ── RF2: Enviar a Facturación (Point-to-Point) ───────────────────
            .to("direct:enviarFacturacion")

            // ── RF3: Publicar evento (Publish/Subscribe) ─────────────────────
            .to("direct:publicarEvento")

            // ── RF4: Generar CSV para sistema legado ─────────────────────────
            .to("direct:generarCSV")

            .log(LoggingLevel.INFO,
                 ">>> [ORQUESTADOR] Flujo completado exitosamente para idCita=${exchangeProperty.idCita}");

        // ════════════════════════════════════════════════════════════════════
        //  RUTA 2 — RF2: Point-to-Point Channel → billing.queue
        //
        //  Patrón: Point-to-Point Channel (EIP)
        //  El mensaje de facturación llega a UN ÚNICO consumidor.
        //  Usa exchange direct "billing" con routing key "billing.queue".
        // ════════════════════════════════════════════════════════════════════
        from("direct:enviarFacturacion")
            .routeId("ruta-facturacion-p2p")
            .log(LoggingLevel.INFO,
                 ">>> [RF2 - P2P] Enviando mensaje a billing.queue | idCita=${exchangeProperty.idCita}")
            .process(exchange ->
                exchange.getIn().setBody(exchange.getProperty("billingJson", String.class))
            )
            // spring-rabbitmq: exchange + routingKey
            .to("spring-rabbitmq:billing?routingKey=billing.queue")
            .log(LoggingLevel.INFO,
                 ">>> [RF2 - P2P] Mensaje publicado en billing.queue ✓");

        // ════════════════════════════════════════════════════════════════════
        //  RUTA 3 — RF3: Publish/Subscribe Channel → appointments.events (fanout)
        //
        //  Patrón: Publish/Subscribe Channel (EIP)
        //  El evento CITA_CONFIRMADA se distribuye a MÚLTIPLES consumidores:
        //    - notifications.queue (Sistema de Notificaciones)
        //    - analytics.queue    (Sistema de Analítica)
        //  El exchange fanout NO necesita routing key.
        // ════════════════════════════════════════════════════════════════════
        from("direct:publicarEvento")
            .routeId("ruta-pubsub-evento")
            .log(LoggingLevel.INFO,
                 ">>> [RF3 - PUB/SUB] Publicando evento en appointments.events | idCita=${exchangeProperty.idCita}")
            .process(exchange ->
                exchange.getIn().setBody(exchange.getProperty("eventoJson", String.class))
            )
            // Fanout exchange — distribuye a TODAS las colas vinculadas
            .to("spring-rabbitmq:appointments.events")
            .log(LoggingLevel.INFO,
                 ">>> [RF3 - PUB/SUB] Evento CITA_CONFIRMADA publicado en appointments.events ✓");

        // ════════════════════════════════════════════════════════════════════
        //  RUTA 4 — RF4: Generación de CSV para sistema legado de auditoría
        //
        //  Escribe/agrega una línea en data/outbox/auditoria-citas.csv.
        //  El sistema legado solo acepta archivos CSV en carpeta compartida.
        // ════════════════════════════════════════════════════════════════════
        from("direct:generarCSV")
            .routeId("ruta-csv-legado")
            .log(LoggingLevel.INFO,
                 ">>> [RF4 - CSV] Escribiendo en auditoria-citas.csv | idCita=${exchangeProperty.idCita}")
            .process(exchange -> {
                CitaRequest cita = exchange.getProperty("citaOriginal", CitaRequest.class);
                escribirCSV(cita);
            })
            .log(LoggingLevel.INFO,
                 ">>> [RF4 - CSV] Registro escrito en auditoria-citas.csv ✓");

        // ════════════════════════════════════════════════════════════════════
        //  RUTA 5 — Consumidor: Sistema de Facturación (Point-to-Point)
        //
        //  Simula al receptor del sistema de facturación.
        //  En producción este consumer estaría en un microservicio independiente.
        // ════════════════════════════════════════════════════════════════════
        from("spring-rabbitmq:billing?queues=billing.queue")
            .routeId("consumidor-facturacion")
            .log(LoggingLevel.INFO,
                 ">>> [FACTURACIÓN] Orden de cobro recibida desde billing.queue:")
            .log(LoggingLevel.INFO, "    Payload: ${body}");

        // ════════════════════════════════════════════════════════════════════
        //  RUTA 6 — Consumidor: Sistema de Notificaciones (Pub/Sub)
        //
        //  Simula al receptor del sistema de notificaciones al paciente.
        // ════════════════════════════════════════════════════════════════════
        from("spring-rabbitmq:appointments.events?queues=notifications.queue")
            .routeId("consumidor-notificaciones")
            .log(LoggingLevel.INFO,
                 ">>> [NOTIFICACIONES] Evento recibido desde notifications.queue:")
            .log(LoggingLevel.INFO, "    Payload: ${body}");

        // ════════════════════════════════════════════════════════════════════
        //  RUTA 7 — Consumidor: Sistema de Analítica (Pub/Sub)
        //
        //  Simula al receptor del sistema de indicadores operativos.
        // ════════════════════════════════════════════════════════════════════
        from("spring-rabbitmq:appointments.events?queues=analytics.queue")
            .routeId("consumidor-analitica")
            .log(LoggingLevel.INFO,
                 ">>> [ANALÍTICA] Evento recibido desde analytics.queue:")
            .log(LoggingLevel.INFO, "    Payload: ${body}");
    }

    // ── Utilidades privadas ──────────────────────────────────────────────────

    /**
     * Escribe o agrega una línea al CSV de auditoría.
     * RF4: data/outbox/auditoria-citas.csv
     *
     * Formato:
     *   idCita,paciente,correo,especialidad,fechaCita,sede,valor
     */
    private void escribirCSV(CitaRequest cita) {
        try {
            Path outbox = Paths.get(outboxDir);
            if (!Files.exists(outbox)) {
                Files.createDirectories(outbox);
            }

            Path csvFile = outbox.resolve(auditoriaFilename);
            boolean esNuevo = !Files.exists(csvFile);

            try (FileWriter fw = new FileWriter(csvFile.toFile(), true)) {
                if (esNuevo) {
                    fw.write("idCita,paciente,correo,especialidad,fechaCita,sede,valor\n");
                }
                fw.write(String.format("%s,%s,%s,%s,%s,%s,%.2f%n",
                        escapeCsv(cita.getIdCita()),
                        escapeCsv(cita.getPaciente()),
                        escapeCsv(cita.getCorreo()),
                        escapeCsv(cita.getEspecialidad()),
                        escapeCsv(cita.getFechaCita()),
                        escapeCsv(cita.getSede()),
                        cita.getValor()));
            }

            log.info("[CSV] Registro escrito en: {}", csvFile.toAbsolutePath());

        } catch (IOException e) {
            log.error("[CSV ERROR] No se pudo escribir en auditoria-citas.csv: {}", e.getMessage());
            throw new RuntimeException("Error al escribir CSV de auditoría: " + e.getMessage(), e);
        }
    }

    /** Escapa valores CSV: envuelve en comillas si contiene coma, comilla o salto de línea. */
    private String escapeCsv(String valor) {
        if (valor == null) return "";
        if (valor.contains(",") || valor.contains("\"") || valor.contains("\n")) {
            return "\"" + valor.replace("\"", "\"\"") + "\"";
        }
        return valor;
    }

    /** Serializa un objeto a JSON string. */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}
