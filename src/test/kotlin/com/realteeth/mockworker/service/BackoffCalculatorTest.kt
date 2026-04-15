package com.realteeth.mockworker.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class BackoffCalculatorTest {

    private val initial = Duration.ofSeconds(1)
    private val max = Duration.ofSeconds(30)

    @Test
    fun `doneCount 0 이면 initial 반환`() {
        assertThat(BackoffCalculator.next(initial, max, 0)).isEqualTo(initial)
    }

    @Test
    fun `시도마다 간격이 두 배씩 증가`() {
        assertThat(BackoffCalculator.next(initial, max, 1)).isEqualTo(Duration.ofSeconds(2))
        assertThat(BackoffCalculator.next(initial, max, 2)).isEqualTo(Duration.ofSeconds(4))
        assertThat(BackoffCalculator.next(initial, max, 3)).isEqualTo(Duration.ofSeconds(8))
    }

    @Test
    fun `최대값을 초과하지 않음`() {
        assertThat(BackoffCalculator.next(initial, max, 10)).isEqualTo(max)
    }

    @Test
    fun `음수 doneCount 는 0 으로 처리`() {
        assertThat(BackoffCalculator.next(initial, max, -1)).isEqualTo(initial)
    }
}
