package com.realteeth.mockworker.web

import com.realteeth.mockworker.domain.ImageJob
import com.realteeth.mockworker.domain.JobStatus
import com.realteeth.mockworker.service.IdempotencyConflictException
import com.realteeth.mockworker.service.ImageJobService
import com.realteeth.mockworker.service.JobNotFoundException
import java.time.Instant
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
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
        whenever(service.accept(any(), any())).thenThrow(IdempotencyConflictException("충돌"))

        mvc.post("/api/v1/jobs") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "dup-key")
            content = """{"imageUrl":"https://img.example.com"}"""
        }.andExpect { status { isConflict() } }
    }

    @Test
    fun `존재하지 않는 id 조회 시 404`() {
        whenever(service.get(any())).thenThrow(JobNotFoundException("no-such-id"))

        mvc.get("/api/v1/jobs/no-such-id")
            .andExpect { status { isNotFound() } }
    }
}
