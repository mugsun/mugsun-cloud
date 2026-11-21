package com.mugsun.cloud.gateway.route;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 和 Nacos 打交道的那一段：什么时候读、读不到怎么办、启动时读到的和监听送来的谁为准、停的时候收不收尾。
 * Nacos 的客户端和路由的检查都用假的，这里只看两边怎么衔接。
 */
class NacosRouteSourceTest {

	private static final String DATA_ID = "mugsun-gateway-routes.yaml";
	private static final String GROUP = "DEFAULT_GROUP";

	private final ConfigService nacos = mock(ConfigService.class);
	private final DynamicRouteLoader loader = mock(DynamicRouteLoader.class);
	private final NacosRouteSource source = new NacosRouteSource(DATA_ID, GROUP, () -> nacos, loader);

	@Test
	void readsOnceAtStartAndThenFollowsEveryChange() throws Exception {
		when(nacos.getConfig(DATA_ID, GROUP, 5000)).thenReturn("first");

		source.start();

		InOrder order = inOrder(nacos, loader);
		order.verify(nacos).addListener(eq(DATA_ID), eq(GROUP), any(Listener.class));
		order.verify(nacos).getConfig(DATA_ID, GROUP, 5000);
		order.verify(loader).load("first");
		assertThat(source.isRunning()).isTrue();

		listener().receiveConfigInfo("second");
		verify(loader).load("second");
	}

	/** 这份配置还没建，或者被整份删掉：空内容照样交下去，空表示不要动态路由 */
	@Test
	void missingOrDeletedConfigIsPassedOnAsEmpty() throws Exception {
		when(nacos.getConfig(DATA_ID, GROUP, 5000)).thenReturn(null);

		source.start();
		verify(loader).load(null);

		listener().receiveConfigInfo("routes: []");
		listener().receiveConfigInfo("");
		verify(loader).load("");
	}

	@Test
	void configThatCannotBeReadDoesNotStopTheGateway() throws Exception {
		when(nacos.getConfig(any(), any(), anyLong())).thenThrow(new NacosException(NacosException.NO_RIGHT, "no right"));

		assertThatCode(source::start).doesNotThrowAnyException();
		verify(loader, never()).load(any());

		// 监听还挂着：权限补上以后，内容一送到就用上
		listener().receiveConfigInfo("later");
		verify(loader).load("later");
	}

	@Test
	void nacosThatCannotBeReachedDoesNotStopTheGateway() {
		NacosRouteSource unreachable = new NacosRouteSource(DATA_ID, GROUP, () -> {
			throw new NacosException(NacosException.SERVER_ERROR, "connection refused");
		}, loader);

		assertThatCode(unreachable::start).doesNotThrowAnyException();
		assertThatCode(unreachable::stop).doesNotThrowAnyException();
		verify(loader, never()).load(any());
	}

	/** 启动时读的那一下还没回来，监听已经送来了改过的内容：不能再用读到的那份把它盖掉 */
	@Test
	void contentPushedWhileStartingWinsOverTheOneReadAtStart() throws Exception {
		when(nacos.getConfig(any(), any(), anyLong())).thenAnswer(invocation -> {
			listener().receiveConfigInfo("pushed");
			return "read";
		});

		source.start();

		verify(loader).load("pushed");
		verify(loader, never()).load("read");
	}

	/** 第一次刷新路由表时 application.yml 里的路由有错，网关要启动失败；这个异常在这里不能被吞掉 */
	@Test
	void failureWhileApplyingAtStartIsNotSwallowed() throws Exception {
		when(nacos.getConfig(any(), any(), anyLong())).thenReturn("routes");
		when(loader.load("routes")).thenThrow(new IllegalStateException("built-in route is broken"));

		assertThatThrownBy(source::start).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void failureWhileApplyingAPushedChangeStaysInside() throws Exception {
		source.start();
		when(loader.load("boom")).thenThrow(new IllegalStateException("unexpected"));
		Listener listener = listener();

		assertThatCode(() -> listener.receiveConfigInfo("boom")).doesNotThrowAnyException();
	}

	@Test
	void stopRemovesTheListenerAndClosesTheClient() throws Exception {
		source.start();
		Listener listener = listener();

		source.stop();

		verify(nacos).removeListener(DATA_ID, GROUP, listener);
		verify(nacos).shutDown();
		assertThat(source.isRunning()).isFalse();

		listener.receiveConfigInfo("too late");
		verify(loader, never()).load("too late");
	}

	@ParameterizedTest
	@CsvSource({"'', DEFAULT_GROUP", "'  ', DEFAULT_GROUP", "routes.yaml, ''"})
	void dataIdAndGroupMustBeGiven(String dataId, String group) {
		assertThatThrownBy(() -> new NacosRouteSource(dataId, group, () -> nacos, loader))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private Listener listener() throws NacosException {
		ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
		verify(nacos, atLeastOnce()).addListener(eq(DATA_ID), eq(GROUP), captor.capture());
		return captor.getValue();
	}
}
