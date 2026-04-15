package com.realteeth.mockworker.service

import com.realteeth.mockworker.domain.ImageJobRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class ImageJobServiceTest {

    @Autowired lateinit var service: ImageJobService
    @Autowired lateinit var repository: ImageJobRepository

    @Test
    fun `동일 키 + 동일 payload 로 두 번 호출하면 같은 작업 반환`() {
        val key = "idem-key-1"
        val url = "https://img.example.com/a.png"
        val job1 = service.accept(key, url)
        val job2 = service.accept(key, url)
        assertThat(job1.id).isEqualTo(job2.id)
    }

    @Test
    fun `동일 키 + 다른 payload 는 409`() {
        val key = "idem-key-2"
        service.accept(key, "https://img.example.com/a.png")
        assertThatThrownBy { service.accept(key, "https://img.example.com/b.png") }
            .isInstanceOf(IdempotencyConflictException::class.java)
    }

    @Test
    fun `새 작업은 PENDING 상태로 생성됨`() {
        val job = service.accept("idem-key-3", "https://img.example.com/c.png")
        assertThat(job.id).isNotBlank()
        assertThat(job.status.name).isEqualTo("PENDING")
    }

    @Test
    fun `존재하지 않는 id 조회 시 JobNotFoundException`() {
        assertThatThrownBy { service.get("nonexistent-id") }
            .isInstanceOf(JobNotFoundException::class.java)
    }
}
