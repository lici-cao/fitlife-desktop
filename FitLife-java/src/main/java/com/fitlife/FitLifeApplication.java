package com.fitlife;

import com.fitlife.desktop.DesktopBootstrap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FitLifeApplication {

    public static void main(String[] args) {
        // 桌面模式：jpackage 启动时通过 -Dfitlife.desktop=1 打开（准备 FITLIFE_HOME、端口、日志路径等）
        boolean desktopMode = "1".equals(String.valueOf(
                System.getProperty("fitlife.desktop", System.getenv().getOrDefault("FITLIFE_DESKTOP", "0"))));
        if (desktopMode) {
            DesktopBootstrap.prepare(args);
        }
        SpringApplication.run(FitLifeApplication.class, args);
    }
}
