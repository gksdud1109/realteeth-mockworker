package com.realteeth.mockworker.web

import com.realteeth.mockworker.service.ImageJobService
import com.realteeth.mockworker.service.ImageJobService.Companion.fingerprint
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
class JobController(private val service: ImageJobService) : JobApi {

    override fun create(idempotencyKey: String?, body: CreateJobRequest): ResponseEntity<JobResponse> {
        // 대체 키: 콘텐츠 기반 자동 생성. 동일 페이로드는 중복 제거됨.
        // 운영 환경에서는 클라이언트가 명시적 키를 전달해야 함.
        val key = if (idempotencyKey.isNullOrBlank()) "auto-${fingerprint(body.imageUrl)}"
                  else idempotencyKey
        val job = service.accept(key, body.imageUrl)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobResponse.from(job))
    }

    override fun get(id: String): JobResponse = JobResponse.from(service.get(id))

    override fun list(page: Int, size: Int): JobListResponse {
        val p = service.list(PageRequest.of(page, minOf(size, 100)))
        return JobListResponse(
            items = p.content.map { JobResponse.from(it) },
            page = p.number,
            size = p.size,
            total = p.totalElements,
            totalPages = p.totalPages,
            hasNext = p.hasNext(),
            hasPrevious = p.hasPrevious(),
        )
    }

    data class JobListResponse(
        val items: List<JobResponse>,
        val page: Int,
        val size: Int,
        val total: Long,
        val totalPages: Int,
        val hasNext: Boolean,
        val hasPrevious: Boolean,
    )
}
