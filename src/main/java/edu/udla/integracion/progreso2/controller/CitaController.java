package edu.udla.integracion.progreso2.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.udla.integracion.progreso2.model.CitaRequest;
import edu.udla.integracion.progreso2.service.CitaValidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.ProducerTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Controller REST para la gestión de citas médicas.
 *
 * Expone:
 *   POST /api/citas  – RF1: Registrar solicitud de cita
 *   GET  /api/citas  – Consultar citas aceptadas (trazabilidad)
 *
 * El flujo de integración se inicia enviando la cita al endpoint
 * interno "direct:procesarCita" de Apache Camel.
 */
@Slf4j
@RestController
@RequestMapping("/api/citas")
@RequiredArgsConstructor
@Tag(name = "Citas Médicas", description = "Endpoints para el registro y consulta de citas médicas en la red Salud360")
public class CitaController {

    private final ProducerTemplate producerTemplate;
    private final CitaValidationService validationService;
    private final ObjectMapper objectMapper;

    // ══════════════════════════════════════════════════════════════════════════
    //  RF1 – POST /api/citas — Registrar solicitud de cita
    // ══════════════════════════════════════════════════════════════════════════

    @Operation(
        summary     = "Registrar una nueva cita médica",
        description = "Recibe una solicitud de cita, la valida y, si es válida, inicia el flujo "
                    + "de integración: facturación (Point-to-Point), notificaciones y analítica "
                    + "(Publish/Subscribe) y generación de archivo CSV para auditoría (sistema legado)."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "202",
            description  = "Cita aceptada e integración iniciada",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = @ExampleObject(value = """
                    {
                      "status": "ACEPTADA",
                      "idCita": "CITA-1001",
                      "mensaje": "Cita registrada y flujo de integración iniciado correctamente"
                    }"""))
        ),
        @ApiResponse(
            responseCode = "400",
            description  = "Datos de la solicitud inválidos o incompletos",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = @ExampleObject(value = """
                    {
                      "status": "RECHAZADA",
                      "idCita": "N/A",
                      "errores": ["El campo paciente es obligatorio", "El campo correo es obligatorio"]
                    }"""))
        ),
        @ApiResponse(
            responseCode = "422",
            description  = "La solicitud supera validaciones de negocio",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = @ExampleObject(value = """
                    {
                      "status": "RECHAZADA",
                      "idCita": "CITA-1001",
                      "error": "La fechaCita [2020-01-01] no puede ser una fecha pasada"
                    }"""))
        ),
        @ApiResponse(responseCode = "500", description = "Error interno al procesar la integración")
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> registrarCita(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                description = "Datos de la cita médica a registrar",
                required    = true,
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema    = @Schema(implementation = CitaRequest.class),
                    examples  = @ExampleObject(
                        name  = "Cita válida",
                        value = """
                            {
                              "idCita":       "CITA-1001",
                              "paciente":     "Ana Torres",
                              "correo":       "ana.torres@email.com",
                              "especialidad": "Cardiología",
                              "fechaCita":    "2026-06-15",
                              "sede":         "Centro Norte",
                              "valor":        45.50
                            }"""
                    )
                )
            )
            @Valid @RequestBody CitaRequest cita,
            BindingResult bindingResult) {

        log.info("[CITA RECIBIDA] idCita={} | paciente={}", cita.getIdCita(), cita.getPaciente());

        // ── Paso 1: Validación estructural (Bean Validation) ─────────────────
        if (bindingResult.hasErrors()) {
            List<String> errores = bindingResult.getFieldErrors()
                    .stream()
                    .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                    .collect(Collectors.toList());

            String idCita = (cita.getIdCita() != null) ? cita.getIdCita() : "N/A";
            String payload = toJson(cita);

            log.warn("[VALIDACIÓN FALLIDA] idCita={} | Errores={}", idCita, errores);
            validationService.registrarRechazo(idCita, "Validación estructural: " + errores, payload);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "RECHAZADA");
            response.put("idCita", idCita);
            response.put("errores", errores);
            return ResponseEntity.badRequest().body(response);
        }

        // ── Paso 2: Validación de negocio ────────────────────────────────────
        String errorNegocio = validationService.validar(cita);
        if (errorNegocio != null) {
            String payload = toJson(cita);
            log.warn("[NEGOCIO FALLIDO] idCita={} | Error={}", cita.getIdCita(), errorNegocio);
            validationService.registrarRechazo(cita.getIdCita(), errorNegocio, payload);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "RECHAZADA");
            response.put("idCita", cita.getIdCita());
            response.put("error", errorNegocio);
            return ResponseEntity.unprocessableEntity().body(response);
        }

        // ── Paso 3: Iniciar flujo de integración en Apache Camel ─────────────
        try {
            producerTemplate.sendBody("direct:procesarCita", cita);
            validationService.agregarCitaAceptada(cita);

            log.info("[INTEGRACIÓN INICIADA] idCita={}", cita.getIdCita());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ACEPTADA");
            response.put("idCita", cita.getIdCita());
            response.put("mensaje", "Cita registrada y flujo de integración iniciado correctamente");
            return ResponseEntity.accepted().body(response);

        } catch (Exception e) {
            log.error("[ERROR EN INTEGRACIÓN] idCita={} | Error={}", cita.getIdCita(), e.getMessage(), e);
            validationService.registrarRechazo(cita.getIdCita(), "Error en el flujo de integración: " + e.getMessage(), toJson(cita));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ERROR");
            response.put("idCita", cita.getIdCita());
            response.put("error", "Error interno al procesar la integración. Revise los logs.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  GET /api/citas — Consultar citas aceptadas (trazabilidad)
    // ══════════════════════════════════════════════════════════════════════════

    @Operation(
        summary     = "Consultar citas aceptadas",
        description = "Devuelve la lista de citas que han sido aceptadas y procesadas "
                    + "por el flujo de integración durante la sesión actual."
    )
    @ApiResponse(responseCode = "200", description = "Lista de citas aceptadas")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> listarCitas() {
        List<CitaRequest> citas = validationService.getCitasAceptadas();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", citas.size());
        response.put("citas", citas);
        return ResponseEntity.ok(response);
    }

    // ── Utilidad: serializar objeto a JSON (para log de errores) ─────────────
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}
