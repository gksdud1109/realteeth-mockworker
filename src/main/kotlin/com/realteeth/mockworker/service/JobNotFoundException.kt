package com.realteeth.mockworker.service

class JobNotFoundException(id: String) : RuntimeException("작업을 찾을 수 없습니다: $id")
