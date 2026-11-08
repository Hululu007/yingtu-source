package com.yingtu.plus.exception;

/** 取流失败异常：TVBus / Thunder 提取不到播放地址时抛出，调用方只做 instanceof 判断与 getMessage()。 */
public class ExtractException extends Exception {

    public ExtractException(String message) {
        super(message);
    }
}
