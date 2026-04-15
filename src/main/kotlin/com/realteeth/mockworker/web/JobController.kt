package com.realteeth.mockworker.web

import com.realteeth.mockworker.service.ImageJobService
import com.realteeth.mockworker.service.ImageJobService.Companion.fingerprint
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/jobs")
@Tag(name = "이미지 처리 작업", description = "이미지 처리 작업 접수 및 조회 API")
class JobController(private val service: ImageJobService) {

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
    ): ResponseEntity<JobResponse> {
        // 대체 키: 콘텐츠 기반 자동 생성. 동일 페이로드는 중복 제거됨.
        // 운영 환경에서는 클라이언트가 명시적 키를 전달해야 함.
        val key = if (idempotencyKey.isNullOrBlank()) "auto-${fingerprint(body.imageUrl)}"
                  else idempotencyKey
        val job = service.accept(key, body.imageUrl)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobResponse.from(job))
    }

    @GetMapping("/{id}")
    @Operation(summary = "단건 조회", description = "작업 ID 로 상태 및 결과를 조회합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "404", description = "작업 없음"),
    )
    fun get(@PathVariable id: String): JobResponse = JobResponse.from(service.get(id))

    @GetMapping
    @Operation(summary = "목록 조회", description = "작업 목록을 페이지 단위로 조회합니다.")
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): JobListResponse {
        val p = service.list(PageRequest.of(page, minOf(size, 100)))
        return JobListResponse(
            items = p.content.map { JobResponse.from(it) },
            page = p.number,
            size = p.size,
            total = p.totalElements,
        )
    }

    data class JobListResponse(
        val items: List<JobResponse>,
        val page: Int,
        val size: Int,
        val total: Long,
    )
}
