package co.lumen.credit.credit_orchestrator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuración de CORS (Cross-Origin Resource Sharing).
 * Permite que el frontend Angular (http://localhost:4200) consuma
 * el API del orquestador. Sin esta configuración, el navegador bloquea
 * las peticiones con el error clásico:
 *   "Access to XMLHttpRequest at '...' from origin '...' has been blocked
 *    by CORS policy: No 'Access-Control-Allow-Origin' header is present"
 * Los orígenes permitidos se leen de application.yml vía app.cors.allowed-origins,
 * de modo que en producción se puedan inyectar por variable de entorno
 * sin recompilar.
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer{

    private final String[] allowedOrigins;

    public WebCorsConfig(@Value("${app.cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/api/**")                                   // solo el API, no el Actuator
                .allowedOrigins(allowedOrigins)                          // orígenes permitidos
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")                                     // todos los headers entrantes
                .exposedHeaders("Location", "X-Request-Id")              // headers visibles al JS del frontend
                .allowCredentials(true)                                  // permite cookies/auth
                .maxAge(3600);                                           // cache del preflight: 1 hora
    }
}
