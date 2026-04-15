package com.realteeth.mockworker.client

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "mock-worker")
data class MockWorkerProperties(
    val baseUrl: String,
    val candidateName: String,
    val email: String,
    val apiKey: String?,
    val connectTimeout: Duration,
    val readTimeout: Duration,
    val submit: Submit,
    val poll: Poll,
) {
    data class Submit(val maxAttempts: Int, val initialBackoff: Duration, val maxBackoff: Duration)
    data class Poll(val initialInterval: Duration, val maxInterval: Duration, val deadline: Duration?)
}
