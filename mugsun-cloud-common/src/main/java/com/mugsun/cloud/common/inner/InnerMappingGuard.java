package com.mugsun.cloud.common.inner;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import java.util.Map;
import java.util.TreeSet;

/**
 * 启动时检查：不允许有 Controller 挂在 {@code /inner} 下，有就拒绝启动。
 *
 * <p>{@code /inner} 整个归 {@link InnerRequestFilter} 管，请求到不了 Controller，写在这下面的 Controller 永远调不到。
 * 更要紧的是反过来：那个过滤器对带编码、双斜杠、; 参数的路径不做拦截，靠的就是后面没有任何 Controller 能接住它们。
 * 内部接口请实现 {@link InnerEndpoint}。
 */
@Component
class InnerMappingGuard implements SmartInitializingSingleton {

	private final ObjectProvider<RequestMappingInfoHandlerMapping> handlerMappings;

	InnerMappingGuard(ObjectProvider<RequestMappingInfoHandlerMapping> handlerMappings) {
		this.handlerMappings = handlerMappings;
	}

	@Override
	public void afterSingletonsInstantiated() {
		TreeSet<String> offending = new TreeSet<>();
		handlerMappings.forEach(mapping -> collect(mapping.getHandlerMethods(), offending));
		if (!offending.isEmpty()) {
			throw new IllegalStateException("/inner 下不能有 Controller 接口，内部接口请实现 InnerEndpoint。违反的有："
				+ String.join("；", offending));
		}
	}

	static void collect(Map<RequestMappingInfo, HandlerMethod> handlerMethods, TreeSet<String> offending) {
		handlerMethods.forEach((info, method) -> {
			for (String pattern : info.getPatternValues()) {
				if (underInner(pattern)) {
					offending.add(pattern + " → " + method.getBeanType().getName() + "#" + method.getMethod().getName());
				}
			}
		});
	}

	/**
	 * 这个路径模式是不是落在 /inner 下。{@code /inner}、{@code /inner/x}、{@code /inner*}、{@code /inner{x}} 都算；
	 * {@code /innerx}、{@code /inner-x} 是另一个路径段，不算。
	 */
	static boolean underInner(String pattern) {
		String p = pattern.startsWith("/") ? pattern : "/" + pattern;
		if (!p.startsWith(InnerRequestFilter.PREFIX)) {
			return false;
		}
		if (p.length() == InnerRequestFilter.PREFIX.length()) {
			return true;
		}
		char next = p.charAt(InnerRequestFilter.PREFIX.length());
		return !(Character.isLetterOrDigit(next) || next == '-' || next == '_');
	}
}
