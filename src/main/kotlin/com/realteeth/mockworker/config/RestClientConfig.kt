package com.realteeth.mockworker.config

import com.realteeth.mockworker.client.MockWorkerException
import com.realteeth.mockworker.client.MockWorkerProperties
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

@Configuration
class RestClientConfig(private val props: MockWorkerProperties) {

    @Bean
    fun restClient(): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(props.connectTimeout)
            setReadTimeout(props.readTimeout)
        }
        return RestClient.builder()
            .baseUrl(props.baseUrl)
            .requestFactory(factory)
            .build()
    }

    // 4xx 등 영구 오류는 Mock Worker 장애 신호가 아니므로 실패율 집계에서 제외
    @Bean
    fun mockWorkerCbCustomizer(): CircuitBreakerConfigCustomizer =
        CircuitBreakerConfigCustomizer.of("mock-worker") { builder ->
            builder.ignoreException { t ->
                t is MockWorkerException && !t.isTransient
            }
        }
}
