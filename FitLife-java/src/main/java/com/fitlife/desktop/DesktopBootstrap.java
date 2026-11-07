package com.fitlife.desktop;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 桌面版（独立安装包）启动引导。
 *
 * <p>在 Spring 容器启动之前完成四件事，保证「双击 exe 就能用」：
 * <ol>
 *   <li>准备好用户数据目录 {@code %USERPROFILE%\.fitlife}（data / config / logs）；</li>
 *   <li>把 {@code config} 目录挂成外部配置位置，用户改配置不用拆安装包；</li>
 *   <li>用文件锁保证单实例运行，重复双击时直接把已启动的窗口唤到前台；</li>
 *   <li>探测可用端口（默认 8080，被占用时自动顺延），并写进 port.txt 供下次复用。</li>
 * </ol>
 *
 * <p>该类不依赖任何 Spring 组件，纯 JDK 实现，避免容器初始化失败时连日志都看不到。
 */
public final class DesktopBootstrap {

    /** 数据根目录名（位于用户主目录下） */
    private static final String HOME_DIR_NAME = ".fitlife";
    /** 默认 HTTP 端口 */
    private static final String DEFAULT_PORT = "8080";
    /** 端口被占用时最多向后探测多少个端口 */
    private static final int PORT_SCAN_LIMIT = 30;

    private static Path home;
    private static Path portFile;
    private static FileChannel lockChannel;
    private static FileLock fileLock;
    private static int httpPort = 8080;
    private static boolean singleInstance = true;

    private DesktopBootstrap() {
    }

    /**
     * 必须在 {@code SpringApplication.run} 之前调用。
     */
    public static void prepare(String[] args) {
        home = resolveHome();
        try {
            Files.createDirectories(home.resolve("data"));
            Files.createDirectories(home.resolve("config"));
            Files.createDirectories(home.resolve("logs"));
        } catch (IOException e) {
            System.err.println("[FitLife] 无法创建数据目录 " + home + "：" + e.getMessage());
        }

        // 两个 key 都设置：fitlife.home 供代码读取，FITLIFE_HOME 供 application.yml 的
        // ${FITLIFE_HOME:${user.home}/.fitlife} 占位符解析（占位符按字面名匹配，不做松散绑定）。
        // FITLIFE_HOME 统一用正斜杠：它会被拼进 spring.config.import 的 file: 位置，
        // Windows 的反斜杠在 URI 语义下会被当成转义符导致解析失败。
        System.setProperty("fitlife.home", home.toString());
        System.setProperty("FITLIFE_HOME", home.toString().replace('\\', '/'));

        // 外部配置目录：用户可以在这里放 application.yml / ai-key.yml 覆盖内置配置
        // 用 URI 形式（file:///C:/...）：用户名带空格时（如 C:\Users\John Doe）也能正确转义
        String configUri = "optional:" + home.resolve("config").toUri();
        System.setProperty("spring.config.additional-location", configUri);

        // 日志落到用户目录，避免安装目录只读
        if (System.getProperty("logging.file.name") == null) {
            System.setProperty("logging.file.name",
                    home.resolve("logs").resolve("fitlife.log").toString());
        }

        // ---- 单实例文件锁 ----
        acquireSingleInstanceLock();

        writeConfigTemplateIfMissing();

        // ---- 端口 ----
        httpPort = resolvePort();
        System.setProperty("server.port", String.valueOf(httpPort));
        System.setProperty("server.address", System.getProperty("server.address", "127.0.0.1"));
        portFile = home.resolve("port.txt");
        try {
            Files.write(portFile, String.valueOf(httpPort).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 端口缓存写不了不影响启动
        }

        Runtime.getRuntime().addShutdownHook(new Thread(DesktopBootstrap::release));

        System.out.println("============================================================");
        System.out.println(" FitLife 桌面版启动中");
        System.out.println(" 数据目录: " + home);
        System.out.println(" 访问地址: http://127.0.0.1:" + httpPort);
        System.out.println("============================================================");
    }

    /** 供就绪监听器使用：拿到最终端口 */
    public static int getPort() {
        return httpPort;
    }

    /** 供就绪监听器使用：拿到数据根目录 */
    public static Path getHome() {
        return home;
    }

    /** 当前进程是否持有单实例锁 */
    public static boolean isSingleInstance() {
        return singleInstance;
    }

    /** 用系统默认浏览器打开指定地址，失败静默 */
    public static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // 落到下面的兜底
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (Exception ignored) {
            System.out.println("[FitLife] 请手动在浏览器打开：" + url);
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private static Path resolveHome() {
        // 优先级：-Dfitlife.home > 环境变量 FITLIFE_HOME > 用户主目录/.fitlife
        String prop = System.getProperty("fitlife.home");
        if (prop != null && !prop.isBlank()) {
            return Paths.get(prop).toAbsolutePath();
        }
        String env = System.getenv("FITLIFE_HOME");
        if (env != null && !env.isBlank()) {
            return Paths.get(env).toAbsolutePath();
        }
        return Paths.get(System.getProperty("user.home", ".")).resolve(HOME_DIR_NAME).toAbsolutePath();
    }

    private static void acquireSingleInstanceLock() {
        Path lockFile = home.resolve("fitlife.lock");
        try {
            lockChannel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            fileLock = lockChannel.tryLock();
        } catch (Exception e) {
            // 锁文件被占用或不可写：退化为「不校验单实例」，但不能阻断启动
            fileLock = null;
            System.err.println("[FitLife] 单实例锁不可用（" + e.getMessage() + "），继续启动");
            return;
        }

        if (fileLock == null) {
            // 已有实例在跑：把它的页面打开后退出
            singleInstance = false;
            int runningPort = readCachedPort();
            String url = "http://127.0.0.1:" + (runningPort > 0 ? runningPort : httpPort);
            System.out.println("[FitLife] 检测到已在运行的实例，正在打开 " + url);
            openBrowser(url);
            System.exit(0);
        }
    }

    private static int readCachedPort() {
        Path pf = home.resolve("port.txt");
        try {
            if (Files.exists(pf)) {
                String text = Files.readString(pf, StandardCharsets.UTF_8).trim();
                return Integer.parseInt(text);
            }
        } catch (Exception ignored) {
            // 忽略：按默认端口处理
        }
        return -1;
    }

    private static int resolvePort() {
        String desired = System.getProperty("server.port");
        if (desired == null || desired.isBlank()) {
            desired = System.getenv("SERVER_PORT");
        }
        int base = 8080;
        if (desired != null && !desired.isBlank()) {
            try {
                base = Integer.parseInt(desired.trim());
            } catch (NumberFormatException ignored) {
                base = 8080;
            }
        }
        if (isPortFree(base)) {
            return base;
        }
        for (int i = 1; i <= PORT_SCAN_LIMIT; i++) {
            int candidate = base + i;
            if (isPortFree(candidate)) {
                System.out.println("[FitLife] 端口 " + base + " 已被占用，改用 " + candidate);
                return candidate;
            }
        }
        System.out.println("[FitLife] 未找到空闲端口，仍尝试使用 " + base);
        return base;
    }

    private static boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 首次运行时生成 AI Key 配置模板，用户填完重启即可生效 */
    private static void writeConfigTemplateIfMissing() {
        Path tpl = home.resolve("config").resolve("ai-key.yml");
        if (Files.exists(tpl)) {
            return;
        }
        String content = String.join(System.lineSeparator(),
                "# ============================================================",
                "# FitLife 桌面版本地配置",
                "#   * 本文件位于用户目录，重装/升级安装包不会覆盖；",
                "#   * 填好后关闭 FitLife 再重新打开即生效；",
                "#   * 不填 Key 也能正常登录使用，只是 AI 对话会提示未配置。",
                "# ============================================================",
                "",
                "fitlife:",
                "  ai-key:",
                "    # DeepSeek：https://platform.deepseek.com/api_keys",
                "    deepseek: \"\"",
                "    # 豆包（火山方舟）：https://console.volcengine.com/ark",
                "    ark: \"\"",
                "    # 智谱 GLM：https://open.bigmodel.cn/usercenter/apikeys",
                "    zhipu: \"\"",
                "",
                "# 默认模型，可选：deepseek-chat / deepseek-reasoner /",
                "# doubao-seed-1-6-flash / doubao-seed-1-6 / glm-4-flash / glm-4.6",
                "#ai:",
                "#  default-model: deepseek-chat",
                "",
                "# 想换端口就取消下面注释（默认 8080，被占用会自动往后顺延）",
                "#server:",
                "#  port: 8090",
                "",
                "# 不想启动时自动弹浏览器，把下面一行并入上面的 fitlife 段即可：",
                "#   desktop:",
                "#     open-browser: false",
                "");

        try (OutputStream out = Files.newOutputStream(tpl, StandardOpenOption.CREATE_NEW)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 模板写不了不影响启动
        }
    }

    private static void release() {
        try {
            if (portFile != null && Files.exists(portFile)) {
                Files.deleteIfExists(portFile);
            }
        } catch (IOException ignored) {
            // 忽略
        }
        try {
            if (fileLock != null && fileLock.isValid()) {
                fileLock.release();
            }
            if (lockChannel != null && lockChannel.isOpen()) {
                lockChannel.close();
            }
        } catch (IOException ignored) {
            // 忽略
        }
    }
}
