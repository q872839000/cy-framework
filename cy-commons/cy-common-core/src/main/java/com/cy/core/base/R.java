package com.cy.core.base;

import java.io.Serial;
import java.io.Serializable;

/**
 * Description：全局接口返回结果集
 * @author pcy
 * @date 2025年3月4日
 * @param <T> 泛型数据类型
 */
public class R<T> implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;
	
	/**
	 * 默认成功状态码
	 */
	public static final Integer DEFAULT_SUCCESS = 1000; 
	
	/**
	 * 默认服务器错误状态码
	 */
	public static final Integer DEFAULT_SERVER_ERROR = 500;
			
	/**
	 * 默认成功消息
	 */
	public static final String DEFAULT_SUCCESS_MSG = "操作成功";
	
	/**
	 * 默认错误消息
	 */
	public static final String DEFAULT_ERROR_MSG = "操作失败";

	/**
	 * 响应代码
	 */
	private Integer status = DEFAULT_SUCCESS;
	/**
	 * 提示信息
	 */
	private String message = DEFAULT_SUCCESS_MSG;
	/**
	 * 数据结果
	 */
	private T data;
	/**
	 * 时间戳
	 */
	private long timestamp;

	private R() {
		this.timestamp = System.currentTimeMillis();
	}

	private R(T data) {
		this();
		this.data = data;
	}

	private R(Integer status, String message) {
		this();
		this.status = status;
		this.message = message;
	}

	/**
	 * 成功返回结果
	 * @param <T> 泛型数据类型
	 * @return R<T> 返回结果对象
	 */
	public static <T> R<T> ok() {
		return new R<>();
	}

	/**
	 * 成功返回结果并携带数据
	 * @param data 参数
	 * @param <T> 泛型数据类型
	 * @return R<T> 返回结果对象
	 */
	public static <T> R<T> ok(T data) {
		return new R<>(data);
	}

	/**
	 * 错误返回结果
	 * @param <T> 泛型数据类型
	 * @return R<T> 返回结果对象
	 */
	public static <T> R<T> error() {
		return new R<>(DEFAULT_SERVER_ERROR, DEFAULT_ERROR_MSG);
	}

	/**
	 * 错误返回结果并指定状态和信息
	 * @param status 失败状态
	 * @param message 信息
	 * @param <T> 泛型数据类型
	 * @return R<T> 返回结果对象
	 */
	public static <T> R<T> error(Integer status, String message) {
		return new R<>(status, message);
	}

	/**
	 * 判断是否成功
	 * @return boolean 是否成功
	 */
	public boolean success() {
		return DEFAULT_SUCCESS.equals(this.status);
	}

	/**
	 * 获取响应状态码
	 * @return Integer 响应状态码
	 */
	public Integer getStatus() {
		return status;
	}

	/**
	 * 设置响应状态码
	 * @param status 响应状态码
	 */
	public void setStatus(Integer status) {
		this.status = status;
	}

	/**
	 * 获取提示信息
	 * @return String 提示信息
	 */
	public String getMessage() {
		return message;
	}

	/**
	 * 设置提示信息
	 * @param message 提示信息
	 */
	public void setMessage(String message) {
		this.message = message;
	}

	/**
	 * 获取数据结果
	 * @return T 数据结果
	 */
	public T getData() {
		return data;
	}

	/**
	 * 设置数据结果
	 * @param data 数据结果
	 */
	public void setData(T data) {
		this.data = data;
	}

	/**
	 * 获取时间戳
	 * @return long 时间戳
	 */
	public long getTimestamp() {
		return timestamp;
	}

	/**
	 * 设置时间戳
	 * @param timestamp 时间戳
	 */
	public void setTimestamp(long timestamp) {
		this.timestamp = timestamp;
	}

	
}
