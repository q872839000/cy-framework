package com.cy.conversation.session.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.cy.orm.core.domain.BaseEntity;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 会话实体类
 * <p>
 * 表示一个对话会话，包含会话标题、状态、命名状态等信息。
 * 一个会话可以包含多条聊天记录。
 * </p>
 *
 * @author pcy
 * @date 2026/1/26
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Table(value = "conversation_session")
@Schema(description = "会话实体")
public class ConversationSession extends BaseEntity {

    /**
     * 主键ID（雪花算法生成）
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    @Schema(description = "会话ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 会话标题
     * <p>
     * 长度限制：2-50个字符
     * 可通过智能命名自动生成，或手动修改
     * </p>
     */
    @Schema(description = "会话标题")
    private String title;

    /**
     * 会话状态
     * <p>
     * 0-已归档，1-正常
     * </p>
     */
    @Schema(description = "会话状态：0-已归档，1-正常")
    private Integer status;

    /**
     * 命名状态
     * <p>
     * 0-待命名，1-命名中，2-已命名，3-命名失败
     * </p>
     */
    @Schema(description = "命名状态：0-待命名，1-命名中，2-已命名，3-命名失败")
    private Integer namingStatus;

    /**
     * 消息数量（冗余字段，用于快速统计）
     */
    @Schema(description = "消息数量")
    private Integer messageCount;

    /**
     * 删除标志
     * <p>
     * 0-未删除，1-已删除
     * </p>
     */
    @Schema(description = "删除标志：0-未删除，1-已删除")
    @Column(isLogicDelete = true)
    private Integer deleted;

    /**
     * 会话级 LLM 提供商偏好（cy.ai.providers 的 key；null = 跟随平台默认）
     * <p>
     * 宿主服务（管家/知识库等）在运行 LLM 前读取并生效；模型选择存于会话，
     * 换模型对话无需新建会话。
     * </p>
     */
    @Schema(description = "会话级 LLM 提供商偏好")
    private String llmProvider;

    /**
     * 会话级 LLM 模型覆盖（null = 提供商默认模型）
     */
    @Schema(description = "会话级 LLM 模型覆盖")
    private String llmModel;

    /**
     * 会话级思考偏好（off / on / low / medium / high；null = 跟随 provider 全局配置）
     * <p>
     * 与模型偏好同通道钉到会话：宿主服务在运行 LLM 前读取并翻译为请求级
     * {@code thinkingLevel}，仅对声明了思考控制协议的 provider 生效。
     * </p>
     */
    @Schema(description = "会话级思考偏好")
    private String thinkingPref;

    // ==================== 命名状态常量 ====================

    /**
     * 命名状态：待命名
     */
    public static final int NAMING_STATUS_PENDING = 0;

    /**
     * 命名状态：命名中
     */
    public static final int NAMING_STATUS_PROCESSING = 1;

    /**
     * 命名状态：已命名
     */
    public static final int NAMING_STATUS_COMPLETED = 2;

    /**
     * 命名状态：命名失败
     */
    public static final int NAMING_STATUS_FAILED = 3;

    // ==================== 会话状态常量 ====================

    /**
     * 会话状态：已归档
     */
    public static final int STATUS_ARCHIVED = 0;

    /**
     * 会话状态：正常
     */
    public static final int STATUS_NORMAL = 1;
}
