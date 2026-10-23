// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.embedding;

import com.cy.ai.AiFacade;
import com.cy.ai.model.ChatCompletionRequest;
import com.cy.ai.model.ChatCompletionResponse;
import com.cy.ai.model.ChatMessage;
import com.cy.ai.model.ResponseFormats;
import com.cy.orrery.memory.api.ChatPort;
import java.util.ArrayList;
import java.util.List;

/**
 * AiFacade 对话端口桥接（复用 cy-common-ai 的 simpleChat）。
 *
 * @author pcy
 */
public class AiFacadeChatPort implements ChatPort {

    /** AI 门面。 */
    private final AiFacade aiFacade;

    /**
     * 构造对话端口桥接。
     *
     * @param aiFacade AI 门面
     */
    public AiFacadeChatPort(AiFacade aiFacade) {
        this.aiFacade = aiFacade;
    }

    @Override
    public String chat(String systemPrompt, String userMessage) {
        return aiFacade.simpleChat(systemPrompt, userMessage);
    }

    @Override
    public String chatJson(String systemPrompt, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        messages.add(ChatMessage.user(userMessage));
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .messages(messages)
                .responseFormat(ResponseFormats.jsonObject())
                .build();
        ChatCompletionResponse response = aiFacade.chatSync(request);
        return response.getChoices().get(0).getMessage().getContent();
    }

    @Override
    public boolean available() {
        return aiFacade != null;
    }
}
