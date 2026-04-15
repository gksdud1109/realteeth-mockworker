package com.realteeth.mockworker.domain

enum class JobStatus {
    PENDING, IN_PROGRESS, COMPLETED, FAILED;

    private val allowed: Set<JobStatus>
        get() = when (this) {
            PENDING -> setOf(IN_PROGRESS, FAILED)
            IN_PROGRESS -> setOf(COMPLETED, FAILED)
            COMPLETED, FAILED -> emptySet()
        }

    fun canTransitionTo(next: JobStatus) = next in allowed

    val isTerminal get() = this == COMPLETED || this == FAILED
}
