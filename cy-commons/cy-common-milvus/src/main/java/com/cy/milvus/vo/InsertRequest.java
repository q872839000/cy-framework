package com.cy.milvus.vo;

import com.google.gson.JsonObject;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * @Auther: pcy
 * @Date: 2025/9/17 11:54
 * @Description: 插入数据请求（v2 API）
 */
@Data
@Builder
public class InsertRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 集合名称 */
    private String collectionName;

    /** 分区名称（可选） */
    private String partitionName;

    /** 数据行（JsonObject 格式） */
    private List<JsonObject> rows;
}
