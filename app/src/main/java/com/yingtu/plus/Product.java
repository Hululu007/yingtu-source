package com.yingtu.plus;

import com.yingtu.plus.utils.Util;

/**
 * 设备形态：只给 Device.get() 用的 getDeviceType()。
 * leanback（电视）返回 0，手机返回 1，与 Device.isLeanback/isMobile 对齐。
 */
public class Product {

    public static int getDeviceType() {
        return Util.isLeanback() ? 0 : 1;
    }
}
