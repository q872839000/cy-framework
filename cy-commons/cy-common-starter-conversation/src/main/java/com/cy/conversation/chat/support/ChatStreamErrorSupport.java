package com.cy.conversation.chat.support;

import com.cy.conversation.chat.vo.ChatStreamResponse;
import com.cy.core.base.R;
import com.cy.core.exception.CYException;
import com.cy.web.exception.ExceptionMapperSupport;
import org.springframework.stereotype.Component;

@Component
public class ChatStreamErrorSupport {

    private static final int DEFAULT_STREAM_ERROR_CODE = 30101;

    private final ExceptionMapperSupport mapper;

    public ChatStreamErrorSupport(ExceptionMapperSupport mapper) {
        this.mapper = mapper;
    }

    public ChatStreamResponse toStreamError(Throwable throwable, String uri) {
        R<Void> response = resolveResponse(throwable, uri);
        return ChatStreamResponse.error(response);
    }

    private R<Void> resolveResponse(Throwable throwable, String uri) {
        if (throwable instanceof CYException e) {
            return mapper.mapCYException(e, uri);
        }
        if (throwable instanceof IllegalArgumentException e) {
            return mapper.mapIllegalArgument(e, uri);
        }
        if (throwable instanceof NullPointerException e) {
            return mapper.mapNullPointer(e, uri);
        }
        return mapper.buildErrorResponse(DEFAULT_STREAM_ERROR_CODE);
    }
}
