package com.fitlife;

import com.fitlife.desktop.DesktopBootstrap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FitLifeApplication {

    public static void main(String[] args) {
        // 桌面版引导：数据目录 / 外置配置 / 单实例锁 / 端口探测，必须在容器启动前完成
        DesktopBootstrap.prepare(args);
        SpringApplication.run(FitLifeApplication.class, args);
    }
}
