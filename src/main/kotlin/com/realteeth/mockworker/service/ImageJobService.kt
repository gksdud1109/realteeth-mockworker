package com.realteeth.mockworker.service

import com.realteeth.mockworker.domain.ImageJob
import com.realteeth.mockworker.domain.ImageJobRepository
import com.realteeth.mockworker.domain.exception.BusinessException
import com.realteeth.mockworker.domain.exception.JobErrorCode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 작업 등록/조회/목록의 트랜잭션 진입점.
 *
 * 이 서비스가 보장하는 규칙:
 *   - accept 는 멱등성 보장: 동일 [idempotencyKey] + 동일 페이로드 → 기존 작업 반환
 *   - 동일 키로 다른 페이로드가 오면 409 반환 (조용한 병합 없음)
 *   - 외부 Mock Worker 호출은 이 서비스에서 발생하지 않음: accept 는 PENDING 행만 삽입하고,
 *     실제 외부 호출은 백그라운드 [JobSubmitter] 가 담당
 */
@Service
class ImageJobService(
    private val repository: ImageJobRepository,
    private val clock: Clock,
) {
    @Transactional
    fun accept(idempotencyKey: String, imageUrl: String): ImageJob {
        val fingerprint = fingerprint(imageUrl)
        val now = Instant.now(clock)

        // 기존 작업 조회 (빠른 경로)
        val existing = repository.findByClientRequestKey(idempotencyKey)
        if (existing.isPresent) {
            return verifyFingerprintOrThrow(existing.get(), fingerprint)
        }

        val job = ImageJob.accept(idempotencyKey, imageUrl, fingerprint, now)
        return try {
            repository.saveAndFlush(job)
        } catch (e: DataIntegrityViolationException) {
            // 동일 멱등성 키로 동시 삽입 발생 시 — 승자 행을 읽어서 반환
            repository.findByClientRequestKey(idempotencyKey)
                .map { verifyFingerprintOrThrow(it, fingerprint) }
                .orElseThrow { e }
        }
    }

    @Transactional(readOnly = true)
    fun get(jobId: String): ImageJob =
        repository.findById(jobId).orElseThrow {
            BusinessException(JobErrorCode.JOB_NOT_FOUND, "작업을 찾을 수 없습니다: $jobId")
        }

    @Transactional(readOnly = true)
    fun list(pageable: Pageable): Page<ImageJob> = repository.findAll(pageable)

    private fun verifyFingerprintOrThrow(existing: ImageJob, fingerprint: String): ImageJob {
        if (existing.requestFingerprint != fingerprint) {
            throw BusinessException(
                JobErrorCode.IDEMPOTENCY_CONFLICT,
                "동일 멱등성 키로 다른 페이로드가 전달됨: ${existing.clientRequestKey}",
            )
        }
        return existing
    }

    companion object {
        fun fingerprint(imageUrl: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("v1|$imageUrl".toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
