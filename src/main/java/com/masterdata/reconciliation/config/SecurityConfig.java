package com.masterdata.reconciliation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Map;

@Configuration
public class SecurityConfig {
    @Bean
    UserDetailsService users(ReconciliationProperties properties) {
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        var reviewer = User.withUsername(properties.reviewerUsername())
                .password(encoder.encode(properties.reviewerPassword()))
                .roles("REVIEWER")
                .build();
        return new InMemoryUserDetailsManager(reviewer);
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/review-queue/**").hasRole("REVIEWER")
                        .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
                        .requestMatchers("/api/**").hasRole("REVIEWER")
                        .anyRequest().permitAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> {
                            response.setStatus(401);
                            response.setContentType("application/problem+json");
                            objectMapper.writeValue(response.getOutputStream(), Map.of(
                                    "type", "urn:problem:authentication-required", "title", "AUTHENTICATION_REQUIRED",
                                    "status", 401, "detail", "Reviewer authentication is required",
                                    "instance", request.getRequestURI()));
                        })
                        .accessDeniedHandler((request, response, failure) -> {
                            response.setStatus(403);
                            response.setContentType("application/problem+json");
                            objectMapper.writeValue(response.getOutputStream(), Map.of(
                                    "type", "urn:problem:access-denied", "title", "ACCESS_DENIED",
                                    "status", 403, "detail", "The authenticated user cannot perform this action",
                                    "instance", request.getRequestURI()));
                        }))
                .httpBasic(Customizer.withDefaults())
                .build();
    }
}
