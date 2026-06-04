package edu.udla.integracion.progreso2;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de entrada de la aplicación Salud360 – Integración de Citas.
 *
 * Combina:
 *   - Spring Boot (API REST)
 *   - Apache Camel (rutas de integración)
 *   - RabbitMQ (mensajería asíncrona)
 *   - Generación de archivos CSV (sistema legado)
 */
@SpringBootApplication
@OpenAPIDefinition(
    info = @Info(
        title       = "Salud360 – API de Integración de Citas",
        version     = "1.0.0",
        description = "API REST para el registro de citas médicas en la red Salud360. "
                    + "Implementa integración con RabbitMQ (Point-to-Point y Publish/Subscribe) "
                    + "y generación de archivos CSV para el sistema legado de auditoría.",
        contact     = @Contact(name = "Equipo de Integración Salud360", email = "integracion@salud360.ec")
    ),
    servers = {
        @Server(url = "http://localhost:8080", description = "Servidor local de desarrollo")
    }
)
public class Progreso2Application {

    public static void main(String[] args) {
        SpringApplication.run(Progreso2Application.class, args);
    }
}
