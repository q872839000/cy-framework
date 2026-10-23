package com.cy.redis.handler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeyPrefixHandlerTest {

    @Test
    void shouldPrefixNormalKeysAndKeepSharedKeysUnprefixed() {
        KeyPrefixHandler handler = new KeyPrefixHandler("butler", List.of("global:token:"));

        assertEquals("butler:cache:user:1", handler.map("cache:user:1"));
        assertEquals("global:token:abc", handler.map("global:token:abc"));
    }

    @Test
    void shouldUnmapServicePrefix() {
        KeyPrefixHandler handler = new KeyPrefixHandler("cy");

        assertEquals("cache:user:1", handler.unmap("cy:cache:user:1"));
    }
}
