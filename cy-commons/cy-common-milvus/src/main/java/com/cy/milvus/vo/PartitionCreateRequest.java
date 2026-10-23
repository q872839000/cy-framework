package com.cy.milvus.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * @Auther: pcy
 * @Date: 2025/9/17 11:54
 * @Description: 分区相关的参数封装（Value Object）
 * 用于创建或删除分区的简洁参数对象。
 * <p>示例：</p>
 * PartitionCreateVO vo = PartitionCreateVO.builder()
 *     .collectionName("my_collection")
 *     .partitionName("p1")
 *     .build();
 */
@Data
@Builder
public class PartitionCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 集合名（必填） */
    private String collectionName;

    /** 分区名（必填） */
    private String partitionName;

    /** 简单校验 */
    public void validate() {
        if (this.collectionName == null || this.collectionName.isEmpty()) {
            throw new IllegalArgumentException("collectionName 不能为空");
        }
        if (this.partitionName == null || this.partitionName.isEmpty()) {
            throw new IllegalArgumentException("partitionName 不能为空");
        }
    }
}
