package com.realteeth.mockworker.config

import javax.sql.DataSource
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 스케줄러 설정.
 *
 * ShedLock을 통해 멀티 인스턴스 환경에서도 각 스케줄러 작업이
 * 동시에 단 하나의 인스턴스에서만 실행되도록 보장한다.
 *
 * 락 보유 시간은 각 @SchedulerLock의 lockAtMostFor 으로 제어되며,
 * 인스턴스가 크래시되어도 최대 그 시간 이후에는 락이 자동 해제된다.
 *
 * scheduling.enabled=false 환경 변수로 스케줄러 자체를 비활성화할 수 있다.
 * (예: 앱 서버와 스케줄러를 역할 분리할 때 사용)
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30S")
@ConditionalOnProperty(name = ["scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class SchedulingConfig {

    @Bean
    fun lockProvider(dataSource: DataSource): LockProvider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(JdbcTemplate(dataSource))
                .usingDbTime()
                .build(),
        )
}
