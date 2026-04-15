package com.realteeth.mockworker.domain

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Version
import java.time.Instant

/**
 * 모든 엔티티가 공유하는 공통 필드.
 *
 * 생성/수정 시각은 Spring Data Auditing 대신 도메인 로직에서 직접 관리한다.
 * Clock 주입을 통해 테스트에서 시각을 제어할 수 있도록 유지하기 위함이다.
 */
@MappedSuperclass
abstract class BaseEntity {

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.EPOCH; internal set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH; internal set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0; internal set
}
