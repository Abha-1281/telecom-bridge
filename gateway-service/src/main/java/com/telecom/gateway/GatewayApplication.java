package com.telecom.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * GatewayApplication — Spring Boot entry point.
 *
 * @SpringBootApplication is shorthand for:
 *   @Configuration      — this is a Spring config class
 *   @EnableAutoConfiguration — let Spring auto-configure WebFlux, validation, etc.
 *   @ComponentScan      — scan for @Component, @Service, @Controller in this package
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
