package com.realteeth.mockworker.client

data class WorkerJobSnapshot(
    val jobId: String,
    val status: WorkerJobStatus,
    val result: String?,
)
