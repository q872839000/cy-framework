package com.cy.milvus.exception;

import com.cy.core.exception.CYException;
import io.milvus.param.R;


/**
 * @Auther: pcy
 * @Date: 2025/9/29 15:00
 * @Description: milvus 运行时异常检查工具类
 *  由于SDK中捕获了异常，外层方法无法通过异常捕获的方式判断请求是否错误，所以提供此工具类
 */
public class MilvusRCheckUtil {
    public static void check(R<?> res) {
        if (res == null || res.getStatus() == null || res.getStatus() != R.Status.Success.getCode()) {
            throw new CYException(31040);
        }
    }
}
