package com.mugsun.cloud.common.lane;

import com.mugsun.cloud.common.inner.InnerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LaneFilterTest {

	private final LaneFilter filter = new LaneFilter();

	/** 往后传的时候，当前请求的标记是什么 */
	private final List<String> seenInside = new ArrayList<>();

	private final FilterChain chain = (request, response) -> seenInside.add(String.valueOf(LaneContext.current()));

	@AfterEach
	void clear() {
		MDC.clear();
	}

	@Test
	void laneWrittenByTheGatewayIsCurrentWhileTheRequestIsHandled() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/gis/layer/list");
		request.addHeader(Lanes.HEADER, "canary");

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertThat(seenInside).containsExactly("canary");
		assertThat(LaneContext.current()).as("处理完要清掉").isNull();
	}

	@Test
	void requestWithoutTheHeaderHasNoLane() throws Exception {
		filter.doFilter(new MockHttpServletRequest("GET", "/gis/layer/list"), new MockHttpServletResponse(), chain);

		assertThat(seenInside).containsExactly("null");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "base", "Canary", "can ary", "canary,blue", "abcdefghijklmnopqrstuvwxyz0123456"})
	void unusableHeaderValueCountsAsNoLane(String value) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/gis/layer/list");
		request.addHeader(Lanes.HEADER, value);

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertThat(seenInside).containsExactly("null");
	}

	/** 线程上留着上一个请求的标记时，没带标记的请求不能沿用它 */
	@Test
	void requestWithoutTheHeaderDoesNotInheritALeftoverLane() throws Exception {
		LaneContext.set("stale");

		filter.doFilter(new MockHttpServletRequest("GET", "/gis/layer/list"), new MockHttpServletResponse(), chain);

		assertThat(seenInside).containsExactly("null");
	}

	@Test
	void laneIsClearedEvenWhenHandlingFails() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/gis/layer/list");
		request.addHeader(Lanes.HEADER, "canary");
		FilterChain failing = (req, res) -> {
			throw new ServletException("boom");
		};

		assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), failing))
			.isInstanceOf(ServletException.class);

		assertThat(LaneContext.current()).isNull();
	}

	/**
	 * 要排在 InnerRequestFilter 后面：内部接口的请求在那里处理完、不再往后传，这样没验过签的内部请求带的标记到不了这里。
	 */
	@Test
	void runsRightAfterTheInnerRequestFilter() {
		Order order = AnnotationUtils.findAnnotation(LaneFilter.class, Order.class);

		assertThat(order).isNotNull();
		assertThat(order.value()).isEqualTo(LaneFilter.ORDER).isGreaterThan(InnerRequestFilter.ORDER);
	}
}
