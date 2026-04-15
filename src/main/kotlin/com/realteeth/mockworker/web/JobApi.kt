package com.realteeth.mockworker.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

@Tag(name = "이미지 처리 작업", description = "이미지 처리 작업 접수 및 조회 API")
@RequestMapping("/api/v1/jobs")
interface JobApi {

    @PostMapping
    @Operation(
        summary = "작업 접수",
        description = "이미지 처리 작업을 접수합니다. `Idempotency-Key` 헤더로 중복 접수를 방지할 수 있습니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "작업 접수 성공"),
        ApiResponse(responseCode = "400", description = "요청 유효성 오류"),
        ApiResponse(responseCode = "409", description = "동일 멱등성 키로 다른 페이로드가 전달됨"),
    )
    fun create(
        @Parameter(description = "멱등성 키 (선택). 미전달 시 payload 해시로 자동 생성.")
        @RequestHeader(value = "Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody body: CreateJobRequest,
    ): ResponseEntity<JobResponse>

    @GetMapping("/{id}")
    @Operation(summary = "단건 조회", description = "작업 ID 로 상태 및 결과를 조회합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "404", description = "작업 없음"),
    )
    fun get(@PathVariable id: String): JobResponse

    @GetMapping
    @Operation(summary = "목록 조회", description = "작업 목록을 페이지 단위로 조회합니다.")
    fun list(
        @Min(0, message = "page 는 0 이상이어야 합니다")
        @RequestParam(defaultValue = "0") page: Int,
        @Positive(message = "size 는 1 이상이어야 합니다")
        @RequestParam(defaultValue = "20") size: Int,
    ): JobController.JobListResponse
}
