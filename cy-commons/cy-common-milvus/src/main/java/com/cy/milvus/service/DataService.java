package com.cy.milvus.service;

import com.cy.core.exception.CYException;
import com.cy.milvus.core.MilvusClientManager;
import com.cy.milvus.vo.InsertRequest;
import com.google.gson.JsonObject;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.InsertResp;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.*;

/**
 * @Auther: pcy
 * @Date: 2025/9/15 15:44
 * @Description: DataService —— 数据层操作（v2 API）
 */
public class DataService {

    private static final Log log = LogFactory.getLog(DataService.class);

    private final MilvusClientManager clientManager;

    public DataService(MilvusClientManager clientManager) {
        this.clientManager = clientManager;
    }

    /**
     * 插入数据（v2 API）
     *
     * @param req InsertRequest 插入请求
     * @return 是否插入成功
     */
    public Boolean insert(InsertRequest req) {
        Objects.requireNonNull(req, "InsertRequest 不能为空");

        try {
            List<JsonObject> rows = req.getRows();
            if (rows == null || rows.isEmpty()) {
                log.warn("插入数据为空");
                return false;
            }

            InsertReq.InsertReqBuilder builder = InsertReq.builder()
                    .collectionName(req.getCollectionName())
                    .data(rows);

            if (req.getPartitionName() != null && !req.getPartitionName().isEmpty()) {
                builder.partitionName(req.getPartitionName());
            }

            InsertResp resp = clientManager.getClient().insert(builder.build());
            log.info("数据插入成功: collection=" + req.getCollectionName() + ", count=" + resp.getInsertCnt());
            return true;
        } catch (Exception e) {
            log.error("数据插入失败: " + e.getMessage(), e);
            if (e instanceof CYException) throw (CYException) e;
            throw new CYException(31001);
        }
    }
}
