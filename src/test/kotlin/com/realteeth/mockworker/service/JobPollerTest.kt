package com.realteeth.mockworker.service

import com.realteeth.mockworker.client.MockWorkerClient
import com.realteeth.mockworker.client.MockWorkerException
import com.realteeth.mockworker.client.WorkerJobSnapshot
import com.realteeth.mockworker.client.WorkerJobStatus
import com.realteeth.mockworker.domain.ImageJob
import com.realteeth.mockworker.domain.ImageJobRepository
import com.realteeth.mockworker.domain.JobStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class JobPollerTest {

    @Autowired lateinit var poller: JobPoller
    @Autowired lateinit var repository: ImageJobRepository
    @MockitoBean lateinit var client: MockWorkerClient
    @MockitoBean lateinit var clock: Clock

    private var currentTime = Instant.now()

    @BeforeEach
    fun setUp() {
        whenever(clock.instant()).thenAnswer { currentTime }
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
    }

    private fun saveInProgress(): ImageJob {
        val job = ImageJob.accept("key", "https://img.example.com", "fp", currentTime)
        job.markSubmitted("worker-1", currentTime)
        return repository.saveAndFlush(job)
    }

    @Test
    fun `COMPLETED 응답 시 COMPLETED 전이`() {
        val job = saveInProgress()
        whenever(client.fetch(any())).thenReturn(WorkerJobSnapshot("worker-1", WorkerJobStatus.COMPLETED, "결과"))

        poller.runOnce()

        val updated = repository.findById(job.id).get()
        assertThat(updated.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(updated.result).isEqualTo("결과")
    }

    @Test
    fun `FAILED 응답 시 FAILED 전이`() {
        val job = saveInProgress()
        whenever(client.fetch(any())).thenReturn(WorkerJobSnapshot("worker-1", WorkerJobStatus.FAILED, null))

        poller.runOnce()

        assertThat(repository.findById(job.id).get().status).isEqualTo(JobStatus.FAILED)
    }

    @Test
    fun `PROCESSING 응답 시 IN_PROGRESS 유지 및 failureReason null`() {
        val job = saveInProgress()
        job.recordTransientFailure(currentTime, "이전 오류", currentTime)
        repository.saveAndFlush(job)

        whenever(client.fetch(any())).thenReturn(WorkerJobSnapshot("worker-1", WorkerJobStatus.PROCESSING, null))

        poller.runOnce()

        val updated = repository.findById(job.id).get()
        assertThat(updated.status).isEqualTo(JobStatus.IN_PROGRESS)
        assertThat(updated.failureReason).isNull()
    }

    @Test
    fun `일시적 오류 시 IN_PROGRESS 유지`() {
        val job = saveInProgress()
        whenever(client.fetch(any())).thenThrow(MockWorkerException("5xx", true))

        poller.runOnce()

        assertThat(repository.findById(job.id).get().status).isEqualTo(JobStatus.IN_PROGRESS)
    }

    @Test
    fun `영구 오류 시 즉시 FAILED 전이`() {
        val job = saveInProgress()
        whenever(client.fetch(any())).thenThrow(MockWorkerException("4xx", false))

        poller.runOnce()

        assertThat(repository.findById(job.id).get().status).isEqualTo(JobStatus.FAILED)
    }

    @Test
    fun `poll 데드라인 초과 시 FAILED 전이`() {
        val job = saveInProgress()
        // deadline=10m 이므로 11분 후로 시계를 이동
        currentTime = currentTime.plus(Duration.ofMinutes(11))

        poller.runOnce()

        assertThat(repository.findById(job.id).get().status).isEqualTo(JobStatus.FAILED)
    }
}
