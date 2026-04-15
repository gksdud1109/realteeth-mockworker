package com.realteeth.mockworker.service

class IdempotencyConflictException(message: String) : RuntimeException(message)
