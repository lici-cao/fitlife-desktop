package com.fitlife.service;

import com.fitlife.config.AiModelProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 模型 API Key 的本地保管箱。
 *
 * <p>桌面版不希望用户去改打好的 jar 包，所以把 Key 的存放位置从
 * {@code application.yml} 挪到用户目录 {@code ${FITLIFE_HOME:user.home/.fitlife}/config/ai-key.yml}，
 * 该文件已被 {@code application.yml} 里的 {@code spring.config.import} 引入，
 * 因此保存后既立即生效（同步刷新内存里的 {@link AiModelProperties}），重启也依然有效。
 *
 * <p>文件内容用 AES-256-GCM 加密保存（密钥由本机信息派生，换台电脑拷走文件也解不开）。
 * 若用户在文件里手写了明文 Key，也会被原样接受。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiKeyStore {

    /** 密文前缀，用于区分用户手写的明文 Key */
    private static final String PREFIX = "enc:v1:";

    /** 写入 ai-key.yml 的键名（与 application.yml 里 fitlife.ai-key.* 对齐） */
    private static final Map<String, String> FILE_KEYS = Map.of(
            "deepseek", "deepseek",
            "doubao", "ark",
            "glm", "zhipu"
    );

    /** 各家对应的环境变量名（环境变量优先级最高，不覆盖） */
    private static final Map<String, String> ENV_HINTS = Map.of(
            "deepseek", "DEEPSEEK_API_KEY",
            "doubao", "ARK_API_KEY",
            "glm", "ZHIPU_API_KEY"
    );

    private final AiModelProperties props;
    private final SecureRandom random = new SecureRandom();

    // ============================================================
    // 一、文件位置
    // ============================================================

    public Path configFile() {
        String home = System.getenv("FITLIFE_HOME");
        if (home == null || home.isBlank()) {
            home = System.getProperty("user.home") + File.separator + ".fitlife";
        }
        return Paths.get(home, "config", "ai-key.yml");
    }

    // ============================================================
    // 二、启动时加载（覆盖 application.yml 里注入的密文）
    // ============================================================

    @PostConstruct
    public void loadOnStartup() {
        Map<String, String> stored = readFile();
        if (stored.isEmpty()) {
            return;
        }
        for (AiModelProperties.Provider p : providers()) {
            String env = ENV_HINTS.get(p.getId());
            if (env != null && !Optional.ofNullable(System.getenv(env)).orElse("").isBlank()) {
                // 环境变量优先，不动
                continue;
            }
            String raw = stored.get(fileKeyFor(p.getId()));
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String plain = decrypt(raw);
            if (plain == null || plain.isBlank()) {
                // 解不开（换了电脑 / 文件被改坏）：视为未配置，避免拿密文去请求
                p.setApiKey("");
                log.warn("ai-key.yml 里 {} 的 Key 无法解密，已按未配置处理", p.getId());
                continue;
            }
            p.setApiKey(plain);
            log.info("已从 {} 载入 {} 的 API Key（{}）", configFile(), p.getId(), mask(plain));
        }
    }

    // ============================================================
    // 三、查询状态（脱敏）
    // ============================================================

    public List<Map<String, Object>> status() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AiModelProperties.Provider p : providers()) {
            String key = p.getApiKey();
            boolean configured = key != null && !key.isBlank();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("providerId", p.getId());
            item.put("providerName", p.getName());
            item.put("configured", configured);
            item.put("masked", configured ? mask(key) : "");
            item.put("docUrl", p.getDocUrl());
            item.put("envHint", ENV_HINTS.getOrDefault(p.getId(), p.getId() + " 的 API Key"));
            item.put("fromEnv", configured && isFromEnv(p));
            list.add(item);
        }
        return list;
    }

    // ============================================================
    // 四、保存 / 清除
    // ============================================================

    /** 保存某个厂商的 Key：写文件 + 立即刷新内存，对话页无需重启即可使用 */
    public void save(String providerId, String apiKey) throws IOException {
        AiModelProperties.Provider provider = require(providerId);
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }

        Map<String, String> plainMap = readPlainFile();
        plainMap.put(fileKeyFor(providerId), key);
        writeFile(plainMap);

        provider.setApiKey(key);
        log.info("已保存 {} 的 API Key（{}），文件：{}", providerId, mask(key), configFile());
    }

    /** 清除某个厂商的 Key */
    public void clear(String providerId) throws IOException {
        AiModelProperties.Provider provider = require(providerId);
        Map<String, String> plainMap = readPlainFile();
        plainMap.remove(fileKeyFor(providerId));
        writeFile(plainMap);

        provider.setApiKey("");
        log.info("已清除 {} 的 API Key", providerId);
    }

    // ============================================================
    // 五、内部实现
    // ============================================================

    private List<AiModelProperties.Provider> providers() {
        return props.getProviders() == null ? List.of() : props.getProviders();
    }

    private AiModelProperties.Provider require(String providerId) {
        for (AiModelProperties.Provider p : providers()) {
            if (p.getId() != null && p.getId().equalsIgnoreCase(providerId)) {
                return p;
            }
        }
        throw new IllegalArgumentException("未知的厂商：" + providerId);
    }

    private String fileKeyFor(String providerId) {
        String k = FILE_KEYS.get(providerId);
        return k == null ? providerId : k;
    }

    private boolean isFromEnv(AiModelProperties.Provider p) {
        String env = ENV_HINTS.get(p.getId());
        return env != null && !Optional.ofNullable(System.getenv(env)).orElse("").isBlank();
    }

    private String mask(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    /** 读文件并解密成明文 Map */
    private Map<String, String> readPlainFile() {
        Map<String, String> plain = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : readFile().entrySet()) {
            String v = decrypt(e.getValue());
            if (v != null && !v.isBlank()) {
                plain.put(e.getKey(), v);
            }
        }
        return plain;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readFile() {
        Map<String, String> result = new LinkedHashMap<>();
        Path file = configFile();
        if (!Files.exists(file)) {
            return result;
        }
        try (InputStream in = Files.newInputStream(file)) {
            Object root = new Yaml().load(in);
            Object aiKey = dig(root, "fitlife", "ai-key");
            if (!(aiKey instanceof Map)) {
                return result;
            }
            for (Map.Entry<String, Object> e : ((Map<String, Object>) aiKey).entrySet()) {
                if (e.getValue() != null) {
                    result.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        } catch (Exception e) {
            log.warn("读取 {} 失败：{}", file, e.getMessage());
        }
        return result;
    }

    private Object dig(Object root, String... path) {
        Object cur = root;
        for (String key : path) {
            if (!(cur instanceof Map)) {
                return null;
            }
            cur = ((Map<?, ?>) cur).get(key);
        }
        return cur;
    }

    /**
     * 写文件（逐项加密）。
     *
     * <p>只改写 {@code fitlife.ai-key} 这一段，文件里其余内容（默认模型、端口等用户自行取消注释的配置）
     * 原样保留；首次改写前会把原文件备份成 {@code ai-key.yml.bak}。
     */
    private void writeFile(Map<String, String> plainMap) throws IOException {
        Path file = configFile();
        Files.createDirectories(file.getParent());

        List<String> block = new ArrayList<>();
        block.add("  ai-key:");
        for (Map.Entry<String, String> e : plainMap.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) {
                continue;
            }
            block.add("    " + e.getKey() + ": \"" + encrypt(e.getValue()) + "\"");
        }

        List<String> lines = Files.exists(file)
                ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                : new ArrayList<>();

        if (!lines.isEmpty()) {
            Path backup = file.resolveSibling("ai-key.yml.bak");
            if (!Files.exists(backup)) {
                Files.copy(file, backup);
            }
        }

        // 定位 fitlife: 与其中的 ai-key: 段落范围
        int fitlifeIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).stripTrailing().equals("fitlife:")) {
                fitlifeIdx = i;
                break;
            }
        }
        int aiKeyIdx = -1;
        if (fitlifeIdx >= 0) {
            for (int i = fitlifeIdx + 1; i < lines.size(); i++) {
                String t = lines.get(i);
                if (t.isBlank() || t.stripLeading().startsWith("#")) {
                    continue;
                }
                if (!t.startsWith(" ")) {
                    break;
                }
                if (t.stripTrailing().equals("  ai-key:")) {
                    aiKeyIdx = i;
                    break;
                }
            }
        }

        if (aiKeyIdx >= 0) {
            int end = aiKeyIdx + 1;
            while (end < lines.size()) {
                String t = lines.get(end);
                if (t.isBlank()) {
                    end++;
                    continue;
                }
                if (!t.startsWith("    ")) {
                    break;
                }
                end++;
            }
            while (end > aiKeyIdx + 1 && lines.get(end - 1).isBlank()) {
                end--;
            }
            lines.subList(aiKeyIdx, end).clear();
            lines.addAll(aiKeyIdx, block);
        } else if (fitlifeIdx >= 0) {
            lines.addAll(fitlifeIdx + 1, block);
        } else {
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add("fitlife:");
            lines.addAll(block);
        }

        String joined = String.join("\n", lines);
        if (!joined.contains("由 AI 对话页自动生成")) {
            List<String> header = new ArrayList<>();
            header.add("# ============================================================");
            header.add("# FitLife 模型 API Key —— 由 AI 对话页自动生成，也可直接手写明文");
            header.add("# 内容经 AES-256-GCM 加密，仅本机可解密；保存后立即生效，无需重启");
            header.add("# 环境变量 DEEPSEEK_API_KEY / ARK_API_KEY / ZHIPU_API_KEY 优先级最高");
            header.add("# ============================================================");
            lines.addAll(0, header);
        }

        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private SecretKeySpec secretKey() throws Exception {
        String seed = "fitlife-ai-key|"
                + Optional.ofNullable(System.getenv("COMPUTERNAME")).orElse("")
                + "|" + System.getProperty("user.name", "");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    private String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // 加密环节出问题也不该让用户填不了 Key：退化为明文保存
            log.warn("API Key 加密失败，改为明文保存：{}", e.getMessage());
            return plain;
        }
    }

    private String decrypt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!raw.startsWith(PREFIX)) {
            return raw.trim();
        }
        try {
            byte[] all = Base64.getDecoder().decode(raw.substring(PREFIX.length()).trim());
            byte[] iv = new byte[12];
            System.arraycopy(all, 0, iv, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(all, 12, all.length - 12);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("API Key 解密失败：{}", e.getMessage());
            return null;
        }
    }
}
