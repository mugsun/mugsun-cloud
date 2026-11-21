package com.mugsun.cloud.common.shutdown;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.serviceregistry.AbstractAutoServiceRegistration;
import org.springframework.cloud.client.serviceregistry.Registration;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 停止时的顺序：先注销、再等、最后停注册；没登记过就什么都不做；等的时候 Spring 的线程池还能接任务。
 * 注册中心用模拟对象代替，等待不真等。
 */
@SuppressWarnings("unchecked")
class DeregisterBeforeStopTest {

	private static final Duration WAIT = Duration.ofSeconds(8);

	private final ApplicationContext context = mock(ApplicationContext.class);

	private final AbstractAutoServiceRegistration<Registration> auto = mock(AbstractAutoServiceRegistration.class);

	private final ServiceRegistry<Registration> registry = mock(ServiceRegistry.class);

	private final Registration self = mock(Registration.class);

	/** 按发生的先后记下：注销、等待、停注册 */
	private final List<String> steps = new ArrayList<>();

	private DeregisterBeforeStop listener(Duration wait) {
		return listener(context, wait, duration -> steps.add("等 " + duration.toSeconds() + " 秒"));
	}

	private DeregisterBeforeStop listener(ApplicationContext owner, Duration wait, DeregisterBeforeStop.Sleeper sleeper) {
		doAnswer(invocation -> {
			steps.add("注销");
			return null;
		}).when(registry).deregister(self);
		doAnswer(invocation -> {
			steps.add("停注册");
			return null;
		}).when(auto).stop();
		return new DeregisterBeforeStop(owner, provider(auto), provider(registry), provider(self), wait, sleeper);
	}

	private static <T> ObjectProvider<T> provider(Object bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfUnique()).thenReturn((T) bean);
		return provider;
	}

	@Test
	void deregistersThenWaitsThenStops() {
		when(auto.isRunning()).thenReturn(true);

		listener(WAIT).onApplicationEvent(new ContextClosedEvent(context));

		assertThat(steps).containsExactly("注销", "等 8 秒", "停注册");
	}

	@Test
	void zeroWaitStopsRightAfterDeregistering() {
		when(auto.isRunning()).thenReturn(true);

		listener(Duration.ZERO).onApplicationEvent(new ContextClosedEvent(context));

		assertThat(steps).containsExactly("注销", "停注册");
	}

	/** 配置项写成空串时转出来的等待时间是 null */
	@Test
	void missingWaitMeansNoWait() {
		when(auto.isRunning()).thenReturn(true);

		listener(null).onApplicationEvent(new ContextClosedEvent(context));

		assertThat(steps).containsExactly("注销", "停注册");
	}

	/** 启动到一半失败、或者关掉了自动登记：没人会往这里发请求，不用等 */
	@Test
	void doesNothingWhenNotRegistered() {
		when(auto.isRunning()).thenReturn(false);

		listener(WAIT).onApplicationEvent(new ContextClosedEvent(context));

		assertThat(steps).isEmpty();
		verifyNoInteractions(registry);
	}

	@Test
	void doesNothingWithoutARegistry() {
		DeregisterBeforeStop listener = new DeregisterBeforeStop(context, provider(null), provider(null), provider(null),
			WAIT, duration -> steps.add("等"));

		listener.onApplicationEvent(new ContextClosedEvent(context));

		assertThat(steps).isEmpty();
	}

	@Test
	void ignoresOtherContextsClosing() {
		when(auto.isRunning()).thenReturn(true);

		listener(WAIT).onApplicationEvent(new ContextClosedEvent(mock(ApplicationContext.class)));

		assertThat(steps).isEmpty();
	}

	@Test
	void failedDeregistrationSkipsTheWait() {
		when(auto.isRunning()).thenReturn(true);
		DeregisterBeforeStop listener = listener(WAIT);
		doThrow(new IllegalStateException("注册中心连不上")).when(registry).deregister(self);

		listener.onApplicationEvent(new ContextClosedEvent(context));

		assertThat(steps).containsExactly("停注册");
	}

	/** Nacos 客户端 3.0.3 关闭时会报空指针：不能让它打断后面的停止步骤 */
	@Test
	void errorWhileStoppingTheRegistrationIsNotPropagated() {
		when(auto.isRunning()).thenReturn(true);
		DeregisterBeforeStop listener = listener(WAIT);
		doThrow(new NullPointerException("NotifyCenter.INSTANCE is null")).when(auto).stop();

		listener.onApplicationEvent(new ContextClosedEvent(context));

		assertThat(steps).containsExactly("注销", "等 8 秒");
		verify(auto).stop();
	}

	@Test
	void interruptedWaitStillStopsAndKeepsTheInterruptFlag() {
		when(auto.isRunning()).thenReturn(true);
		DeregisterBeforeStop listener = listener(context, WAIT, duration -> {
			throw new InterruptedException();
		});

		listener.onApplicationEvent(new ContextClosedEvent(context));

		assertThat(Thread.interrupted()).isTrue();
		assertThat(steps).containsExactly("注销", "停注册");
	}

	@Test
	void runsFirstAndInThePublishingThread() {
		DeregisterBeforeStop listener = listener(WAIT);

		assertThat(listener.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
		assertThat(listener.supportsAsyncExecution()).isFalse();
		verify(auto, never()).stop();
	}

	/**
	 * 用真的 Spring 上下文关一次：等待期间线程池还能接任务，关完以后不能。
	 * 线程池先登记、这个监听器后登记：不排在最前面的话，线程池会先收到关闭事件，等待期间就拒绝任务了。
	 */
	@Test
	void executorsStillAcceptTasksDuringTheWait() {
		when(auto.isRunning()).thenReturn(true);
		AtomicBoolean ranDuringTheWait = new AtomicBoolean();
		GenericApplicationContext real = new GenericApplicationContext();
		real.registerBean("taskExecutor", ThreadPoolTaskExecutor.class);
		real.registerBean(DeregisterBeforeStop.class, () -> listener(real, WAIT, duration -> {
			CountDownLatch done = new CountDownLatch(1);
			real.getBean(ThreadPoolTaskExecutor.class).execute(done::countDown);
			ranDuringTheWait.set(done.await(5, TimeUnit.SECONDS));
		}));
		real.refresh();
		ThreadPoolTaskExecutor executor = real.getBean(ThreadPoolTaskExecutor.class);

		real.close();

		assertThat(steps).containsExactly("注销", "停注册");
		assertThat(ranDuringTheWait).isTrue();
		assertThatThrownBy(() -> executor.execute(() -> {
		})).isInstanceOf(TaskRejectedException.class);
	}
}
