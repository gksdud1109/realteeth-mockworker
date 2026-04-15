package com.realteeth.mockworker.config

import com.realteeth.mockworker.client.MockWorkerProperties
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
}
