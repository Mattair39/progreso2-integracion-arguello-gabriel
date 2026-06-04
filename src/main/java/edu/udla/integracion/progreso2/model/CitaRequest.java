package edu.udla.integracion.progreso2.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Modelo de solicitud de cita médica.
 *
 * Representa el payload que el cliente envía al endpoint POST /api/citas.
 * Las anotaciones de validación aseguran que todos los campos obligatorios
 * estén presentes y sean válidos antes de iniciar el flujo de integración.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Solicitud de registro de cita médica en la red Salud360")
public class CitaRequest {

    @NotBlank(message = "El campo idCita es obligatorio")
    @Schema(description = "Identificador único de la cita", example = "CITA-1001", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("idCita")
    private String idCita;

    @NotBlank(message = "El campo paciente es obligatorio")
    @Schema(description = "Nombre completo del paciente", example = "Ana Torres", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("paciente")
    private String paciente;

    @NotBlank(message = "El campo correo es obligatorio")
    @Email(message = "El correo no tiene un formato válido")
    @Schema(description = "Correo electrónico del paciente", example = "ana.torres@email.com", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("correo")
    private String correo;

    @NotBlank(message = "El campo especialidad es obligatorio")
    @Schema(description = "Especialidad médica de la cita", example = "Cardiología", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("especialidad")
    private String especialidad;

    @NotBlank(message = "El campo fechaCita es obligatorio")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "La fechaCita debe tener el formato YYYY-MM-DD")
    @Schema(description = "Fecha de la cita (formato YYYY-MM-DD)", example = "2026-06-15", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("fechaCita")
    private String fechaCita;

    @NotBlank(message = "El campo sede es obligatorio")
    @Schema(description = "Sede del centro médico donde se realizará la cita", example = "Centro Norte", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("sede")
    private String sede;

    @NotNull(message = "El campo valor es obligatorio")
    @DecimalMin(value = "0.01", message = "El valor de la cita debe ser mayor a 0")
    @Schema(description = "Valor monetario de la cita en USD", example = "45.50", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("valor")
    private Double valor;
}
