package com.cy.conversation.message.entity;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.cy.core.serializer.LongToTimestampDeserializer;
import com.cy.core.serializer.TimestampToLongSerializer;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.sql.Timestamp;

/**
 * 聊天消息实体类
 * <p>
 * 表示一条聊天记录，包含消息角色（用户/助手/系统）、消息内容、Token消耗等信息。
 * 每条消息都属于一个会话。
 * </p>
 *
 * @author pcy
 * @date 2026/1/26
 */
@Data
@Table(value = "conversation_message")
@Schema(description = "聊天消息实体")
public class ChatMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID（雪花算法生成）
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    @Schema(description = "消息ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 所属会话ID
     */
    @Schema(description = "会话ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;

    /**
     * 消息角色
     * <p>
     * user - 用户消息
     * assistant - AI助手回复
     * system - 系统提示
     * </p>
     */
    @Schema(description = "消息角色：user/assistant/system")
    private String role;

    /**
     * 消息内容
     */
    @Schema(description = "消息内容")
    private String content;

    /**
     * 附件列表（JSON 数组字符串，元素为图片地址：data URI 或 http(s) URL）
     * <p>
     * 仅用户消息可携带；多模态输入的图片随消息落库，供历史回看渲染。
     * </p>
     */
    @Schema(description = "附件列表（JSON 数组，图片地址）")
    private String attachments;

    /**
     * 思考内容
     * <p>
     * 仅AI回复消息在启用深度思考模式时记录推理过程内容
     * </p>
     */
    @Schema(description = "思考内容")
    private String reasoningContent;

    /**
     * 过程快照（JSON）
     * <p>
     * 仅AI回复消息携带：一次运行的执行过程聚合（推理轮次 / 工具调用 / 思考与正文块 /
     * 文件产物 / 用量元信息），供历史回看重建执行过程视图；无过程的单次应答为空。
     * </p>
     */
    @Schema(description = "过程快照（JSON：events/reasonings/textBlocks/artifacts/meta）")
    private String blocksJson;

    /**
     * Token消耗数量
     * <p>
     * 仅AI回复消息记录Token数
     * </p>
     */
    @Schema(description = "Token消耗数量")
    private Integer tokens;

    /**
     * 使用的模型名称
     */
    @Schema(description = "使用的模型名称")
    private String model;

    /**
     * 响应耗时（毫秒）
     * <p>
     * 仅AI回复消息记录响应耗时
     * </p>
     */
    @Schema(description = "响应耗时（毫秒）")
    private Long durationMs;

    /**
     * 首帧响应耗时（毫秒）
     * <p>
     * 从发起请求到收到第一个Token的耗时
     * </p>
     */
    @Schema(description = "首帧响应耗时（毫秒）")
    private Long firstTokenMs;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间", type = "string")
    @JsonDeserialize(using = LongToTimestampDeserializer.class)
    @JsonSerialize(using = TimestampToLongSerializer.class)
    private Timestamp createTime;

    /**
     * 创建用户ID
     */
    @Schema(description = "创建用户ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createUser;

    // ==================== 角色常量 ====================

    /**
     * 角色：用户
     */
    public static final String ROLE_USER = "user";

    /**
     * 角色：AI助手
     */
    public static final String ROLE_ASSISTANT = "assistant";

    /**
     * 角色：系统
     */
    public static final String ROLE_SYSTEM = "system";
}
