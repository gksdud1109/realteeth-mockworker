package com.realteeth.mockworker.domain.exception

class BusinessException(
    val errorCode: ErrorCode,
    detail: String? = null,
) : RuntimeException(detail ?: errorCode.message)
