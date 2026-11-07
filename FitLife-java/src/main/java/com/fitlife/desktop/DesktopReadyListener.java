package com.fitlife.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 应用就绪后自动把界面推到用户面前。
 *
 * <p>桌面版的目标是「双击即用」，因此默认在容器就绪后打开系统浏览器；
 * 如果用户不想要这个行为，可以在 {@code ~/.fitlife/config/application.yml} 中设置：
 * <pre>
 * fitlife:
 *   desktop:
 *     open-browser: false
 * </pre>
 */
@Component
public class DesktopReadyListener {

    private static final Logger log = LoggerFactory.getLogger(DesktopReadyListener.class);

    @Value("${fitlife.desktop.open-browser:true}")
    private boolean openBrowser;

    @Value("${server.port:8080}")
    private String port;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        int p = DesktopBootstrap.getPort() > 0 ? DesktopBootstrap.getPort()
                : parseInt(port, 8080);
        String url = "http://127.0.0.1:" + p;

        Path home = DesktopBootstrap.getHome();
        log.info("FitLife 已就绪：{}（数据目录：{}）", url, home == null ? "-" : home.toString());

        if (home != null) {
            Path db = home.resolve("data").resolve("fitlife.mv.db");
            if (!Files.exists(db)) {
                log.info("首次启动，数据库将在 {} 自动创建", db);
            }
        }

        if (!openBrowser) {
            log.info("已按配置跳过自动打开浏览器，请手动访问 {}", url);
            return;
        }

        // 稍等一下，确保 Tomcat 的监听端口完全可连接
        new Thread(() -> {
            try {
                Thread.sleep(1200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            DesktopBootstrap.openBrowser(url);
        }, "fitlife-open-browser").start();
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
