package com.cy.web.context;

import com.cy.core.context.InvocationContext;
import com.cy.core.context.InvocationScope;
import com.cy.core.utils.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;

public final class ServletInvocationContextAccessor {

    private ServletInvocationContextAccessor() {
    }

    public static InvocationContext current(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object attribute = request.getAttribute(InvocationContext.REQUEST_ATTRIBUTE);
        return attribute instanceof InvocationContext context ? context : null;
    }

    public static InvocationContext getOrCreate(HttpServletRequest request) {
        InvocationContext context = current(request);
        if (context != null) {
            return context;
        }
        String incomingSpanId = request.getHeader(InvocationContext.SPAN_HEADER);
        String parentSpanId = (incomingSpanId != null && !incomingSpanId.isBlank()) ? incomingSpanId : null;
        String clientIp = ClientIpResolver.resolve(request::getHeader, request.getRemoteAddr());
        InvocationContext created = InvocationContext.anonymous(
                InvocationContext.resolveId(request.getHeader(InvocationContext.TRACE_HEADER)),
                InvocationContext.generateId(),
                parentSpanId,
                InvocationContext.SOURCE_EXTERNAL,
                clientIp,
                request.getMethod(),
                request.getRequestURI()
        );
        replace(request, created);
        return created;
    }

    public static void bind(HttpServletRequest request, InvocationContext context) {
        if (request != null && context != null) {
            request.setAttribute(InvocationContext.REQUEST_ATTRIBUTE, context);
        }
    }

    public static void replace(HttpServletRequest request, InvocationContext context) {
        bind(request, context);
        if (context != null) {
            InvocationScope.replace(context);
        }
    }
}
