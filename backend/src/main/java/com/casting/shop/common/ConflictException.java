package com.casting.shop.common;

/** 并发冲突：另一人已先一步提交，调用方应刷新后查看已生效结果。 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
