package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
@RestController
class ClientController {
    private final RestTemplate restTemplate = new RestTemplate();
    @GetMapping("/invoke-backend")
    public String callBackend() {
        // Calls the backend Node.js service via its Kubernetes DNS name
        String backendUrl = "http://nodejs-service.secure-mesh.svc.cluster.local:3000/data";
        try {
            String response = restTemplate.getForObject(backendUrl, String.class);
            return "Spring Boot received: " + response;
        } catch (Exception e) {
            return "Error calling backend: " + e.getMessage();
        }
    }
}