package com.realteeth.mockworker.web

import com.realteeth.mockworker.domain.ImageJob
import com.realteeth.mockworker.domain.JobStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "이미지 처리 작업 응답")
data class JobResponse(
    @Schema(description = "작업 ID") val id: String,
    @Schema(description = "작업 상태") val status: JobStatus,
    @Schema(description = "요청한 이미지 URL") val imageUrl: String,
    @Schema(description = "처리 결과 (COMPLETED 시 포함)") val result: String?,
    @Schema(description = "실패 사유 (FAILED 시 포함)") val failureReason: String?,
    @Schema(description = "시도 횟수") val attemptCount: Int,
    @Schema(description = "작업 생성 시각") val createdAt: Instant,
    @Schema(description = "마지막 상태 변경 시각") val updatedAt: Instant,
) {
    companion object {
        fun from(job: ImageJob) = JobResponse(
            id = job.id,
            status = job.status,
            imageUrl = job.imageUrl,
            result = job.result,
            failureReason = job.failureReason,
            attemptCount = job.attemptCount,
            createdAt = job.createdAt,
            updatedAt = job.updatedAt,
        )
    }
}
