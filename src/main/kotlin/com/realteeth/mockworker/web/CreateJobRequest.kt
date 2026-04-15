package com.realteeth.mockworker.web

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateJobRequest(
    @field:NotBlank(message = "imageUrl 은 필수입니다")
    @field:Size(max = 2048, message = "imageUrl 은 2048자를 초과할 수 없습니다")
    @Schema(description = "처리할 이미지 URL", example = "https://example.com/tooth.png")
    val imageUrl: String,
)
