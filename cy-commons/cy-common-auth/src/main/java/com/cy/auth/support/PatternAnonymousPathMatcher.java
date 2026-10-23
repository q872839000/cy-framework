package com.cy.auth.support;

import org.springframework.util.AntPathMatcher;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

public class PatternAnonymousPathMatcher implements AnonymousPathMatcher {

    private final Supplier<Collection<String>> patternSupplier;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private volatile Set<String> patterns;

    public PatternAnonymousPathMatcher(Supplier<Collection<String>> patternSupplier) {
        this.patternSupplier = patternSupplier;
    }

    @Override
    public boolean matches(String requestUri) {
        for (String pattern : getPatterns()) {
            if (pathMatcher.match(pattern, requestUri)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> getPatterns() {
        Set<String> localPatterns = patterns;
        if (localPatterns != null) {
            return localPatterns;
        }
        synchronized (this) {
            if (patterns == null) {
                patterns = new LinkedHashSet<>(patternSupplier.get());
            }
            return patterns;
        }
    }
}
