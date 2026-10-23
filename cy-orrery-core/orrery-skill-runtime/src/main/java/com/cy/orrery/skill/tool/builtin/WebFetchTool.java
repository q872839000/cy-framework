// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.tool.builtin;

import com.cy.orrery.skill.tool.ToolCategory;
import com.cy.orrery.skill.tool.ToolExecutionContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Web 抓取工具
 * <p>
 * 参考 OpenClaw 的 {@code web_fetch} 工具，从指定 URL 获取网页内容并返回文本。
 * 当前实现使用 Java HttpClient 进行 HTTP GET 请求，返回响应体文本。
 * 后续可集成 Readability 解析或 Firecrawl 等专业抓取引擎。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
public class WebFetchTool extends AbstractBuiltinTool {

    private static final String TOOL_ID = "web_fetch";
    private static final String TOOL_NAME = "网页抓取";
    private static final String TOOL_SUMMARY = "从指定 URL 获取网页内容，返回响应文本，支持超时和内容截断";

    private static final int DEFAULT_TIMEOUT_SEC = 30;
    private static final int DEFAULT_MAX_CHARS = 50_000;
    private static final String DEFAULT_USER_AGENT = "OrreryWebFetch/1.0";

    private static final String PARAMETER_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "url": {
                  "type": "string",
                  "description": "要抓取的 URL（必须是 http 或 https）"
                },
                "maxChars": {
                  "type": "integer",
                  "description": "响应内容最大字符数（默认50000）"
                },
                "timeoutSec": {
                  "type": "integer",
                  "description": "请求超时秒数（默认30秒）"
                }
              },
              "required": ["url"]
            }
            """;

    /** 构造网页抓取工具（网络类，只读）。 */
    public WebFetchTool() {
        super(TOOL_ID, TOOL_NAME, TOOL_SUMMARY, ToolCategory.NETWORK);
    }

    @Override
    public String getParameterSchema() {
        return PARAMETER_SCHEMA;
    }

    @Override
    protected Map<String, Object> doExecute(Map<String, Object> input, ToolExecutionContext context) {
        String url = readStringParam(input, "url", true);
        int maxChars = readIntParam(input, "maxChars", DEFAULT_MAX_CHARS);
        int timeoutSec = readIntParam(input, "timeoutSec", DEFAULT_TIMEOUT_SEC);

        // URL 校验
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return failureOutput("URL 必须以 http:// 或 https:// 开头");
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSec))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .GET()
                    .build();

            long startMs = System.currentTimeMillis();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long durationMs = System.currentTimeMillis() - startMs;

            int statusCode = response.statusCode();
            String body = response.body();
            boolean truncated = false;

            if (body != null && body.length() > maxChars) {
                body = body.substring(0, maxChars);
                truncated = true;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("status", statusCode >= 200 && statusCode < 400 ? "success" : "failed");
            result.put("statusCode", statusCode);
            result.put("content", body != null ? body : "");
            result.put("contentLength", body != null ? body.length() : 0);
            result.put("truncated", truncated);
            result.put("durationMs", durationMs);
            result.put("url", url);

            log.info("网页抓取完成: url={}, statusCode={}, durationMs={}", url, statusCode, durationMs);
            return result;
        } catch (IOException | InterruptedException e) {
            log.error("网页抓取失败: url={}", url, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return failureOutput("网页抓取失败: " + e.getMessage());
        }
    }
}
