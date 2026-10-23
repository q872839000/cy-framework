// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.tool.builtin;

import com.cy.orrery.protocol.enums.SideEffectLevel;
import com.cy.orrery.skill.tool.ToolCategory;
import com.cy.orrery.skill.tool.ToolExecutionContext;
import com.cy.orrery.skill.tool.security.ToolSecurityPolicy;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Shell 命令执行工具
 * <p>
 * 参考 OpenClaw 的 {@code exec} 工具，在指定工作目录下执行 Shell 命令。
 * 支持超时控制、输出截断、环境变量注入。
 * 沙箱模式下命令执行受限于沙箱根目录。
 * </p>
 * <p>
 * <b>安全机制（多层防御）：</b>
 * <ol>
 *   <li><b>ownerOnly</b>：此工具默认标记为 ownerOnly，非 owner 身份无法调用</li>
 *   <li><b>沙箱强制</b>：由 {@link AbstractBuiltinTool} 的安全前置校验强制沙箱模式</li>
 *   <li><b>命令过滤</b>：通过 {@link ToolSecurityPolicy#checkCommand} 执行
 *       危险关键词 + 正则黑名单 + 正则白名单三层过滤</li>
 *   <li><b>超时控制</b>：命令执行超时后强制终止进程</li>
 *   <li><b>输出截断</b>：防止命令输出耗尽内存</li>
 * </ol>
 * </p>
 * <p>
 * <b>关于命令注入风险的说明：</b><br>
 * 由于需要支持复杂的 shell 特性（管道、重定向、变量替换等），本工具必须通过 shell 解释器
 * （Windows 的 cmd.exe 或 Unix 的 /bin/sh）执行命令。虽然使用了 ProcessBuilder 的数组参数模式，
 * 但在 Windows 平台上为了解决 UTF-8 编码问题，仍需要字符串拼接 "chcp 65001 >nul && " + command。
 * <br><br>
 * 这种设计在理论上存在命令注入风险，但通过以下机制将风险降至最低：
 * <ul>
 *   <li>命令在执行前必须通过 {@link ToolSecurityPolicy} 的三层过滤</li>
 *   <li>只有 Agent owner 可以调用此工具（ownerOnly=true）</li>
 *   <li>工作目录被强制限制在沙箱根路径内</li>
 *   <li>超时机制防止恶意命令长时间占用资源</li>
 * </ul>
 * 生产环境应配置严格的命令白名单，仅允许必要的命令执行。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
public class ShellExecTool extends AbstractBuiltinTool {

    private static final String TOOL_ID = "shell_exec";
    private static final String TOOL_NAME = "命令执行";
    private static final String TOOL_SUMMARY = "在指定工作目录下执行 Shell 命令，支持超时控制和输出截断";

    private static final int DEFAULT_TIMEOUT_SEC = 60;
    private static final int MAX_OUTPUT_CHARS = 100_000;

    /** 读取缓冲区大小（字节）。 */
    private static final int READ_BUFFER_SIZE = 8192;

    /** 原始字节累积上限的字符换算倍率（UTF-8 单字符最长 4 字节）。 */
    private static final int MAX_BYTES_PER_CHAR = 4;

    /** 失败回执中输出头部的截断长度。 */
    private static final int ERROR_OUTPUT_HEAD_CHARS = 300;

    /** 日志中命令文本的截断长度。 */
    private static final int LOG_COMMAND_MAX_LEN = 120;

    private static final String PARAMETER_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "要执行的 Shell 命令"
                },
                "workdir": {
                  "type": "string",
                  "description": "命令执行的工作目录（默认使用上下文的工作目录）"
                },
                "timeoutSec": {
                  "type": "integer",
                  "description": "超时秒数（默认60秒）"
                },
                "env": {
                  "type": "object",
                  "description": "附加环境变量（key-value 对）"
                }
              },
              "required": ["command"]
            }
            """;

    /** 构造命令执行工具（执行类，副作用 HIGH，仅 owner 可调）。 */
    public ShellExecTool() {
        super(TOOL_ID, TOOL_NAME, TOOL_SUMMARY, ToolCategory.EXECUTION, SideEffectLevel.HIGH);
    }

    @Override
    public String getParameterSchema() {
        return PARAMETER_SCHEMA;
    }

    @Override
    public boolean isOwnerOnly() {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doExecute(Map<String, Object> input, ToolExecutionContext context) {
        String command = readStringParam(input, "command", true);

        // 安全检查：通过 ToolSecurityPolicy 执行命令过滤
        ToolSecurityPolicy policy = getSecurityPolicy();
        if (policy != null) {
            ToolSecurityPolicy.CommandCheckResult checkResult = policy.checkCommand(command);
            if (!checkResult.allowed()) {
                log.warn("命令被安全策略拒绝: command='{}', reason='{}'",
                        truncateCommand(command), checkResult.reason());
                return failureOutput("安全策略拒绝执行该命令: " + checkResult.reason());
            }
        }

        String workdir = readStringParam(input, "workdir", false);
        int timeoutSec = readIntParam(input, "timeoutSec", DEFAULT_TIMEOUT_SEC);

        // 超时下限保护：至少 1 秒，至多取 context 超时与参数超时的较小值
        if (timeoutSec <= 0) {
            timeoutSec = DEFAULT_TIMEOUT_SEC;
        }
        long contextTimeoutSec = context.getTimeoutMs() / 1000;
        if (contextTimeoutSec > 0 && timeoutSec > contextTimeoutSec) {
            timeoutSec = (int) contextTimeoutSec;
        }

        // 解析工作目录
        File workingDir = null;
        if (workdir != null) {
            workingDir = resolveAndAssertPath(workdir, context).toFile();
        } else if (context.getWorkingDirectory() != null) {
            workingDir = context.getWorkingDirectory().toFile();
        }

        try {
            // 构建进程
            // 注意：由于需要支持复杂的 shell 命令（管道、重定向、变量替换等），
            // 必须通过 shell 解释器执行。这里采用 ProcessBuilder 的数组参数模式，
            // 将命令作为独立参数传递，避免额外的 shell 解析层级。
            //
            // 安全保障：
            // 1. 命令已通过 ToolSecurityPolicy 的三层过滤（危险关键词 + 黑名单 + 白名单）
            // 2. ownerOnly 限制：只有 Agent owner 可以调用此工具
            // 3. 沙箱强制：工作目录被限制在沙箱根路径内
            // 4. 超时控制：防止恶意命令长时间占用资源
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            ProcessBuilder pb;
            if (isWindows) {
                // Windows: 使用 cmd.exe /c 执行命令
                // chcp 65001 切换控制台代码页到 UTF-8，解决中文乱码问题
                // 注意：这里将 "chcp 65001 >nul && " + command 作为单个参数传递给 cmd.exe
                // 虽然存在字符串拼接，但由于 command 已经过安全策略过滤，风险可控
                pb = new ProcessBuilder("cmd.exe", "/c", "chcp 65001 >nul && " + command);
            } else {
                // Unix/Linux: 使用 /bin/sh -c 执行命令
                // -c 参数后的字符串会被 shell 解释执行
                pb = new ProcessBuilder("/bin/sh", "-c", command);
            }

            if (workingDir != null && workingDir.isDirectory()) {
                pb.directory(workingDir);
            }

            pb.redirectErrorStream(true);

            // 注入环境变量
            Map<String, String> envOverrides = context.getEnvOverrides();
            if (envOverrides != null) {
                pb.environment().putAll(envOverrides);
            }
            Object envParam = input.get("env");
            if (envParam instanceof Map) {
                Map<String, String> paramEnv = (Map<String, String>) envParam;
                pb.environment().putAll(paramEnv);
            }

            long startMs = System.currentTimeMillis();
            Process process = pb.start();

            // 读取输出（按原始字节解码：先严格 UTF-8，失败回退 GBK——
            // Windows 中文版控制台（GBK 代码页）下 cmd/系统命令输出是 GBK，
            // 直接按 UTF-8 读必乱码；chcp 65001 前缀只覆盖 UTF-8 原生命令，覆盖不了系统报错文本）
            ByteArrayOutputStream rawOutput = new ByteArrayOutputStream();
            boolean outputTruncated = false;
            byte[] readBuffer = new byte[READ_BUFFER_SIZE];
            int readCount;
            while ((readCount = process.getInputStream().read(readBuffer)) != -1) {
                // 超限后继续排空管道（防子进程写满管道阻塞），但不再累积内容
                if (!outputTruncated) {
                    rawOutput.write(readBuffer, 0, readCount);
                    if (rawOutput.size() > MAX_OUTPUT_CHARS * MAX_BYTES_PER_CHAR) {
                        outputTruncated = true;
                    }
                }
            }
            String rawText = decodeConsoleOutput(rawOutput.toByteArray());
            if (rawText.length() > MAX_OUTPUT_CHARS) {
                outputTruncated = true;
                rawText = rawText.substring(0, MAX_OUTPUT_CHARS);
            }
            StringBuilder outputBuilder = new StringBuilder(rawText);

            // 等待进程结束
            boolean completed = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            long durationMs = System.currentTimeMillis() - startMs;

            if (!completed) {
                process.destroyForcibly();
                Map<String, Object> result = new HashMap<>();
                result.put("status", "timeout");
                result.put("output", outputBuilder.toString());
                result.put("durationMs", durationMs);
                result.put("truncated", outputTruncated);
                log.warn("命令执行超时: command='{}', timeoutSec={}", truncateCommand(command), timeoutSec);
                return result;
            }

            int exitCode = process.exitValue();
            Map<String, Object> result = new HashMap<>();
            result.put("status", exitCode == 0 ? "success" : "failed");
            result.put("exitCode", exitCode);
            result.put("output", outputBuilder.toString());
            result.put("durationMs", durationMs);
            result.put("truncated", outputTruncated);
            if (exitCode != 0) {
                // 失败回执统一契约：非零退出码必须携带 error 键，
                // 上游按 error 键判定失败（退出码文本对 LLM 自纠更友好）
                String head = outputBuilder.length() > ERROR_OUTPUT_HEAD_CHARS
                        ? outputBuilder.substring(0, ERROR_OUTPUT_HEAD_CHARS) + "…"
                        : outputBuilder.toString();
                result.put("error", "命令退出码 " + exitCode + ": " + head);
            }

            log.info("命令执行完成: exitCode={}, durationMs={}, command='{}'",
                    exitCode, durationMs, truncateCommand(command));
            return result;
        } catch (IOException | InterruptedException e) {
            log.error("命令执行异常: command='{}'", truncateCommand(command), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return failureOutput("命令执行异常: " + e.getMessage());
        }
    }

    /**
     * 截断命令字符串用于日志输出（防止敏感信息泄露到日志）
     *
     * @param command 原始命令
     * @return 截断后的命令（最多 {@value #LOG_COMMAND_MAX_LEN} 字符）
     */
    private String truncateCommand(String command) {
        if (command.length() > LOG_COMMAND_MAX_LEN) {
            return command.substring(0, LOG_COMMAND_MAX_LEN) + "...";
        }
        return command;
    }

    /**
     * 控制台输出智能解码：先严格 UTF-8（chcp 65001 前缀后的 UTF-8 原生命令输出），
     * 解码失败回退 GBK（Windows 中文版控制台默认代码页下 cmd / 系统命令的输出）。
     * GBK 解码几乎不失败，两条路径都确定性无乱码，不做乱码猜测。
     *
     * @param bytes 原始字节
     * @return 解码文本
     */
    static String decodeConsoleOutput(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return Charset.forName("GBK").decode(ByteBuffer.wrap(bytes)).toString();
        }
    }
}
