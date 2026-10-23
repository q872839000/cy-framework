package com.cy.auth.util;

import com.cy.core.utils.ClientIpResolver;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.Optional;

/**
 *
 *
 * Description：HttpServletRequest的工具类
 *
 * @author 牧风
 * @date 2024年5月8日
 */
public class RequestUtils {
	public static final Logger LOGGER = LoggerFactory.getLogger(RequestUtils.class);

	/**
	 * Desc：获取Request对象
	 *
	 * @return
	 */
	public static HttpServletRequest getRequest() {
		RequestAttributes reqAttr = RequestContextHolder.getRequestAttributes();
		Assert.notNull(reqAttr, "Can not get request!!!");
		return ((ServletRequestAttributes) reqAttr).getRequest();
	}

	/**
	 * Desc：获取Request对象，不校验是否为空
	 *
	 * @return
	 */
	public static HttpServletRequest getRequestWithoutCheck() {
		RequestAttributes reqAttr = RequestContextHolder.getRequestAttributes();
		if (reqAttr instanceof ServletRequestAttributes) {
			return ((ServletRequestAttributes) reqAttr).getRequest();
		}

		return null;
	}

	/**
	 * Desc：获取Response对象
	 *
	 * @return
	 */
	public static HttpServletResponse getResponse() {
		RequestAttributes reqAttr = RequestContextHolder.getRequestAttributes();
		Assert.notNull(reqAttr, "Can not get response!!!");
		return ((ServletRequestAttributes) reqAttr).getResponse();
	}

	/**
	 * Desc：从当前请求头获取值
	 *
	 * @param name 参数名称
	 * @return
	 */
	public static String getHeader(String name) {
		return getRequest().getHeader(name);
	}

	/**
	 * Desc：从当前请求参数中获取
	 *
	 * @param name 参数名称
	 * @return
	 */
	public static String getRequestParam(String name) {
		return getRequest().getParameter(name);
	}

	/**
	 * Desc：从当前请求获取值
	 *
	 * @param name 参数名称
	 * @return
	 */
	public static String getRequestAttr(String name) {
		Optional<Object> attribute = Optional.ofNullable(getRequest().getAttribute(name));
		if (attribute.isPresent()) {
			return attribute.get().toString();
		}
		return null;
	}

	/**
	 * Desc： 是否有Request
	 *
	 * @return
	 */
	public static boolean isRequest() {
		return Optional.ofNullable(RequestContextHolder.getRequestAttributes()).isPresent();
	}

	/**
	 * Desc：子线程共用主线程request
	 */
	public static void setThreadRequest() {
		RequestContextHolder.setRequestAttributes(RequestContextHolder.getRequestAttributes(), true);
	}

	/**
	 * Desc：判断是否为RPC调用
	 *
	 * @param request
	 * @return
	 */
	public static boolean isRpcCall(HttpServletRequest request) {
		if (request == null)
			return false;
		// 如果是hessian请求则认为是rpc调用
		if (isHessianRequest(request))
			return true;
		// 如果是soap的web service请求则认为是rpc调用
		return isSoapRequest(request);
	}

	/**
	 * Desc：判断是否为soap调用
	 *
	 * @param request
	 */
	private static boolean isSoapRequest(HttpServletRequest request) {
		if (request == null)
			return false;
		String contentType = request.getContentType();
		if (contentType == null)
			return false;
		// sopa1.2协议中，ContentType为application/soap+xml
		if (contentType.contains("soap"))
			return true;
		// sopa协议中有一个请求头SOAPAction的项，该项可能为空串 因此只判断是否为null
		return request.getHeader("SOAPAction") != null;
	}

	/**
	 * Desc：判断是否为hessian调用
	 *
	 * @param request
	 */
	private static boolean isHessianRequest(HttpServletRequest request) {
		if (request == null)
			return false;
		String contentType = request.getContentType();
		if (contentType == null)
			return false;
		// Hessian协议的ContentType为x-application/hessian
		return contentType.contains("hessian");
	}

	/**
	 * Desc：获取本次请求 IP
	 *
	 * @return 当前请求的客户端 IP；无法解析时返回 {@link ClientIpResolver#UNKNOWN}
	 */
	public static String getRequestIp() {
		try {
			return getRequestIp(getRequest());
		} catch (Exception e) {
			return ClientIpResolver.UNKNOWN;
		}
	}

	/**
	 * Desc：获取 HTTP 请求来源地址
	 *
	 * @param request HTTP 请求
	 * @return 客户端 IP；当 {@code request} 为空或无法识别时返回 {@link ClientIpResolver#UNKNOWN}
	 */
	public static String getRequestIp(HttpServletRequest request) {
		if (request == null) {
			return ClientIpResolver.UNKNOWN;
		}
		return ClientIpResolver.resolve(request::getHeader, request.getRemoteAddr());
	}

	/**
	 * Desc：获取原始的http请求的内容，主要用于获取web接口中请求内容
	 *
	 * @param request
	 * @return
	 */
	public static String getRequestString(HttpServletRequest request) {
		if (request == null)
			return null;

		// 如果是rpc调用，则不获取请求内容，rpc调用请求的内容是特定格式
		if (isRpcCall(request))
			return null;

		// 如果是文件上传，则不获取请求内容
		if (isMultipart(request)) {
			return null;
		}

		// 是GET方法则从query string中获取
		String method = request.getMethod();
		if (method == null)
			method = "";

		if (method.equalsIgnoreCase("GET"))
			return request.getQueryString();

		// 如果是post方法则从请求的body中获取,但需要区分文件上传的情况
		if (method.equalsIgnoreCase("POST")) {
			try {
				ServletInputStream inputStream = request.getInputStream();
				int length = request.getContentLength();
				if (length <= 0)
					return null;

				byte[] bytes = new byte[length];
				int readSize = inputStream.read(bytes);
				if (readSize > 0)
					return new String(bytes, 0, readSize);
				else
					return "";
			} catch (Throwable t) {
				LOGGER.error("get post data body from request input stream fail", t);
			}
		}

		return null;
	}

	/**
	 * Desc：判断是否为文件上传
	 *
	 * @param request
	 * @return
	 */
	public static boolean isMultipart(HttpServletRequest request) {
		if (!isHttpPost(request)) {
			return false;
		}
		String contentType = request.getContentType();
		return (contentType != null && contentType.toLowerCase().startsWith("multipart/"));
	}

	/**
	 * Desc：判断是否为POST请求
	 *
	 * @param request
	 * @return
	 */
	public static boolean isHttpPost(HttpServletRequest request) {
		if (request == null)
			return false;
		String method = request.getMethod();
		return "post".equalsIgnoreCase(method);
	}

	/**
	 * Desc：检查http请求是否是请求的传入的二进制数据，对于octet-stream，image，multipart文件 都认为是二进制的
	 *
	 * @param request
	 * @return
	 */
	public static boolean isBinaryBodyData(HttpServletRequest request) {
		String contentType = request.getContentType();
		if (contentType == null)
			return false;
		contentType = contentType.toLowerCase();
		// 判断Content-Type是否指定为流数据
		if (contentType.contains("stream"))
			return true;
		// 判断Content-Type是否指定为文件上传
		if (contentType.contains("multipart"))
			return true;
		// 判断Content-Type是否指定为图片
		return contentType.contains("image");
	}

	/**
	 * Desc：获取http请求中的请求内容
	 *
	 * @param request
	 * @return
	 */
	public static String getParameterMapString(HttpServletRequest request) {
		if (request == null)
			return "";
		Map<String, String[]> map = request.getParameterMap();
		if (map == null || map.size() <= 0)
			return "";
		StringBuilder sb = new StringBuilder(100);
		boolean bfirst = true;// 是否首次拼接
		for (Map.Entry<String, String[]> entry : map.entrySet()) {
			for (String item : entry.getValue()) {
				if (!bfirst) {
					sb.append("&");
				} else {
					bfirst = false;
				}
				sb.append(entry.getKey());
				sb.append("=");
				sb.append(item);
			}
		}
		return sb.toString();
	}

	/**
	 * Desc：获取请求url的应用映射部分url 例如 demo-test-war/test/index.html,获得结果为/test/index.html
	 *
	 * @param request
	 * @return
	 */
	public static String getReqAppPath(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String contextPath = request.getContextPath();
		return getRemainingPath(uri, contextPath, true);
	}

	/**
	 * Desc：去除context path
	 *
	 * @param requestUri 请求地址
	 * @param mapping 匹配内容
	 * @param ignoreCase 是否忽略大小写
	 * @return
	 */
	protected static String getRemainingPath(String requestUri, String mapping, boolean ignoreCase) {
		int index1 = 0;
		int index2 = 0;
		for (; (index1 < requestUri.length()) && (index2 < mapping.length()); index1++, index2++) {
			char c1 = requestUri.charAt(index1);
			char c2 = mapping.charAt(index2);
			if (c1 == ';') {
				index1 = requestUri.indexOf('/', index1);
				if (index1 == -1) {
					return null;
				}
				c1 = requestUri.charAt(index1);
			}
			if (c1 == c2) {
				continue;
			} else if (ignoreCase && (Character.toLowerCase(c1) == Character.toLowerCase(c2))) {
				continue;
			}
			return null;
		}
		if (index2 != mapping.length()) {
			return null;
		} else if (index1 == requestUri.length()) {
			return "";
		} else if (requestUri.charAt(index1) == ';') {
			index1 = requestUri.indexOf('/', index1);
		}
		return (index1 != -1 ? requestUri.substring(index1) : "");
	}

	public static Optional<ServletInputStream> getRequestBodyAsStream(HttpServletRequest request) {
		try {
			return Optional.ofNullable(request.getInputStream());
		} catch (Exception e) {
			LOGGER.error("获取请求体输入流失败", e);
			return Optional.empty();
		}
	}
}
