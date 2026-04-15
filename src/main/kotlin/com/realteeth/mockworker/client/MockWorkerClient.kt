package com.realteeth.mockworker.client

interface MockWorkerClient {
    fun submit(imageUrl: String): WorkerJobSnapshot
    fun fetch(workerJobId: String): WorkerJobSnapshot
}
