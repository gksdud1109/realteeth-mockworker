package com.realteeth.mockworker.domain

import java.time.Instant
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface ImageJobRepository : JpaRepository<ImageJob, String> {

    fun findByClientRequestKey(clientRequestKey: String): Optional<ImageJob>

    @Query(
        """
        select j from ImageJob j
         where j.status = :status
           and j.nextAttemptAt <= :now
         order by j.nextAttemptAt asc
        """,
    )
    fun findDueByStatus(
        @Param("status") status: JobStatus,
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<ImageJob>
}
