package com.mugsun.cloud.config;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 和 Nacos 打交道的那一段：什么时候读、读不到怎么办、启动时读到的和监听送来的谁为准、停的时候收不收尾、几份配置之间会不会互相影响。
 * Nacos 的客户端和接收内容的一方都用假的，这里只看两边怎么衔接。
 */
class NacosConfigSourceTest {

	private static final String DATA_ID = "mugsun-gateway-routes.yaml";
	private static final String OTHER_DATA_ID = "mugsun-gateway-lanes.yaml";
	private static final String GROUP = "DEFAULT_GROUP";

	private final ConfigService nacos = mock(ConfigService.class);
	private final Consumer<String> sink = sink();
	private final Consumer<String> otherSink = sink();
	private final NacosConfigSource source = new NacosConfigSource(() -> nacos, List.of(watch(DATA_ID, sink)));

	@Test
	void readsOnceAtStartAndThenFollowsEveryChange() throws Exception {
		when(nacos.getConfig(DATA_ID, GROUP, 5000)).thenReturn("first");

		source.start();

		InOrder order = inOrder(nacos, sink);
		order.verify(nacos).addListener(eq(DATA_ID), eq(GROUP), any(Listener.class));
		order.verify(nacos).getConfig(DATA_ID, GROUP, 5000);
		order.verify(sink).accept("first");
		assertThat(source.isRunning()).isTrue();

		listener(DATA_ID).receiveConfigInfo("second");
		verify(sink).accept("second");
	}

	/** 这份配置还没建，或者被整份删掉：空内容照样交下去，空表示不要这份配置里的东西了 */
	@Test
	void missingOrDeletedConfigIsPassedOnAsEmpty() throws Exception {
		when(nacos.getConfig(DATA_ID, GROUP, 5000)).thenReturn(null);

		source.start();
		verify(sink).accept(null);

		listener(DATA_ID).receiveConfigInfo("routes: []");
		listener(DATA_ID).receiveConfigInfo("");
		verify(sink).accept("");
	}

	@Test
	void configThatCannotBeReadDoesNotStopTheGateway() throws Exception {
		when(nacos.getConfig(any(), any(), anyLong())).thenThrow(new NacosException(NacosException.NO_RIGHT, "no right"));

		assertThatCode(source::start).doesNotThrowAnyException();
		verify(sink, never()).accept(any());

		// 监听还挂着：权限补上以后，内容一送到就用上
		listener(DATA_ID).receiveConfigInfo("later");
		verify(sink).accept("later");
	}

	@Test
	void nacosThatCannotBeReachedDoesNotStopTheGateway() {
		NacosConfigSource unreachable = new NacosConfigSource(() -> {
			throw new NacosException(NacosException.SERVER_ERROR, "connection refused");
		}, List.of(watch(DATA_ID, sink), watch(OTHER_DATA_ID, otherSink)));

		assertThatCode(unreachable::start).doesNotThrowAnyException();
		assertThatCode(unreachable::stop).doesNotThrowAnyException();
		verifyNoInteractions(sink, otherSink);
	}

	/** 启动时读的那一下还没回来，监听已经送来了改过的内容：不能再用读到的那份把它盖掉 */
	@Test
	void contentPushedWhileStartingWinsOverTheOneReadAtStart() throws Exception {
		when(nacos.getConfig(any(), any(), anyLong())).thenAnswer(invocation -> {
			listener(DATA_ID).receiveConfigInfo("pushed");
			return "read";
		});

		source.start();

		verify(sink).accept("pushed");
		verify(sink, never()).accept("read");
	}

	/** 第一次刷新路由表时 application.yml 里的路由有错，网关要启动失败；这个异常在这里不能被吞掉 */
	@Test
	void failureWhileApplyingAtStartIsNotSwallowed() throws Exception {
		when(nacos.getConfig(any(), any(), anyLong())).thenReturn("routes");
		doThrow(new IllegalStateException("built-in route is broken")).when(sink).accept("routes");

		assertThatThrownBy(source::start).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void failureWhileApplyingAPushedChangeStaysInside() throws Exception {
		source.start();
		doThrow(new IllegalStateException("unexpected")).when(sink).accept("boom");
		Listener listener = listener(DATA_ID);

		assertThatCode(() -> listener.receiveConfigInfo("boom")).doesNotThrowAnyException();
	}

	@Test
	void stopRemovesTheListenerAndClosesTheClient() throws Exception {
		source.start();
		Listener listener = listener(DATA_ID);

		source.stop();

		verify(nacos).removeListener(DATA_ID, GROUP, listener);
		verify(nacos).shutDown();
		assertThat(source.isRunning()).isFalse();

		listener.receiveConfigInfo("too late");
		verify(sink, never()).accept("too late");
	}

	@ParameterizedTest
	@CsvSource({"'', DEFAULT_GROUP", "'  ', DEFAULT_GROUP", "routes.yaml, ''"})
	void dataIdAndGroupMustBeGiven(String dataId, String group) {
		assertThatThrownBy(() -> new NacosConfigSource.Watch("动态路由", "先只用 application.yml 里的路由", dataId, group, sink))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("动态路由");
	}

	@Test
	void dataIdAndGroupAreTrimmed() {
		NacosConfigSource.Watch watch = new NacosConfigSource.Watch("动态路由", "先只用 application.yml 里的路由", " routes.yaml ",
			" DEFAULT_GROUP ", sink);

		assertThat(watch.dataId()).isEqualTo("routes.yaml");
		assertThat(watch.group()).isEqualTo("DEFAULT_GROUP");
	}

	// ---------- 几份配置 ----------

	/** 几份配置只连一次 Nacos，各读各的、各交各的；之后哪一份改了，只有它的接收方收到 */
	@Test
	void severalConfigsShareOneConnectionAndStayApart() throws Exception {
		AtomicInteger connections = new AtomicInteger();
		NacosConfigSource both = new NacosConfigSource(() -> {
			connections.incrementAndGet();
			return nacos;
		}, List.of(watch(DATA_ID, sink), watch(OTHER_DATA_ID, otherSink)));
		when(nacos.getConfig(DATA_ID, GROUP, 5000)).thenReturn("routes");
		when(nacos.getConfig(OTHER_DATA_ID, GROUP, 5000)).thenReturn("lanes");

		both.start();

		assertThat(connections).hasValue(1);
		verify(sink).accept("routes");
		verify(otherSink).accept("lanes");

		listener(OTHER_DATA_ID).receiveConfigInfo("lanes changed");
		verify(otherSink).accept("lanes changed");
		verify(sink, never()).accept("lanes changed");
	}

	@Test
	void oneUnreadableConfigDoesNotKeepTheOthersFromBeingRead() throws Exception {
		NacosConfigSource both = new NacosConfigSource(() -> nacos,
			List.of(watch(DATA_ID, sink), watch(OTHER_DATA_ID, otherSink)));
		when(nacos.getConfig(DATA_ID, GROUP, 5000)).thenThrow(new NacosException(NacosException.NO_RIGHT, "no right"));
		when(nacos.getConfig(OTHER_DATA_ID, GROUP, 5000)).thenReturn("lanes");

		assertThatCode(both::start).doesNotThrowAnyException();

		verify(sink, never()).accept(any());
		verify(otherSink).accept("lanes");
		// 读不到的那一份，监听也挂着
		listener(DATA_ID).receiveConfigInfo("routes at last");
		verify(sink).accept("routes at last");
	}

	/** 一份已经被监听送来的内容盖过，不影响另一份用启动时读到的内容 */
	@Test
	void contentPushedForOneConfigDoesNotSuppressTheReadOfAnother() throws Exception {
		NacosConfigSource both = new NacosConfigSource(() -> nacos,
			List.of(watch(DATA_ID, sink), watch(OTHER_DATA_ID, otherSink)));
		when(nacos.getConfig(DATA_ID, GROUP, 5000)).thenAnswer(invocation -> {
			listener(DATA_ID).receiveConfigInfo("routes pushed");
			return "routes read";
		});
		when(nacos.getConfig(OTHER_DATA_ID, GROUP, 5000)).thenReturn("lanes read");

		both.start();

		verify(sink).accept("routes pushed");
		verify(sink, never()).accept("routes read");
		verify(otherSink).accept("lanes read");
	}

	@Test
	void stopRemovesEveryListener() throws Exception {
		NacosConfigSource both = new NacosConfigSource(() -> nacos,
			List.of(watch(DATA_ID, sink), watch(OTHER_DATA_ID, otherSink)));
		both.start();
		Listener first = listener(DATA_ID);
		Listener second = listener(OTHER_DATA_ID);
		doThrow(new IllegalStateException("already closed")).when(nacos).removeListener(DATA_ID, GROUP, first);

		assertThatCode(both::stop).doesNotThrowAnyException();

		assertThat(first).isNotSameAs(second);
		verify(nacos).removeListener(OTHER_DATA_ID, GROUP, second);
		verify(nacos).shutDown();
	}

	/** 动态路由和灰度规则都没开：没有要读的配置，就不去连 Nacos */
	@Test
	void withNothingToReadNacosIsNeverContacted() {
		AtomicInteger connections = new AtomicInteger();
		NacosConfigSource idle = new NacosConfigSource(() -> {
			connections.incrementAndGet();
			return nacos;
		}, List.of());

		idle.start();
		assertThat(idle.isRunning()).isTrue();
		idle.stop();

		assertThat(idle.isRunning()).isFalse();
		assertThat(connections).hasValue(0);
		verifyNoInteractions(nacos);
	}

	@SuppressWarnings("unchecked")
	private static Consumer<String> sink() {
		return mock(Consumer.class);
	}

	private static NacosConfigSource.Watch watch(String dataId, Consumer<String> sink) {
		return new NacosConfigSource.Watch("测试配置", "先按没有它运行", dataId, GROUP, sink);
	}

	private Listener listener(String dataId) throws NacosException {
		ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
		verify(nacos, atLeastOnce()).addListener(eq(dataId), eq(GROUP), captor.capture());
		return captor.getValue();
	}
}
