package com.yingtu.plus.web;

/**
 * WebHome 扩展重载入口。
 *
 * main 里 WebHome 页由 WebHomeActivity + HomeWebBridge 直接驱动，没有常驻的
 * WebHomeController 实例；Backup.refreshWebHomeExtensions() 调用的
 * WebHomeExtensionRegistry.get().clear() 已丢弃旧扩展数据，页面下次打开即
 * 重新加载。本方法保留调用点兼容，不做多余动作。
 */
public class HomeWebController {

    private HomeWebController() {}

    public static void requestExtensionReload() {
        // 无常驻控制器可通知；扩展在下次打开 WebHome 页时重新加载。
    }
}
