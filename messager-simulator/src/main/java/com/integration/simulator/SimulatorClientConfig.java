package com.integration.simulator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class SimulatorClientConfig {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public RestClient brokerRestClient() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(TIMEOUT);

        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
