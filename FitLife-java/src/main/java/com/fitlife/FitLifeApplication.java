package com.fitlife;

import com.fitlife.desktop.DesktopBootstrap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FitLifeApplication {

    public static void main(String[] args) {
        // 只有桌面发行版（jpackage 启动参数里带 -Dfitlife.desktop=1）才做本地目录/单实例/端口探测，
        // 服务器部署走原来的 Spring Boot 启动流程，行为完全不变。
        boolean desktopMode = "1".equals(String.valueOf(
                System.getProperty("fitlife.desktop", System.getenv().getOrDefault("FITLIFE_DESKTOP", "0"))));
        if (desktopMode) {
            DesktopBootstrap.prepare(args);
        }
        SpringApplication.run(FitLifeApplication.class, args);
    }
}
