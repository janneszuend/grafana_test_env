package com.example.accountservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean("balanceRestTemplate")
    public RestTemplate balanceRestTemplate(
            @Value("${services.balance.timeout-ms}") int timeoutMs) {
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
