package com.cy.core.base;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 分页查询条件基类
 *
 * @author pcy
 */
@Data
@Schema(description = "分页查询条件")
public class Condition implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 每页条数上限 */
    public static final int MAX_PAGE_SIZE = 200;

    @Schema(description = "是否分页", defaultValue = "true")
    private boolean pageable = true;

    @Schema(description = "页号", defaultValue = "1")
    private int page = 1;

    @Schema(description = "每页条数", defaultValue = "10")
    private int size = 10;

    public void setPage(int page) {
        this.page = Math.max(1, page);
    }

    public void setSize(int size) {
        this.size = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    }
}
