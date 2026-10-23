package com.cy.orrery.skill.tool.builtin;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 控制台输出智能解码单元测试（GBK 根治的回归保护）。
 *
 * @author pcy
 */
class ShellExecToolDecodeTest {

    /**
     * UTF-8 原样通过（chcp 65001 后的命令输出 / Java 工具输出）。
     */
    @Test
    void shouldDecodeUtf8AsIs() {
        String text = "进程信息：JAVA 进程列表\nPID=1234";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        assertEquals(text, ShellExecTool.decodeConsoleOutput(bytes));
    }

    /**
     * GBK 编码的中文输出回退解码（Windows 中文版控制台 cmd/系统命令）。
     */
    @Test
    void shouldFallbackToGbkForLegacyConsoleOutput() {
        String text = "映像名称                       PID 会话名\r\njava.exe                      1234 Console";
        byte[] bytes = text.getBytes(Charset.forName("GBK"));

        assertEquals(text, ShellExecTool.decodeConsoleOutput(bytes));
    }

    /**
     * 空与纯 ASCII 输入稳健处理。
     */
    @Test
    void shouldHandleEmptyAndAscii() {
        assertEquals("", ShellExecTool.decodeConsoleOutput(new byte[0]));
        assertEquals("", ShellExecTool.decodeConsoleOutput(null));
        assertEquals("tasklist /fo table", ShellExecTool.decodeConsoleOutput("tasklist /fo table".getBytes(StandardCharsets.US_ASCII)));
    }
}
