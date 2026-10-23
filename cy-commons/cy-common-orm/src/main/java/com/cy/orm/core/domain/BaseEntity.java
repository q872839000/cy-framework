package com.cy.orm.core.domain;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.cy.core.serializer.LongToTimestampDeserializer;
import com.cy.core.serializer.TimestampToLongSerializer;
import com.mybatisflex.annotation.Column;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.sql.Timestamp;

/**
 * Entity基类
 *
 * @author pcy
 */

@Data
public class BaseEntity implements Serializable {


    /**
     * 创建时间
     */
    @Schema(description = "创建时间", type = "string")
    @JsonDeserialize(using = LongToTimestampDeserializer.class)
    @JsonSerialize(using = TimestampToLongSerializer.class)
    protected Timestamp createTime;

    /**
     * 修改时间
     */
    @Schema(description = "最后更新时间", type = "string")
    @JsonDeserialize(using = LongToTimestampDeserializer.class)
    @JsonSerialize(using = TimestampToLongSerializer.class)
    protected Timestamp updateTime;

    /**
     * 创建用户ID
     */
    @Schema(description = "创建者ID")
    @JsonSerialize(using = ToStringSerializer.class)
    protected Long createUser;

    /**
     * 修改用户ID
     */
    @Schema(description = "最后更新者ID")
    @JsonSerialize(using = ToStringSerializer.class)
    protected Long updateUser;

    @Schema(description = "创建者名称")
    @Column(ignore = true)
    protected String createName;

    @Schema(description = "最后更新者名称")
    @Column(ignore = true)
    protected String updateName;

}
