package com.realteeth.mockworker

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class MockworkerApplication

fun main(args: Array<String>) {
    runApplication<MockworkerApplication>(*args)
}
