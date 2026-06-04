package edu.udla.integracion.progreso2.service;

import edu.udla.integracion.progreso2.model.CitaRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Servicio de validación de citas médicas.
 *
 * Responsabilidades:
 *   1. Validar campos de negocio adicionales (más allá de Bean Validation).
 *   2. Registrar errores en el archivo de citas rechazadas (RF5).
 *   3. Mantener la lista en memoria de citas aceptadas (para consulta).
 */
@Slf4j
@Service
public class CitaValidationService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${salud360.data.errors}")
    private String errorsDir;

    @Value("${salud360.data.errores.filename}")
    private String erroresFilename;

    // ── Caché en memoria de citas aceptadas (para endpoint GET) ──────────────
    private final List<CitaRequest> citasAceptadas = new ArrayList<>();

    /**
     * Valida la solicitud de cita a nivel de negocio.
     *
     * Las validaciones estructurales ya fueron realizadas por Bean Validation
     * (@NotBlank, @Email, etc.) en el controller. Este método agrega
     * validaciones de lógica de negocio más específicas.
     *
     * @param cita la solicitud de cita a validar
     * @return null si es válida, o el mensaje de error si no lo es
     */
    public String validar(CitaRequest cita) {

        // Validación adicional: fecha no puede ser en el pasado
        try {
            java.time.LocalDate fecha = java.time.LocalDate.parse(cita.getFechaCita());
            if (fecha.isBefore(java.time.LocalDate.now())) {
                return "La fechaCita [" + cita.getFechaCita() + "] no puede ser una fecha pasada";
            }
        } catch (Exception e) {
            return "La fechaCita [" + cita.getFechaCita() + "] no es una fecha válida";
        }

        // Validación adicional: valor con máximo razonable
        if (cita.getValor() > 99999.99) {
            return "El valor [" + cita.getValor() + "] supera el máximo permitido de 99999.99";
        }

        return null; // null = válida
    }

    /**
     * Registra una cita rechazada en el archivo de errores (RF5).
     *
     * Formato de cada línea:
     *   [YYYY-MM-DD HH:mm:ss] | idCita: X | Motivo: Y | Payload: {...}
     *
     * @param idCita  identificador de la cita (puede ser "N/A" si no existe)
     * @param motivo  descripción del error
     * @param payload JSON del request original
     */
    public void registrarRechazo(String idCita, String motivo, String payload) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String linea = String.format(
                "[%s] | idCita: %s | Motivo: %s | Payload: %s%n",
                timestamp, idCita, motivo, payload
        );

        try {
            asegurarDirectorio(errorsDir);
            Path archivo = Paths.get(errorsDir, erroresFilename);
            try (FileWriter fw = new FileWriter(archivo.toFile(), true)) {
                fw.write(linea);
            }
            log.warn("[RECHAZO REGISTRADO] idCita={} | Motivo={}", idCita, motivo);
        } catch (IOException e) {
            log.error("[ERROR] No se pudo escribir en el archivo de errores: {}", e.getMessage());
        }
    }

    /**
     * Agrega una cita a la lista en memoria de citas aceptadas.
     * Usado para el endpoint GET /api/citas.
     */
    public void agregarCitaAceptada(CitaRequest cita) {
        citasAceptadas.add(cita);
    }

    /**
     * Devuelve la lista de citas aceptadas desde que arrancó la aplicación.
     */
    public List<CitaRequest> getCitasAceptadas() {
        return new ArrayList<>(citasAceptadas);
    }

    /**
     * Crea el directorio indicado si no existe.
     */
    private void asegurarDirectorio(String dirPath) throws IOException {
        Path dir = Paths.get(dirPath);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            log.info("[INIT] Directorio creado: {}", dir.toAbsolutePath());
        }
    }
}
