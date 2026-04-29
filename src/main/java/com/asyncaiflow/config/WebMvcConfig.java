package com.asyncaiflow.config;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final String uploadResourceLocation;

    public WebMvcConfig(@Value("${asyncaiflow.upload-dir:/tmp/asyncaiflow_uploads}") String uploadDir) {
        this.uploadResourceLocation = Path.of(uploadDir).toAbsolutePath().normalize().toUri().toString();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/models/**")
                .addResourceLocations("file:/tmp/asyncaiflow-assembly-output/");

        registry.addResourceHandler("/scan-models/**")
                .addResourceLocations("file:/tmp/asyncaiflow-scan-output/");

        registry.addResourceHandler("/files/upload/**")
                .addResourceLocations(uploadResourceLocation);
    }

        @Override
        public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOriginPatterns("*")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(false)
            .maxAge(3600);

        registry.addMapping("/models/**")
            .allowedOrigins("http://localhost:5173", "http://localhost:5174")
            .allowedMethods("GET", "HEAD", "OPTIONS")
            .allowCredentials(false);

        registry.addMapping("/scan-models/**")
            .allowedOrigins("http://localhost:5173", "http://localhost:5174")
            .allowedMethods("GET", "HEAD", "OPTIONS")
            .allowCredentials(false);

        registry.addMapping("/files/upload/**")
            .allowedOrigins("http://localhost:5173", "http://localhost:5174")
            .allowedMethods("GET", "HEAD", "OPTIONS")
            .allowCredentials(false);
        }
}
