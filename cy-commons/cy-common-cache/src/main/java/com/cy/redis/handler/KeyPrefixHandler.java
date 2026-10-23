package com.cy.redis.handler;

import org.apache.commons.lang3.StringUtils;
import org.redisson.api.NameMapper;

import java.util.Collection;
import java.util.List;

/**
 * Redis Key 前缀处理器
 * <p>
 * 实现 Redisson 的 {@link NameMapper} 接口，为 Redis Key 映射命名空间。
 * <p>
 * 作用：
 * <ul>
 *     <li>默认 key 自动叠加服务私有前缀，隔离多应用私有缓存</li>
 *     <li>命中共享前缀时保持原始 key，不叠加服务私有前缀，支持跨服务共享数据</li>
 *     <li>便于按前缀批量管理缓存</li>
 * </ul>
 * <p>
 * 示例：前缀为 "app1" 时
 * <ul>
 *     <li>{@code map("user:1")} -> "app1:user:1"</li>
 *     <li>共享前缀包含 {@code auth:token:} 时，{@code map("auth:token:1")} -> "auth:token:1"</li>
 *     <li>{@code unmap("app1:user:1")} -> "user:1"</li>
 * </ul>
 *
 * @author pcy
 */
public class KeyPrefixHandler implements NameMapper {

    private final String keyPrefix;
    private final List<String> sharedKeyPrefixes;

    public KeyPrefixHandler(String keyPrefix) {
        this(keyPrefix, List.of());
    }

    public KeyPrefixHandler(String keyPrefix, Collection<String> sharedKeyPrefixes) {
        //前缀为空 则返回空前缀
        String normalizedKeyPrefix = StringUtils.trimToEmpty(keyPrefix);
        this.keyPrefix = StringUtils.isBlank(normalizedKeyPrefix) ? "" : normalizePrefix(normalizedKeyPrefix);
        this.sharedKeyPrefixes = sharedKeyPrefixes == null ? List.of() : sharedKeyPrefixes.stream()
            .map(StringUtils::trimToEmpty)
            .filter(StringUtils::isNotBlank)
            .distinct()
            .toList();
    }

    /**
     * 增加服务私有前缀，或让共享 key 保持原始命名空间。
     */
    @Override
    public String map(String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }
        if (isSharedKey(name)) {
            return name;
        }
        if (StringUtils.isNotBlank(keyPrefix) && !name.startsWith(keyPrefix)) {
            return keyPrefix + name;
        }
        return name;
    }

    /**
     * 去除服务私有前缀。
     */
    @Override
    public String unmap(String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }
        if (StringUtils.isNotBlank(keyPrefix) && name.startsWith(keyPrefix)) {
            return name.substring(keyPrefix.length());
        }
        return name;
    }

    private static String normalizePrefix(String prefix) {
        return prefix.endsWith(":") ? prefix : prefix + ":";
    }

    private boolean isSharedKey(String name) {
        return sharedKeyPrefixes.stream().anyMatch(name::startsWith);
    }

}
