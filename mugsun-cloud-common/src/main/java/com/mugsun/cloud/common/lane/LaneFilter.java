package com.mugsun.cloud.common.lane;

import com.mugsun.cloud.common.inner.InnerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 把网关写在请求头 {@link Lanes#HEADER} 里的灰度标记记成当前请求的标记，处理完清掉。
 * 处理这个请求时再去调别的服务，{@code InnerClient} 按这个标记找对方的实例。
 *
 * <p>排在 {@link InnerRequestFilter} 后面：内部接口的请求在那里就处理完了，到不了这里；内部调用带来的标记由它在验签通过以后读。
 *
 * <p>这里不查请求头是谁写的。服务的端口只该让网关和别的服务连得到，客户端自己带的这个头在网关就被去掉了；
 * 就算有人绕过网关直接连上来并带了它，改变的也只是这次请求再去调别的服务时找哪一组实例，能不能调、调了能看到什么不受它影响。
 */
@Component
@Order(LaneFilter.ORDER)
public class LaneFilter extends OncePerRequestFilter {

	public static final int ORDER = InnerRequestFilter.ORDER + 1;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		LaneContext.set(request.getHeader(Lanes.HEADER));
		try {
			chain.doFilter(request, response);
		} finally {
			LaneContext.clear();
		}
	}
}
