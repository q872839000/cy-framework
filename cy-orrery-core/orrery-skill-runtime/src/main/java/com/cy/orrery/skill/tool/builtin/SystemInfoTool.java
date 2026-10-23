// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.tool.builtin;

import com.cy.orrery.skill.tool.ToolCategory;
import com.cy.orrery.skill.tool.ToolExecutionContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 系统信息采集工具（高层级工具）
 * <p>
 * 对齐 Anthropic 工具设计原则："Instead of list_users + list_events + create_event,
 * build schedule_event. Higher-level, task-oriented tools outperform granular CRUD tools."
 * </p>
 * <p>
 * 一次调用即可收集操作系统、CPU、内存、磁盘、网卡等完整系统信息，
 * 避免 LLM 逐个调用 shell_exec（systeminfo、wmic cpu、wmic memory...）导致
 * 消耗过多 Agent 循环轮次。
 * </p>
 *
 * @author pcy
 * @date 2026/04/08
 */
public class SystemInfoTool extends AbstractBuiltinTool {

    private static final String TOOL_ID = "system_info";
    private static final String TOOL_NAME = "系统信息采集";
    private static final String TOOL_SUMMARY =
            "仅当用户明确询问系统硬件配置、操作系统版本、CPU/内存/磁盘/GPU/网卡信息时使用。" +
            "一次调用收集完整系统信息并返回结构化概要。" +
            "不要在用户询问软件状态、Docker、服务、进程等非硬件问题时调用此工具";

    private static final String PARAMETER_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "sections": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "要采集的信息类别（可选），默认全部采集。可选值：os, cpu, memory, disk, gpu, network"
                }
              }
            }
            """;

    /** 单个命令的超时（毫秒） */
    private static final long CMD_TIMEOUT_MS = 15_000L;

    /** 单命令输出的最大行数（防超长输出耗尽上下文）。 */
    private static final int MAX_OUTPUT_LINES = 200;

    /** 默认采集的信息类别（sections 参数缺省时全部采集）。 */
    private static final List<String> DEFAULT_SECTIONS =
            List.of("os", "cpu", "memory", "disk", "gpu", "network");

    /** 构造系统信息采集工具（执行类，只读语义）。 */
    public SystemInfoTool() {
        super(TOOL_ID, TOOL_NAME, TOOL_SUMMARY, ToolCategory.EXECUTION);
    }

    @Override
    public String getParameterSchema() {
        return PARAMETER_SCHEMA;
    }

    @Override
    protected Map<String, Object> doExecute(Map<String, Object> input, ToolExecutionContext context) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        // 解析要采集的 sections（默认全部）
        List<String> sections = input.get("sections") instanceof List<?> list
                ? list.stream().map(Object::toString).map(String::toLowerCase).toList()
                : DEFAULT_SECTIONS;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("platform", isWindows ? "windows" : "unix");

        if (isWindows) {
            collectWindows(result, sections);
        } else {
            collectUnix(result, sections);
        }

        return result;
    }

    private void collectWindows(Map<String, Object> result, List<String> sections) {
        if (sections.contains("os")) {
            result.put("os", runCmd("systeminfo"));
        }
        if (sections.contains("cpu")) {
            result.put("cpu", runCmd("wmic cpu get Name,NumberOfCores,NumberOfLogicalProcessors /format:list"));
        }
        if (sections.contains("memory")) {
            result.put("memory", runCmd("wmic memorychip get Capacity,Manufacturer,Speed /format:list"));
        }
        if (sections.contains("disk")) {
            result.put("disk", runCmd("wmic diskdrive get Model,InterfaceType,Size /format:list"));
        }
        if (sections.contains("gpu")) {
            result.put("gpu", runCmd("wmic path win32_videocontroller get Name,AdapterRAM,DriverVersion /format:list"));
        }
        if (sections.contains("network")) {
            result.put("network", runCmd("ipconfig"));
        }
    }

    private void collectUnix(Map<String, Object> result, List<String> sections) {
        if (sections.contains("os")) {
            result.put("os", runCmd("uname -a && cat /etc/os-release 2>/dev/null || true"));
        }
        if (sections.contains("cpu")) {
            result.put("cpu", runCmd("lscpu 2>/dev/null || sysctl -a machdep.cpu 2>/dev/null || cat /proc/cpuinfo"));
        }
        if (sections.contains("memory")) {
            result.put("memory", runCmd("free -h 2>/dev/null || sysctl hw.memsize 2>/dev/null || true"));
        }
        if (sections.contains("disk")) {
            result.put("disk", runCmd("df -h 2>/dev/null || true"));
        }
        if (sections.contains("gpu")) {
            result.put("gpu", runCmd("lspci 2>/dev/null | grep -i vga || system_profiler SPDisplaysDataType 2>/dev/null || echo 'N/A'"));
        }
        if (sections.contains("network")) {
            result.put("network", runCmd("ip addr 2>/dev/null || ifconfig 2>/dev/null || true"));
        }
    }

    /**
     * 运行单个命令并返回输出（超时保护）
     */
    private String runCmd(String command) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        try {
            ProcessBuilder pb = isWindows
                    ? new ProcessBuilder("cmd.exe", "/c", "chcp 65001 >nul && " + command)
                    : new ProcessBuilder("/bin/sh", "-c", command);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null && lineCount < MAX_OUTPUT_LINES) {
                    if (!line.isBlank()) {
                        sb.append(line).append('\n');
                        lineCount++;
                    }
                }
            }
            boolean finished = process.waitFor(CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                sb.append("\n[命令超时]");
            }
            return sb.toString().trim();
        } catch (IOException e) {
            log.warn("命令执行失败: cmd={}, error={}", command, e.getMessage());
            return "执行失败: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("命令等待被中断: cmd={}", command);
            return "执行被中断";
        }
    }
}
