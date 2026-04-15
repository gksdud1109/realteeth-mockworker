package com.realteeth.mockworker.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Mock Worker 연동 서비스 API")
                .description(
                    "이미지 처리 요청을 외부 Mock Worker 에 위임하고 상태와 결과를 추적합니다.\n\n" +
                        "- `POST /api/v1/jobs` 로 작업을 접수하면 PENDING 상태로 등록됩니다.\n" +
                        "- 백그라운드 스케줄러가 Mock Worker 에 제출하고 결과를 폴링합니다.\n" +
                        "- `Idempotency-Key` 헤더로 중복 접수를 방지할 수 있습니다.",
                )
                .version("v1"),
        )
}
