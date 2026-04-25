package com.example.orderservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean("inventoryRestTemplate")
    public RestTemplate inventoryRestTemplate(
            @Value("${services.inventory.timeout-ms}") int timeoutMs) {
        return buildRestTemplate(timeoutMs);
    }

    @Bean("notificationRestTemplate")
    public RestTemplate notificationRestTemplate(
            @Value("${services.notification.timeout-ms}") int timeoutMs) {
        return buildRestTemplate(timeoutMs);
    }

    private RestTemplate buildRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }
}
