package com.realteeth.mockworker.web

import com.realteeth.mockworker.domain.ImageJob
import com.realteeth.mockworker.domain.JobStatus
import com.realteeth.mockworker.domain.exception.BusinessException
import com.realteeth.mockworker.domain.exception.JobErrorCode
import com.realteeth.mockworker.service.ImageJobService
import java.time.Instant
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(JobController::class)
class JobControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var service: ImageJobService

    private fun stubJob(status: JobStatus = JobStatus.PENDING): ImageJob =
        ImageJob.accept("key", "https://img.example.com", "fp", Instant.EPOCH).also {
            if (status == JobStatus.IN_PROGRESS) it.markSubmitted("w-1", Instant.EPOCH)
        }

    @Test
    fun `요청 body 없으면 400`() {
        mvc.post("/api/v1/jobs") {
            contentType = MediaType.APPLICATION_JSON
            content = ""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `imageUrl 이 빈 문자열이면 400`() {
        mvc.post("/api/v1/jobs") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"imageUrl":""}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `imageUrl 이 URL 형식이 아니면 400`() {
        mvc.post("/api/v1/jobs") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"imageUrl":"not-a-url"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `정상 접수 시 202 반환`() {
        whenever(service.accept(any(), any())).thenReturn(stubJob())

        mvc.post("/api/v1/jobs") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"imageUrl":"https://img.example.com"}"""
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.id") { isNotEmpty() }
            jsonPath("$.status") { value("PENDING") }
        }
    }

    @Test
    fun `멱등성 키 충돌 시 409 반환`() {
        whenever(service.accept(any(), any())).thenThrow(BusinessException(JobErrorCode.IDEMPOTENCY_CONFLICT))

        mvc.post("/api/v1/jobs") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "dup-key")
            content = """{"imageUrl":"https://img.example.com"}"""
        }.andExpect { status { isConflict() } }
    }

    @Test
    fun `존재하지 않는 id 조회 시 404`() {
        whenever(service.get(any())).thenThrow(BusinessException(JobErrorCode.JOB_NOT_FOUND, "no-such-id"))

        mvc.get("/api/v1/jobs/no-such-id")
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `목록 조회 응답에 페이징 메타 필드 포함`() {
        val jobs = listOf(stubJob())
        val page = PageImpl(jobs, PageRequest.of(0, 20), 1L)
        whenever(service.list(any())).thenReturn(page)

        mvc.get("/api/v1/jobs")
            .andExpect {
                status { isOk() }
                jsonPath("$.items.length()") { value(1) }
                jsonPath("$.total") { value(1) }
                jsonPath("$.totalPages") { value(1) }
                jsonPath("$.hasNext") { value(false) }
                jsonPath("$.hasPrevious") { value(false) }
            }
    }

    @Test
    fun `빈 목록 조회 시 items가 빈 배열`() {
        val page = PageImpl(emptyList<ImageJob>(), PageRequest.of(0, 20), 0L)
        whenever(service.list(any())).thenReturn(page)

        mvc.get("/api/v1/jobs")
            .andExpect {
                status { isOk() }
                jsonPath("$.items.length()") { value(0) }
                jsonPath("$.total") { value(0) }
                jsonPath("$.hasNext") { value(false) }
            }
    }

    @Test
    fun `목록 조회 시 page가 음수면 400`() {
        mvc.get("/api/v1/jobs?page=-1&size=20")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.error") { value("validation_failed") }
            }
    }

    @Test
    fun `목록 조회 시 size가 0이면 400`() {
        mvc.get("/api/v1/jobs?page=0&size=0")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.error") { value("validation_failed") }
            }
    }
}
