# Feat 集成服务（interview-server）实施计划

> **状态：已完成**（interview-server 已成为 Java 正式产品基座）。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `services/` 下新建一个独立的 Java Feat 服务 `services/interview-server/`，把现有 ASR WebSocket 网关（mica-voice-gateway）、LLM 回答服务（answer_service 核心）和内嵌 HTML 页面集成进同一个进程，作为 Flask 应用唯一的 AI 网关替代；WebSocket 协议与现网 100% 兼容，Python 侧零改动。

**Architecture:** 单进程 Feat 服务（JDK8，端口 18080 与原网关一致）。全部走 feat-core 一条链路：`Router` 注册 REST 路由 + WebSocket UPGRADE（`/mica/voice/ws/online-asr`，PCM16 二进制帧流式 ASR），未匹配路径落到 `HttpStaticResourceHandler`（classpath:static 内嵌页面）。mica-voice 通过 `MicaVoice.onlineAsrTyped(...)` 门面直连 `mica-voice-core`（不再用 Spring 自动装配）；feat-ai `ChatModel` 提供 OpenAI 兼容（阿里百炼 DashScope）流式回答。模型目录由 `MICA_VOICE_MODELS_DIR` 环境变量指向外部固定目录（本机默认 `E:/BaiduNetdiskDownload/projects/models`），**不打包进 resources/jar**。

**Tech Stack:** Java 8、Maven、feat-core 2.4.0、feat-ai 2.4.0、mica-voice-core 1.0.1、fastjson2 2.0.64、JUnit 5、Mockito 4.11.0（JDK8 兼容）、WebSocket(RFC 6455 文本+二进制帧)。

---

## 模型目录决策（先回答：绝对路径 vs resources/models）

**结论：用外部固定目录 + 环境变量注入，不用 `resources/models`。**

| 方案 | 判定 | 原因 |
|---|---|---|
| `resources/models`（打进 jar/classpath） | ❌ | mica-voice 的 `models-dir` 按**文件系统目录**加载（README：默认 `./models`，可用 `-Dmica.voice.models-dir` 指定绝对路径），不做 classpath 资源读取；且流式模型 100MB~1GB 级，进 jar/git 会让构建产物、Docker 镜像膨胀几十倍 |
| 固定绝对路径硬编码 | ⚠️ 不推荐 | 单机可用，换机器/部署即坏 |
| **外部目录 + 环境变量 `MICA_VOICE_MODELS_DIR`** | ✅ | 与现有 Spring 网关 `application.yml` 的 `${MICA_VOICE_MODELS_DIR:...}` 约定保持一致；本机由 `start.ps1` 注入 `E:/BaiduNetdiskDownload/projects/models`，换机器只改一处；`.gitignore` 排除，模型不进版本库 |

---

## 文件结构

| 文件 | 类型 | 职责 |
|---|---|---|
| `services/interview-server/pom.xml` | 新建 | 工程依赖（feat-core/feat-ai/mica-voice-core/fastjson2/测试） |
| `services/interview-server/src/main/java/com/aiinterview/server/InterviewServerApplication.java` | 新建 | main：装配 Router + 静态资源 + 生命周期（启动/关闭 ASR 引擎） |
| `services/interview-server/src/main/java/com/aiinterview/server/AppConfig.java` | 新建 | 环境变量读取（端口/模型目录/LLM）+ fail-fast 校验 |
| `services/interview-server/src/main/java/com/aiinterview/server/AsrEngine.java` | 新建 | mica-voice-core 门面持有者（替代 Spring 自动装配） |
| `services/interview-server/src/main/java/com/aiinterview/server/Pcm16Decoder.java` | 新建 | 移植：PCM16 LE → float 采样 |
| `services/interview-server/src/main/java/com/aiinterview/server/TranscriptRules.java` | 新建 | 移植：`shouldDelayEndpoint` / `shouldPublishTranscript` 纯函数 |
| `services/interview-server/src/main/java/com/aiinterview/server/AsrWebSocketUpgrade.java` | 新建 | 移植：WS 处理器（控制帧 + 二进制帧 + partial/final/error） |
| `services/interview-server/src/main/java/com/aiinterview/server/AnswerService.java` | 新建 | feat-ai ChatModel 流式回答封装 |
| `services/interview-server/src/main/resources/static/index.html` | 新建 | 内嵌 HTML 页面（copilot 占位页） |
| `services/interview-server/src/test/java/com/aiinterview/server/Pcm16DecoderTest.java` | 新建 | 解码单测 |
| `services/interview-server/src/test/java/com/aiinterview/server/TranscriptRulesTest.java` | 新建 | 端点/发布规则单测（移植原网关断言） |
| `services/interview-server/src/test/java/com/aiinterview/server/AsrWebSocketUpgradeTest.java` | 新建 | WS 帧处理单测（Mockito） |
| `services/interview-server/start.ps1` | 新建 | 构建 + 启动（注入环境变量） |
| `services/interview-server/.gitignore` | 新建 | 排除 target/、模型目录 |
| `services/interview-server/README.md` | 新建 | 运行说明、环境变量、模型准备 |

---

## Task 1: 工程骨架、配置与模型目录

**Files:**
- Create: `services/interview-server/pom.xml`
- Create: `services/interview-server/src/main/java/com/aiinterview/server/AppConfig.java`
- Create: `services/interview-server/src/main/java/com/aiinterview/server/AsrEngine.java`
- Create: `services/interview-server/src/main/java/com/aiinterview/server/InterviewServerApplication.java`
- Create: `services/interview-server/start.ps1`
- Create: `services/interview-server/.gitignore`
- Test: `mvn -q -DskipTests package` 编译通过

- [x] **Step 1: 编写 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.aiinterview</groupId>
    <artifactId>interview-server</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.source>1.8</maven.compiler.source>
        <maven.compiler.target>1.8</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>tech.smartboot.feat</groupId>
            <artifactId>feat-core</artifactId>
            <version>2.4.0</version>
        </dependency>
        <dependency>
            <groupId>tech.smartboot.feat</groupId>
            <artifactId>feat-ai</artifactId>
            <version>2.4.0</version>
        </dependency>
        <dependency>
            <groupId>net.dreamlu</groupId>
            <artifactId>mica-voice-core</artifactId>
            <version>1.0.1</version>
        </dependency>
        <dependency>
            <groupId>com.alibaba.fastjson2</groupId>
            <artifactId>fastjson2</artifactId>
            <version>2.0.64</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <version>4.11.0</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-dependency-plugin</artifactId>
                <version>3.6.1</version>
                <executions>
                    <execution>
                        <id>copy-dependencies</id>
                        <phase>package</phase>
                        <goals>
                            <goal>copy-dependencies</goal>
                        </goals>
                        <configuration>
                            <outputDirectory>${project.build.directory}/lib</outputDirectory>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [x] **Step 2: 编写 AppConfig（环境变量 + fail-fast 校验）**

```java
package com.aiinterview.server;

import java.io.File;
import java.util.Map;

public final class AppConfig {

    private final int port;
    private final String modelsDir;
    private final String onlineModelDirName;
    private final String llmApiKey;
    private final String llmBaseUrl;
    private final String llmModel;

    private AppConfig(int port, String modelsDir, String onlineModelDirName,
                      String llmApiKey, String llmBaseUrl, String llmModel) {
        this.port = port;
        this.modelsDir = modelsDir;
        this.onlineModelDirName = onlineModelDirName;
        this.llmApiKey = llmApiKey;
        this.llmBaseUrl = llmBaseUrl;
        this.llmModel = llmModel;
    }

    public static AppConfig load() {
        Map<String, String> env = System.getenv();
        int port = Integer.parseInt(firstNonBlank(env.get("SERVER_PORT"), "18080"));
        String modelsDir = firstNonBlank(env.get("MICA_VOICE_MODELS_DIR"), "E:/BaiduNetdiskDownload/projects/models");
        String onlineModelDirName = firstNonBlank(env.get("MICA_VOICE_ONLINE_MODEL"), "x-asr-zh-en-chunk-960ms");
        String llmApiKey = env.get("LLM_API_KEY");
        String llmBaseUrl = firstNonBlank(env.get("LLM_BASE_URL"), "https://dashscope.aliyuncs.com/compatible-mode/v1");
        String llmModel = firstNonBlank(env.get("LLM_MODEL"), "qwen-plus");
        return new AppConfig(port, modelsDir, onlineModelDirName, llmApiKey, llmBaseUrl, llmModel);
    }

    /** 启动前校验：在线 ASR 模型子目录必须存在，缺失直接拒绝启动并给出下载指引。 */
    public void validate() {
        File modelDir = new File(modelsDir, onlineModelDirName);
        if (!modelDir.isDirectory()) {
            throw new IllegalStateException(
                "在线 ASR 模型目录不存在: " + modelDir.getAbsolutePath()
                    + System.lineSeparator()
                    + "请下载模型后重试（见 README）："
                    + "powershell -File third_party/mica-voice/models/scripts/download-models.ps1 x-asr"
                    + System.lineSeparator()
                    + "并把 MICA_VOICE_MODELS_DIR 指向包含 " + onlineModelDirName + " 的目录");
        }
    }

    public int port() { return port; }
    public String modelsDir() { return modelsDir; }
    public String onlineModelDirName() { return onlineModelDirName; }
    public String llmApiKey() { return llmApiKey; }
    public String llmBaseUrl() { return llmBaseUrl; }
    public String llmModel() { return llmModel; }

    private static String firstNonBlank(String value, String fallback) {
        return (value == null || value.trim().isEmpty()) ? fallback : value.trim();
    }
}
```

- [x] **Step 3: 编写 AsrEngine（mica-voice-core 门面，替代 Spring 自动装配；`MicaVoice.onlineAsrTyped` 已由 mica-voice-core 1.0.1 源码证实）**

```java
package com.aiinterview.server;

import net.dreamlu.mica.voice.asr.OnlineAsrService;
import net.dreamlu.mica.voice.config.MicaVoiceConfig;
import net.dreamlu.mica.voice.config.ModelType;
import net.dreamlu.mica.voice.config.OnlineAsrConfig;
import net.dreamlu.mica.voice.core.MicaVoice;

/** 进程内唯一的 mica-voice 在线 ASR 引擎；不依赖 Spring。 */
public final class AsrEngine implements AutoCloseable {

    private static final AsrEngine INSTANCE = new AsrEngine();

    private OnlineAsrService onlineAsrService;

    private AsrEngine() {
    }

    public static AsrEngine getInstance() {
        return INSTANCE;
    }

    /** 首次访问时按 AppConfig 构建 OnlineAsrService。 */
    public synchronized OnlineAsrService onlineAsr(AppConfig config) {
        if (onlineAsrService == null) {
            MicaVoiceConfig props = MicaVoiceConfig.builder()
                .modelsDir(config.modelsDir())
                .threads(2)
                .build();
            OnlineAsrConfig online = OnlineAsrConfig.builder()
                .modelDirName(config.onlineModelDirName())
                .modelType(ModelType.X_ASR)
                .enableEndpoint(true)
                .endpointRule1MinTrailingSilence(1.2)
                .endpointRule2MinTrailingSilence(1.0)
                .endpointRule3MinUtteranceLength(0.5)
                .chunkSize(1600)
                .build();
            onlineAsrService = MicaVoice.onlineAsrTyped(props, online);
            System.out.println("OnlineAsrService created, modelDir=" + config.onlineModelDirName());
        }
        return onlineAsrService;
    }

    @Override
    public synchronized void close() {
        if (onlineAsrService != null) {
            onlineAsrService.close();
            onlineAsrService = null;
            System.out.println("OnlineAsrService closed");
        }
    }
}
```

- [x] **Step 4: 编写主程序骨架（先只起健康检查，Task 4 再挂 Router/静态页/WS）**

```java
package com.aiinterview.server;

import tech.smartboot.feat.Feat;
import tech.smartboot.feat.core.server.HttpServer;

public class InterviewServerApplication {

    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        config.validate();

        HttpServer server = Feat.httpServer()
            .httpHandler(request -> request.getResponse().write("{\"status\":\"ok\"}"))
            .listen(config.port());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            AsrEngine.getInstance().close();
            server.shutdown();
        }));
        System.out.println("interview-server listening on http://localhost:" + config.port());
    }
}
```

- [x] **Step 5: 编写 start.ps1（注入环境变量 + 构建 + 启动；延续旧 start-gateway.ps1 的 classpath 方式）**

```powershell
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

# 模型目录：本机固定绝对路径，可通过环境变量覆盖
$env:MICA_VOICE_MODELS_DIR = if ($env:MICA_VOICE_MODELS_DIR) { $env:MICA_VOICE_MODELS_DIR } else { "E:/BaiduNetdiskDownload/projects/models" }
$env:SERVER_PORT = if ($env:SERVER_PORT) { $env:SERVER_PORT } else { "18080" }
# LLM（可选，未配置时 /api/copilot/answer 不可用）
# $env:LLM_API_KEY = "..."
# $env:LLM_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
# $env:LLM_MODEL = "qwen-plus"

Push-Location $root
try {
    mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }
    $cp = @("$root\target\classes") + (Get-ChildItem "$root\target\lib\*.jar" | ForEach-Object { $_.FullName })
    & java -cp ($cp -join ";") com.aiinterview.server.InterviewServerApplication
} finally {
    Pop-Location
}
```

- [x] **Step 6: 编写 .gitignore**

```
target/
models/
output/
```

- [x] **Step 7: 编译验证**

Run: `mvn -q -DskipTests package`
Expected: BUILD SUCCESS，`target/classes` 与 `target/lib/` 生成。

Run: `powershell -File services/interview-server/start.ps1`（若模型未下载到 `E:/BaiduNetdiskDownload/projects/models`）
Expected: 打印模型目录不存在指引并退出（fail-fast 生效）。按指引下载模型（`third_party/mica-voice/models/scripts/download-models.ps1 x-asr`，模型落在 `MICA_VOICE_MODELS_DIR`）后，启动成功且 `http://localhost:18080/` 返回 `{"status":"ok"}`。

- [x] **Step 8: 提交**

```bash
git add services/interview-server/
git commit -m "feat(interview-server): 工程骨架 + 环境变量配置 + mica-voice-core 引擎 + 启动脚本"
```

## Task 2: 移植纯逻辑（Pcm16Decoder + TranscriptRules），TDD

**Files:**
- Create: `services/interview-server/src/test/java/com/aiinterview/server/Pcm16DecoderTest.java`
- Create: `services/interview-server/src/test/java/com/aiinterview/server/TranscriptRulesTest.java`
- Create: `services/interview-server/src/main/java/com/aiinterview/server/Pcm16Decoder.java`
- Create: `services/interview-server/src/main/java/com/aiinterview/server/TranscriptRules.java`

> 移植源：`services/mica-voice-gateway/src/main/java/com/aiinterview/gateway/Pcm16Decoder.java` 与 `OnlineAsrWebSocketHandler.java` 中的两个 static 方法。断言直接沿用旧网关 `OnlineAsrWebSocketHandlerTest.java`（去掉 Spring 依赖，用纯 JUnit 5）。

- [x] **Step 1: 写失败测试 Pcm16DecoderTest**

```java
package com.aiinterview.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Pcm16DecoderTest {

    @Test
    void decodesLittleEndianPcm16Samples() {
        byte[] pcm = new byte[] {0x00, 0x00, 0x00, (byte) 0x80, (byte) 0xFF, 0x7F};
        assertArrayEquals(new float[] {0.0f, -1.0f, 0.999969482421875f}, Pcm16Decoder.decode(pcm));
    }

    @Test
    void rejectsOddLengthPayload() {
        assertThrows(IllegalArgumentException.class, () -> Pcm16Decoder.decode(new byte[] {0x00}));
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=Pcm16DecoderTest`
Expected: 编译错误 `cannot find symbol Pcm16Decoder`。

- [x] **Step 3: 写最小实现 Pcm16Decoder（原样移植）**

```java
package com.aiinterview.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Converts signed little-endian PCM16 frames to sherpa-onnx float samples. */
final class Pcm16Decoder {

    private Pcm16Decoder() {
    }

    static float[] decode(byte[] pcm) {
        if (pcm == null || pcm.length % 2 != 0) {
            throw new IllegalArgumentException("pcm16 数据长度必须是 2 的倍数");
        }
        ByteBuffer buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        float[] samples = new float[pcm.length / 2];
        for (int index = 0; index < samples.length; index++) {
            samples[index] = buffer.getShort() / 32768.0f;
        }
        return samples;
    }
}
```

- [x] **Step 4: 写失败测试 TranscriptRulesTest（断言移植自旧网关）**

```java
package com.aiinterview.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptRulesTest {

    @Test
    void delaysVeryShortEndpointTextWithoutPunctuation() {
        assertTrue(TranscriptRules.shouldDelayEndpoint("思路"));
        assertTrue(TranscriptRules.shouldDelayEndpoint("请介绍一下"));
        assertFalse(TranscriptRules.shouldDelayEndpoint("请介绍一下自己"));
        assertFalse(TranscriptRules.shouldDelayEndpoint("思路里的传播机制"));
        assertFalse(TranscriptRules.shouldDelayEndpoint("好。"));
    }

    @Test
    void publishesOnlyChangedTranscriptTextButAllowsPartialToFinalTransition() {
        assertFalse(TranscriptRules.shouldPublishTranscript(
            "partial", "请介绍一下", "partial", "请介绍一下"));
        assertTrue(TranscriptRules.shouldPublishTranscript(
            "partial", "请介绍一下", "final", "请介绍一下"));
        assertTrue(TranscriptRules.shouldPublishTranscript(
            "partial", "请介绍一下", "partial", "请介绍一下自己"));
    }
}
```

- [x] **Step 5: 运行确认失败**

Run: `mvn -q test -Dtest=TranscriptRulesTest`
Expected: 编译错误 `cannot find symbol TranscriptRules`。

- [x] **Step 6: 写最小实现 TranscriptRules（逻辑原样移植，去掉 Spring 依赖）**

```java
package com.aiinterview.server;

import java.util.regex.Pattern;

final class TranscriptRules {

    private static final int MIN_ENDPOINT_TEXT_LENGTH = 4;
    private static final Pattern SHORT_QUESTION_PREFIX = Pattern.compile(
        "^(请|能否|可以|说说|谈谈|介绍|解释|描述|为什么|怎么|如何|什么|哪些|是否|有没有).{0,5}$"
    );

    private TranscriptRules() {
    }

    static boolean shouldDelayEndpoint(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            return true;
        }
        if (SHORT_QUESTION_PREFIX.matcher(normalized).matches()
            && !"请介绍一下自己".equals(normalized)) {
            return true;
        }
        if (normalized.length() >= MIN_ENDPOINT_TEXT_LENGTH) {
            return false;
        }
        return !normalized.matches(".*[?？。！!；;]$");
    }

    static boolean shouldPublishTranscript(
        String previousType, String previousText, String nextType, String nextText
    ) {
        String previous = previousText == null ? "" : previousText.trim();
        String next = nextText == null ? "" : nextText.trim();
        if (next.isEmpty()) {
            return false;
        }
        return !next.equals(previous) || !String.valueOf(nextType).equals(String.valueOf(previousType));
    }
}
```

- [x] **Step 7: 运行全部测试确认通过**

Run: `mvn -q test -Dtest=Pcm16DecoderTest,TranscriptRulesTest`
Expected: Tests run: 4, Failures: 0, Errors: 0。

- [x] **Step 8: 提交**

```bash
git add services/interview-server/src/main/java/com/aiinterview/server/Pcm16Decoder.java services/interview-server/src/main/java/com/aiinterview/server/TranscriptRules.java services/interview-server/src/test/java/com/aiinterview/server/Pcm16DecoderTest.java services/interview-server/src/test/java/com/aiinterview/server/TranscriptRulesTest.java
git commit -m "feat(interview-server): 移植 PCM 解码与端点/发布规则（含单测）"
```

## Task 3: WebSocket ASR 处理器（协议 100% 兼容）

**Files:**
- Create: `services/interview-server/src/test/java/com/aiinterview/server/AsrWebSocketUpgradeTest.java`
- Create: `services/interview-server/src/main/java/com/aiinterview/server/AsrWebSocketUpgrade.java`

> 移植源：`services/mica-voice-gateway/src/main/java/com/aiinterview/gateway/OnlineAsrWebSocketHandler.java`。Spring 的 `WebSocketSession attributes` 换成"每个连接一个 `AsrWebSocketUpgrade` 实例"（Router 的 route lambda 每次请求都 new，天然按连接隔离）。

**协议兼容清单（必须逐条满足，Python `mica_voice_client.py` 依赖）：**

| 方向 | 帧 | 载荷 | 行为 |
|---|---|---|---|
| 服务端 | 文本 | `{"type":"ready"}` | 握手成功后立即发送 |
| 客户端 | 文本 | `{"type":"config",...}` | 回 `{"type":"config-ack"}` |
| 客户端 | 文本 | `{"type":"start"}` | 重建流，回 `{"type":"started"}` |
| 客户端 | 二进制 | PCM16 S16LE/16kHz/mono | 解码→`acceptWaveform`→decode→`partial`/`final` |
| 客户端 | 文本 | `{"type":"stop"}` | `inputFinished`+decode→`final`→释放流 |
| 客户端 | 文本 | `{"type":"close"}` | 关闭连接 |
| 客户端 | 文本 | 其他 | `{"type":"error","text":"未知控制帧: ..."}` |
| 服务端 | 文本 | `{"type":"partial"/"final","text":"..."}` | 增量/最终转写（按 TranscriptRules 去重） |
| 服务端 | 文本 | `{"type":"error","text":"..."}` | 识别异常 |

- [x] **Step 1: 写失败测试（Mockito mock WebSocketRequest/WebSocketResponse 与 OnlineAsrService）**

```java
package com.aiinterview.server;

import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import net.dreamlu.mica.voice.asr.OnlineAsrService;
import org.junit.jupiter.api.Test;
import tech.smartboot.feat.core.server.WebSocketRequest;
import tech.smartboot.feat.core.server.WebSocketResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsrWebSocketUpgradeTest {

    private final WebSocketRequest request = mock(WebSocketRequest.class);
    private final WebSocketResponse response = mock(WebSocketResponse.class);
    private final OnlineAsrService service = mock(OnlineAsrService.class);
    private final OnlineStream stream = mock(OnlineStream.class);
    private final OnlineRecognizer recognizer = mock(OnlineRecognizer.class);
    private final AsrWebSocketUpgrade upgrade = new AsrWebSocketUpgrade(service);

    private Result result(String text) {
        return new Result(text);
    }

    private void text(String json) {
        when(request.getFrameOpcode()).thenReturn(1); // WebSocket.OPCODE_TEXT
        when(request.getPayload()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
        upgrade.handle(request, response);
    }

    @Test
    void sendsReadyOnHandshake() {
        upgrade.onHandShake(request, response);
        verify(response).sendTextMessage(contains("\"type\":\"ready\""));
    }

    @Test
    void startCreatesStreamAndAcks() {
        when(service.createStream()).thenReturn(stream);
        text("{\"type\":\"start\"}");
        verify(response).sendTextMessage(contains("\"type\":\"started\""));
    }

    @Test
    void unknownControlFrameEmitsError() {
        text("{\"type\":\"whatever\"}");
        verify(response).sendTextMessage(contains("\"type\":\"error\""));
    }

    @Test
    void stopInputFinishedEmitsFinalAndReleasesStream() {
        when(service.createStream()).thenReturn(stream);
        when(service.getRecognizer()).thenReturn(recognizer);
        when(recognizer.isReady(stream)).thenReturn(false);
        when(recognizer.getResult(stream)).thenReturn(new OnlineRecognizerResult(
            "请简单介绍一下自己", new String[0], new float[0], new float[0]));
        text("{\"type\":\"start\"}");
        text("{\"type\":\"stop\"}");
        verify(stream).inputFinished();
        verify(response).sendTextMessage(contains("\"type\":\"final\""));
        verify(stream).release();
    }

    @Test
    void binaryFrameDecodesAndPublishesFinalWhenEndpointDetected() {
        when(service.createStream()).thenReturn(stream);
        when(service.getRecognizer()).thenReturn(recognizer);
        when(recognizer.isReady(stream)).thenReturn(false);
        when(recognizer.getResult(stream)).thenReturn(new OnlineRecognizerResult(
            "请简单介绍一下自己", new String[0], new float[0], new float[0]));
        when(recognizer.isEndpoint(stream)).thenReturn(true);
        when(request.getFrameOpcode()).thenReturn(2); // WebSocket.OPCODE_BINARY
        when(request.getPayload()).thenReturn(new byte[] {0x00, 0x00});
        upgrade.handle(request, response);
        verify(stream).acceptWaveform(argThat(samples -> samples.length == 1 && samples[0] == 0.0f), argThat(rate -> rate == 16000));
        verify(response).sendTextMessage(contains("\"type\":\"final\""));
        verify(recognizer).reset(stream);
    }

    @Test
    void closeControlFrameClosesConnection() {
        text("{\"type\":\"close\"}");
        verify(response).close();
        verify(service, never()).createStream();
    }
}
```

说明：`OnlineRecognizerResult` 构造器沿用旧网关测试代码（`String, String[], float[], float[]`）；若 mica-sherpa-onnx 1.13.6 签名不同，编译期会暴露，以实际签名为准。

- [x] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=AsrWebSocketUpgradeTest`
Expected: 编译错误 `cannot find symbol AsrWebSocketUpgrade`。

- [x] **Step 3: 写最小实现 AsrWebSocketUpgrade（协议与原网关一致）**

```java
package com.aiinterview.server;

import com.alibaba.fastjson2.JSONObject;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import net.dreamlu.mica.voice.asr.OnlineAsrService;
import tech.smartboot.feat.core.common.codec.websocket.CloseReason;
import tech.smartboot.feat.core.server.WebSocketRequest;
import tech.smartboot.feat.core.server.WebSocketResponse;
import tech.smartboot.feat.core.server.upgrade.websocket.WebSocketUpgrade;

/** 与原 Spring 网关 OnlineAsrWebSocketHandler 协议完全一致的 Feat 版。 */
public class AsrWebSocketUpgrade extends WebSocketUpgrade {

    private final OnlineAsrService asrService;

    private OnlineStream stream;
    private String lastTranscriptText = "";
    private String lastTranscriptType = "";

    public AsrWebSocketUpgrade(OnlineAsrService asrService) {
        this.asrService = asrService;
    }

    @Override
    public void onHandShake(WebSocketRequest request, WebSocketResponse response) {
        send(response, "ready", null);
    }

    @Override
    public void handleTextMessage(WebSocketRequest request, WebSocketResponse response, String message) {
        JSONObject payload = JSONObject.parseObject(message);
        String type = String.valueOf(payload.get("type"));
        if ("config".equals(type)) {
            send(response, "config-ack", null);
        } else if ("start".equals(type)) {
            replaceStream();
            send(response, "started", null);
        } else if ("stop".equals(type)) {
            finish(response);
        } else if ("close".equals(type)) {
            response.close();
        } else {
            send(response, "error", "未知控制帧: " + type);
        }
    }

    @Override
    public void handleBinaryMessage(WebSocketRequest request, WebSocketResponse response, byte[] data) {
        if (data.length == 0) {
            return;
        }
        if (stream == null) {
            replaceStream();
        }
        try {
            stream.acceptWaveform(Pcm16Decoder.decode(data), 16000);
            while (asrService.getRecognizer().isReady(stream)) {
                asrService.getRecognizer().decode(stream);
            }
            OnlineRecognizerResult result = asrService.getRecognizer().getResult(stream);
            boolean endpoint = asrService.getRecognizer().isEndpoint(stream)
                && !TranscriptRules.shouldDelayEndpoint(result.getText());
            String eventType = endpoint ? "final" : "partial";
            publishTranscript(response, eventType, result.getText());
            if (endpoint) {
                asrService.getRecognizer().reset(stream);
                clearTranscriptCache();
            }
        } catch (RuntimeException error) {
            send(response, "error", error.getMessage());
        }
    }

    @Override
    public void onClose(WebSocketRequest request, WebSocketResponse response, CloseReason closeReason) {
        release();
    }

    private void replaceStream() {
        release();
        clearTranscriptCache();
        stream = asrService.createStream();
    }

    private void finish(WebSocketResponse response) {
        if (stream == null) {
            return;
        }
        try {
            stream.inputFinished();
            while (asrService.getRecognizer().isReady(stream)) {
                asrService.getRecognizer().decode(stream);
            }
            publishTranscript(response, "final", asrService.getRecognizer().getResult(stream).getText());
        } finally {
            release();
        }
    }

    private void release() {
        if (stream != null) {
            stream.release();
            stream = null;
        }
    }

    private void publishTranscript(WebSocketResponse response, String type, String text) {
        if (!TranscriptRules.shouldPublishTranscript(lastTranscriptType, lastTranscriptText, type, text)) {
            return;
        }
        String normalized = text == null ? "" : text.trim();
        send(response, type, normalized);
        lastTranscriptText = normalized;
        lastTranscriptType = type;
    }

    private void clearTranscriptCache() {
        lastTranscriptText = "";
        lastTranscriptType = "";
    }

    private void send(WebSocketResponse response, String type, String value) {
        JSONObject payload = new JSONObject();
        payload.put("type", type);
        if (value != null) {
            payload.put("text", value);
        }
        response.sendTextMessage(payload.toJSONString());
    }
}
```

- [x] **Step 4: 运行全部测试**

Run: `mvn -q test`
Expected: Tests run: 9（Pcm16 2 + Rules 2 + Upgrade 5）, Failures: 0, Errors: 0。

- [x] **Step 5: 提交**

```bash
git add services/interview-server/src/main/java/com/aiinterview/server/AsrWebSocketUpgrade.java services/interview-server/src/test/java/com/aiinterview/server/AsrWebSocketUpgradeTest.java
git commit -m "feat(interview-server): WS ASR 处理器移植（协议兼容）+ 帧处理单测"
```

## Task 4: 路由装配 + 内嵌 HTML

**Files:**
- Modify: `services/interview-server/src/main/java/com/aiinterview/server/InterviewServerApplication.java`
- Create: `services/interview-server/src/main/resources/static/index.html`

> 参考：`third_party/feat/feat-test` 的 `WebSocketRouterDemo.java`（Router + upgrade）、`ColorGameApp.java`（`HttpStaticResourceHandler` classpath 静态页）、`Router.java` 构造函数（`new Router(HttpHandler defaultHandler)` 已由源码证实）。

- [x] **Step 1: 编写内嵌 HTML 页面（classpath:static 下，Copilot 占位页）**

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>AI 面试 Copilot</title>
    <style>
        body { font-family: system-ui, sans-serif; margin: 2rem auto; max-width: 720px; padding: 0 1rem; }
        #status { font-size: 0.9rem; color: #666; margin-bottom: 0.5rem; }
        #transcript { min-height: 4rem; border: 1px solid #ddd; border-radius: 8px; padding: 1rem; white-space: pre-wrap; }
    </style>
</head>
<body>
    <h1>AI 面试 Copilot（Feat 集成服务占位页）</h1>
    <p id="status">检查服务状态…</p>
    <div id="transcript">转写区：等待 WS 数据</div>
    <script>
        fetch('/api/health').then(function (r) { return r.json(); }).then(function (data) {
            document.getElementById('status').textContent = '服务状态: ' + data.status;
        });
        var ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/mica/voice/ws/online-asr');
        ws.onopen = function () {
            ws.send(JSON.stringify({ type: 'config', sampleRate: 16000, format: 'pcm16le', source: 'mixed' }));
            ws.send(JSON.stringify({ type: 'start' }));
        };
        ws.onmessage = function (event) {
            var message = JSON.parse(event.data);
            if (message.type === 'partial' || message.type === 'final') {
                document.getElementById('transcript').textContent = message.text;
            }
        };
    </script>
</body>
</html>
```

- [x] **Step 2: 重写主程序：Router 挂健康检查 + WS 升级，未匹配路径落到静态资源**

```java
package com.aiinterview.server;

import tech.smartboot.feat.Feat;
import tech.smartboot.feat.core.server.HttpServer;
import tech.smartboot.feat.core.server.handler.HttpStaticResourceHandler;
import tech.smartboot.feat.router.Router;

public class InterviewServerApplication {

    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        config.validate();

        Router router = new Router(new HttpStaticResourceHandler(opt -> opt.baseDir("classpath:static")));
        router.route("/api/health", (ctx) ->
            ctx.Response.write("{\"status\":\"ok\"}")
        );
        router.route("/mica/voice/ws/online-asr", (ctx) ->
            ctx.Request.upgrade(new AsrWebSocketUpgrade(AsrEngine.getInstance().onlineAsr(config)))
        );

        HttpServer server = Feat.httpServer().httpHandler(router).listen(config.port());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            AsrEngine.getInstance().close();
            server.shutdown();
        }));
        System.out.println("interview-server listening on http://localhost:" + config.port());
        System.out.println("WS ASR: ws://localhost:" + config.port() + "/mica/voice/ws/online-asr");
    }
}
```

- [x] **Step 3: 启动验证（模型已就绪时）**

Run: `powershell -File services/interview-server/start.ps1`
Expected: 浏览器访问 `http://localhost:18080/` 显示占位页且状态显示 `ok`；`http://localhost:18080/api/health` 返回 `{"status":"ok"}`；WS 路径握手成功（浏览器控制台无报错，`onopen` 触发）。

- [x] **Step 4: 提交**

```bash
git add services/interview-server/src/main/java/com/aiinterview/server/InterviewServerApplication.java services/interview-server/src/main/resources/static/index.html
git commit -m "feat(interview-server): Router 装配 WS/健康检查 + 内嵌静态页面"
```

## Task 5: LLM 回答服务（feat-ai）

**Files:**
- Create: `services/interview-server/src/main/java/com/aiinterview/server/AnswerService.java`
- Modify: `services/interview-server/src/main/java/com/aiinterview/server/InterviewServerApplication.java`

> 替代 Python `services/answer_service.py` 的流式回答核心：feat-ai `ChatModel.chatStream` + 阿里百炼 OpenAI 兼容 endpoint。`FeatAI.chatModel`、`ChatModel.chatStream(List, List, ChatStreamListener)`、`Message.ofSystem/ofUser`、`ChatResponse.getContent()` 均已由 feat-ai 2.4.0 源码证实；`router.route(path, "GET", handler)` 与 `ctx.Request.getParameter` 由 RouterDemo1/HttpRequest 证实。

- [x] **Step 1: 编写 AnswerService**

```java
package com.aiinterview.server;

import tech.smartboot.feat.ai.FeatAI;
import tech.smartboot.feat.ai.chat.ChatModel;
import tech.smartboot.feat.ai.chat.ChatStreamListener;
import tech.smartboot.feat.ai.chat.entity.ChatResponse;
import tech.smartboot.feat.ai.chat.entity.Message;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 基于 OpenAI 兼容接口的流式回答服务（替代 Python answer_service 核心）。 */
public final class AnswerService {

    private static final String SYSTEM_PROMPT =
        "你是 AI 面试回答助手。基于面试官问题与给定上下文，输出 3-5 条回答要点和一段简洁参考回答。";

    private final ChatModel chatModel;

    public AnswerService(AppConfig config) {
        this.chatModel = FeatAI.chatModel(opts -> opts
            .baseUrl(config.llmBaseUrl())
            .model(config.llmModel())
            .apiKey(config.llmApiKey())
            .system(SYSTEM_PROMPT));
    }

    /** 非流式入口：聚合流式片段后一次性返回；30 秒超时。 */
    public String answer(String question, String context) throws Exception {
        StringBuilder builder = new StringBuilder();
        CompletableFuture<Void> done = new CompletableFuture<>();
        chatModel.chatStream(
            Arrays.asList(
                Message.ofSystem("上下文（简历/JD 摘要）：\n" + context),
                Message.ofUser(question)
            ),
            null,
            new ChatStreamListener() {
                @Override
                public void onStreamResponse(String content) {
                    builder.append(content);
                }

                @Override
                public void onCompletion(ChatResponse chatResponse) {
                    done.complete(null);
                }

                @Override
                public void onError(Throwable throwable) {
                    done.completeExceptionally(throwable);
                }
            });
        done.get(30, TimeUnit.SECONDS);
        return builder.toString();
    }
}
```

- [x] **Step 2: 注册 /api/copilot/answer 路由（未配置 LLM_API_KEY 时不注册该路由）**

修改 `InterviewServerApplication.main`，在 `/api/health` 路由之后追加：

```java
        if (config.llmApiKey() != null && !config.llmApiKey().trim().isEmpty()) {
            AnswerService answerService = new AnswerService(config);
            router.route("/api/copilot/answer", "GET", (ctx) -> {
                String question = ctx.Request.getParameter("question");
                String context = String.valueOf(ctx.Request.getParameter("context"));
                try {
                    String answer = answerService.answer(question == null ? "" : question, context);
                    ctx.Response.write("{\"answer\":" + com.alibaba.fastjson2.JSON.toJSONString(answer) + "}");
                } catch (Exception error) {
                    ctx.Response.write("{\"error\":" + com.alibaba.fastjson2.JSON.toJSONString(error.getMessage()) + "}");
                }
            });
        }
```

- [x] **Step 3: 编译 + 手动验证（需要 LLM_API_KEY；无 key 时路由不注册属预期）**

Run: `mvn -q -DskipTests package`（编译）
Run: 配置 `LLM_API_KEY` 后启动，访问
```
http://localhost:18080/api/copilot/answer?question=%E8%AF%B7%E4%BB%8B%E7%BB%8D%E4%B8%80%E4%B8%8B%E8%87%AA%E5%B7%B1&context=
```
Expected: 返回 `{"answer":"..."}` JSON，内容为回答要点。

- [x] **Step 4: 提交**

```bash
git add services/interview-server/src/main/java/com/aiinterview/server/AnswerService.java services/interview-server/src/main/java/com/aiinterview/server/InterviewServerApplication.java
git commit -m "feat(interview-server): feat-ai 流式回答服务 + /api/copilot/answer"
```

## Task 6: 端到端验收与接管清单

**Files:**
- Create: `services/interview-server/README.md`

- [x] **Step 1: Python 侧直连验证（协议零改动验收）**

运行现有 Python 客户端单测确认客户端逻辑不受服务器更换影响；再跑一次性冒烟脚本（临时执行，不入库）：

```powershell
python -c "import sys; sys.path.insert(0, '.'); from services.mica_voice_client import MicaVoiceClient; c = MicaVoiceClient('ws://localhost:18080/mica/voice/ws/online-asr'); c.start(); import time; time.sleep(0.5); c.finish(); print([r.text for r in c._results]); c.close();"
```

Expected: 连上、收到 `final`（静音下文本为空但流程不报错即协议通过）。

- [x] **Step 2: 真实语音验收**

对着麦克风说话 5 秒，浏览器 `http://localhost:18080/` 转写区出现中文文本、`partial`/`final` 事件正常，记录首字延迟。

- [x] **Step 3: 回归 Flask 侧现有测试**

Run: `python -m pytest tests/ -q`
Expected: 与改造前一致（本计划不修改任何 Python 代码，失败即异常需排查）。

- [x] **Step 4: 编写 README.md**

内容：环境变量表（`SERVER_PORT`/`MICA_VOICE_MODELS_DIR`/`LLM_API_KEY`/`LLM_BASE_URL`/`LLM_MODEL`）、模型下载方式（`powershell -File third_party/mica-voice/models/scripts/download-models.ps1 x-asr`，模型放 `MICA_VOICE_MODELS_DIR`）、启动命令、WS 协议说明（引用 Task 3 清单）、与旧 Spring 网关的差异说明。

- [x] **Step 5: 提交**

```bash
git add services/interview-server/README.md
git commit -m "docs(interview-server): 运行说明与端到端验收记录"
```

## 明确不做（后续计划）

- Flask 业务模组（登录/简历/面试计划/复盘）与数据库（MySQL → feat-cloud + MyBatis）迁移：单独计划，等本服务稳定对接后再启动。
- feat-cloud 注解式 `@Controller` 分层：本计划统一用 feat-core `Router`；后续需要注解分层时再引入 feat-cloud（不冲突）。
- TTS/声纹/降噪等其他 mica-voice 能力：`AsrEngine` 已预留门面模式，按需扩展。

## 风险与未验证项

| 项 | 状态 |
|---|---|
| feat WebSocket 文本/二进制帧分发 | 已被 `feat-test/WebSocketTest` 与 `WebSocketUpgrade` 源码证实（OPCODE_TEXT/BINARY 分发） |
| `Router(HttpHandler defaultHandler)` 构造器 | 已由 `Router.java` 源码证实；未匹配路径落到静态资源处理器 |
| `ctx.Request.upgrade(...)` / `ctx.Response.write(...)` / `getParameter` | 已由 `WebSocketRouterDemo`/`RouterDemo1`/`HttpRequest` javadoc 证实 |
| mica-voice-core 在 JDK8 + 非 Spring 环境加载 mica-sherpa-onnx native | README 声明 core 为纯 Java 门面；Task 1 Step 7 启动验证兜底 |
| `OnlineRecognizerResult` 构造器签名 | 沿用旧网关测试代码（`String, String[], float[], float[]`）；若 mica-sherpa-onnx 1.13.6 签名不同，Task 3 Step 2 编译期暴露并修正 |
| feat-ai 与 DashScope 兼容 endpoint 的流式协议 | 已由调研证实支持 baseUrl + SSE；Task 5 Step 3 真实调用兜底 |
| Mockito 4.11.0 在 JDK8 mock 具体类（OnlineAsrService） | 默认 subclass mockmaker 支持具体类；若遇 final/native 冲突，改用 `mockito-inline` 4.11.0 |
