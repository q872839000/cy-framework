// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.tool.builtin;

import com.cy.orrery.protocol.enums.SideEffectLevel;
import com.cy.orrery.skill.tool.ToolCategory;
import com.cy.orrery.skill.tool.ToolExecutionContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后台进程管理工具
 * <p>
 * 参考 OpenClaw 的 {@code process} 工具，管理后台运行的 exec 会话。
 * 支持以下操作：
 * <ul>
 *   <li>{@code list} — 列出所有后台进程会话</li>
 *   <li>{@code poll} — 轮询指定会话的新输出</li>
 *   <li>{@code kill} — 终止指定会话</li>
 *   <li>{@code clear} — 清理已结束的会话记录</li>
 * </ul>
 * 每个会话由 sessionId 标识，会话信息包含进程 PID、启动时间、状态和尾部输出。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
public class ProcessManageTool extends AbstractBuiltinTool {

    private static final String TOOL_ID = "process_manage";
    private static final String TOOL_NAME = "进程管理";
    private static final String TOOL_SUMMARY = "管理后台运行的进程会话：列出、轮询输出、终止、清理";

    private static final String PARAMETER_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["list", "poll", "kill", "clear"],
                  "description": "进程管理操作：list=列出, poll=轮询输出, kill=终止, clear=清理已结束会话"
                },
                "sessionId": {
                  "type": "string",
                  "description": "会话ID（list 操作外均必填）"
                }
              },
              "required": ["action"]
            }
            """;

    /**
     * 进程会话注册表（sessionId → ProcessSession）
     */
    private final Map<String, ProcessSession> sessions = new ConcurrentHashMap<>();

    /** 构造进程管理工具（执行类，副作用 HIGH，仅 owner 可调）。 */
    public ProcessManageTool() {
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

    /**
     * 注册后台进程会话（由 ShellExecTool 后台模式调用）
     *
     * @param sessionId 会话ID
     * @param session   会话信息
     */
    public void registerSession(String sessionId, ProcessSession session) {
        sessions.put(sessionId, session);
        log.info("后台会话注册: sessionId={}, command='{}'", sessionId, session.getCommand());
    }

    @Override
    protected Map<String, Object> doExecute(Map<String, Object> input, ToolExecutionContext context) {
        String action = readStringParam(input, "action", true);

        switch (action) {
            case "list":
                return doList();
            case "poll":
                return doPoll(input);
            case "kill":
                return doKill(input);
            case "clear":
                return doClear(input);
            default:
                return failureOutput("未知操作: " + action);
        }
    }

    /**
     * 列出所有会话
     */
    private Map<String, Object> doList() {
        List<Map<String, Object>> sessionList = new ArrayList<>();
        for (Map.Entry<String, ProcessSession> entry : sessions.entrySet()) {
            ProcessSession s = entry.getValue();
            Map<String, Object> info = new HashMap<>();
            info.put("sessionId", entry.getKey());
            info.put("command", s.getCommand());
            info.put("status", s.isAlive() ? "running" : "exited");
            info.put("pid", s.getPid());
            info.put("startedAt", s.getStartedAt());
            if (!s.isAlive()) {
                info.put("exitCode", s.getExitCode());
            }
            info.put("runtimeMs", System.currentTimeMillis() - s.getStartedAt());
            sessionList.add(info);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("sessions", sessionList);
        result.put("total", sessionList.size());
        return result;
    }

    /**
     * 轮询指定会话的输出
     */
    private Map<String, Object> doPoll(Map<String, Object> input) {
        String sessionId = readStringParam(input, "sessionId", true);
        ProcessSession session = sessions.get(sessionId);
        if (session == null) {
            return failureOutput("会话未找到: " + sessionId);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", session.isAlive() ? "running" : "exited");
        result.put("sessionId", sessionId);
        result.put("output", session.drainOutput());
        if (!session.isAlive()) {
            result.put("exitCode", session.getExitCode());
        }
        return result;
    }

    /**
     * 终止指定会话
     */
    private Map<String, Object> doKill(Map<String, Object> input) {
        String sessionId = readStringParam(input, "sessionId", true);
        ProcessSession session = sessions.get(sessionId);
        if (session == null) {
            return failureOutput("会话未找到: " + sessionId);
        }

        session.kill();
        log.info("后台会话已终止: sessionId={}", sessionId);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("sessionId", sessionId);
        result.put("message", "会话已终止");
        return result;
    }

    /**
     * 清理已结束的会话
     */
    private Map<String, Object> doClear(Map<String, Object> input) {
        String sessionId = readStringParam(input, "sessionId", false);

        if (sessionId != null) {
            ProcessSession session = sessions.get(sessionId);
            if (session == null) {
                return failureOutput("会话未找到: " + sessionId);
            }
            if (session.isAlive()) {
                return failureOutput("会话仍在运行，无法清理: " + sessionId);
            }
            sessions.remove(sessionId);
            return successOutput("message", "会话已清理: " + sessionId);
        }

        // 清理所有已结束会话
        int cleared = 0;
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, ProcessSession> entry : sessions.entrySet()) {
            if (!entry.getValue().isAlive()) {
                toRemove.add(entry.getKey());
            }
        }
        for (String id : toRemove) {
            sessions.remove(id);
            cleared++;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("cleared", cleared);
        return result;
    }

    // ========== 内部数据结构 ==========

    /**
     * 后台进程会话
     */
    public static class ProcessSession {

        private final String command;
        private final long pid;
        private final long startedAt;
        private final Process process;
        private final StringBuilder outputBuffer = new StringBuilder();

        /**
         * 构造后台进程会话。
         *
         * @param command 原始命令
         * @param process 进程句柄
         */
        public ProcessSession(String command, Process process) {
            this.command = command;
            this.process = process;
            this.pid = process.pid();
            this.startedAt = System.currentTimeMillis();
        }

        public String getCommand() {
            return command;
        }

        public long getPid() {
            return pid;
        }

        public long getStartedAt() {
            return startedAt;
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        public int getExitCode() {
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException e) {
                return -1;
            }
        }

        /**
         * 追加输出
         */
        public synchronized void appendOutput(String text) {
            outputBuffer.append(text);
        }

        /**
         * 排空输出缓冲区并返回内容
         */
        public synchronized String drainOutput() {
            String output = outputBuffer.toString();
            outputBuffer.setLength(0);
            return output;
        }

        /**
         * 终止进程
         */
        public void kill() {
            process.destroyForcibly();
        }
    }
}
