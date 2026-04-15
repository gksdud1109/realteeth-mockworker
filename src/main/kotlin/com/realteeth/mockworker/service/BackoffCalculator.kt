package com.realteeth.mockworker.service

import java.time.Duration

/**
 * 상한값이 있는 지수 백오프 계산.
 *
 * 매핑: doneCount=0 → initial, doneCount=1 → initial×2, doneCount=2 → initial×4, ...
 * 호출 측에서 [com.realteeth.mockworker.domain.ImageJob.attemptCount] 를 그대로 넘기면 된다.
 */
object BackoffCalculator {

    fun next(initial: Duration, max: Duration, doneCount: Int): Duration {
        val count = maxOf(doneCount, 0)
        // long 오버플로 방지를 위해 shift 를 20으로 제한
        val shift = minOf(count, 20)
        val millis = initial.toMillis() * (1L shl shift)
        return Duration.ofMillis(minOf(millis, max.toMillis()))
    }
}
