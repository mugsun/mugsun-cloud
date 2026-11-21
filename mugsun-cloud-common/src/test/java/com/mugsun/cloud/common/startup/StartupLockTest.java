package com.mugsun.cloud.common.startup;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 启动锁：占得到就往下启动、启动完放开；被别的实例占着就等；等不到或 Redis 用不了时不加锁继续启动。
 * Redis 用模拟对象代替，等待不真等，只记下等了几次。「只续期、只删除自己占的锁」由 Redis 脚本保证，这里只看传进去的键和值。
 */
@SuppressWarnings("unchecked")
class StartupLockTest {

	private static final Duration TTL = Duration.ofSeconds(60);

	private static final String KEY = "mugsun:cloud:startup-lock:mugsun-system";

	private final ValueOperations<String, String> values = mock(ValueOperations.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final List<Duration> sleeps = new ArrayList<>();

	private StartupLock lock(String service, Duration maxWait) {
		when(redis.opsForValue()).thenReturn(values);
		return new StartupLock(redis, service, TTL, maxWait, sleeps::add);
	}

	/** 占锁时写进 Redis 的值：放开和续期都要带着它 */
	private String tokenUsed() {
		ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
		verify(values, atLeastOnce()).setIfAbsent(eq(KEY), token.capture(), eq(TTL));
		return token.getValue();
	}

	@Test
	void takesTheLockWhenFreeAndReleasesItOnceWhenReady() {
		when(values.setIfAbsent(eq(KEY), anyString(), eq(TTL))).thenReturn(true);
		StartupLock lock = lock("mugsun-system", Duration.ofMinutes(2));

		lock.start();
		assertThat(lock.isRunning()).isTrue();
		assertThat(sleeps).isEmpty();
		String token = tokenUsed();

		lock.onReady();
		lock.stop();
		lock.destroy();
		// 启动完成时放开一次；之后应用关闭时不再去删（那时锁可能已经是别的实例的了）
		verify(redis, times(1)).execute(any(RedisScript.class), eq(List.of(KEY)), eq(token));
		assertThat(lock.isRunning()).isFalse();
	}

	@Test
	void waitsWhileAnotherInstanceHoldsTheLock() {
		when(values.setIfAbsent(eq(KEY), anyString(), eq(TTL))).thenReturn(false, false, true);
		StartupLock lock = lock("mugsun-system", Duration.ofMinutes(2));

		lock.start();
		assertThat(sleeps).containsExactly(StartupLock.RETRY_INTERVAL, StartupLock.RETRY_INTERVAL);
		String token = tokenUsed();

		lock.onReady();
		verify(redis, times(1)).execute(any(RedisScript.class), eq(List.of(KEY)), eq(token));
	}

	@Test
	void startsWithoutTheLockWhenTheWaitRunsOut() {
		when(values.setIfAbsent(eq(KEY), anyString(), eq(TTL))).thenReturn(false);
		StartupLock lock = lock("mugsun-system", Duration.ofSeconds(1));

		lock.start();
		assertThat(lock.isRunning()).isTrue();
		assertThat(sleeps).hasSize(2);

		// 没占到就不能去删，也不能去续期：那把锁是别的实例的
		lock.renew();
		lock.onReady();
		lock.stop();
		verify(redis, atLeastOnce()).opsForValue();
		verifyNoMoreInteractions(redis);
	}

	@Test
	void startsWithoutTheLockWhenRedisIsUnavailable() {
		when(values.setIfAbsent(eq(KEY), anyString(), eq(TTL))).thenThrow(new RedisConnectionFailureException("连不上"));
		StartupLock lock = lock("mugsun-system", Duration.ofMinutes(2));

		lock.start();
		assertThat(lock.isRunning()).isTrue();
		assertThat(sleeps).isEmpty();

		lock.onReady();
		verify(redis, atLeastOnce()).opsForValue();
		verifyNoMoreInteractions(redis);
	}

	@Test
	void keyIsScopedToTheService() {
		when(values.setIfAbsent(eq("mugsun:cloud:startup-lock:mugsun-track"), anyString(), eq(TTL))).thenReturn(true);
		StartupLock lock = lock("mugsun-track", Duration.ofMinutes(2));

		lock.start();
		assertThat(sleeps).as("别的服务占着的是另一把锁，不影响这个服务").isEmpty();
		lock.stop();
	}

	@Test
	void renewExtendsTheLockByItsOwnToken() {
		when(values.setIfAbsent(eq(KEY), anyString(), eq(TTL))).thenReturn(true);
		when(redis.execute(any(RedisScript.class), eq(List.of(KEY)), anyString(), eq("60000"))).thenReturn(1L);
		StartupLock lock = lock("mugsun-system", Duration.ofMinutes(2));
		lock.start();
		String token = tokenUsed();

		lock.renew();
		verify(redis).execute(any(RedisScript.class), eq(List.of(KEY)), eq(token), eq("60000"));
		lock.stop();
	}

	@Test
	void lockIsTakenBeforeTheWebServerStartsAcceptingRequests() {
		// 内嵌 Web 服务器开始接请求的阶段是「优雅停机」阶段减 1024；阶段数小的先启动
		assertThat(StartupLock.PHASE).isLessThan(WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE - 1024);
	}

	/**
	 * 实测遇到过的：机器重启后注册中心还没起来，服务占到了锁、Web 服务器也起来了，登记不上，启动失败。
	 * 这时 Spring 先关了 Redis 连接才轮到放锁，锁留在 Redis 里，重新拉起来的实例等了 25 秒才等到它过期。
	 */
	@Test
	void releasesTheLockBeforeTheRedisConnectionClosesWhenStartupFailsHalfway() {
		List<String> events = new ArrayList<>();
		try (GenericApplicationContext context = contextWithTheLock(events)) {
			context.registerBean("webServer", FailsToStart.class, FailsToStart::new);

			assertThatThrownBy(context::refresh).isInstanceOf(ApplicationContextException.class);
		}
		assertThat(events).containsExactly("放开了锁", "连接关了");
	}

	/** 占到了锁，还没到启动完成应用就关闭（比如某个 Runner 抛了异常）：也要先放锁再关连接 */
	@Test
	void releasesTheLockBeforeTheRedisConnectionClosesWhenClosedBeforeReady() {
		List<String> events = new ArrayList<>();
		try (GenericApplicationContext context = contextWithTheLock(events)) {
			context.refresh();
			assertThat(events).isEmpty();
		}
		assertThat(events).containsExactly("放开了锁", "连接关了");
	}

	/** 一个还没启动的应用上下文，里面有连接工厂和启动锁。events 里按先后记下放锁和关连接 */
	private GenericApplicationContext contextWithTheLock(List<String> events) {
		FakeConnectionFactory connectionFactory = new FakeConnectionFactory(events);
		when(values.setIfAbsent(eq(KEY), anyString(), eq(TTL))).thenReturn(true);
		when(redis.execute(any(RedisScript.class), eq(List.of(KEY)), anyString())).thenAnswer(invocation -> {
			events.add(connectionFactory.isRunning() ? "放开了锁" : "要放锁时连接已经关了");
			return 1L;
		});
		StartupLock lock = lock("mugsun-system", Duration.ofMinutes(2));
		GenericApplicationContext context = new GenericApplicationContext();
		context.registerBean("redisConnectionFactory", FakeConnectionFactory.class, () -> connectionFactory);
		context.registerBean("startupLock", StartupLock.class, () -> lock);
		return context;
	}

	/** 和真的连接工厂一样：是一个阶段为 0 的 SmartLifecycle，建好就在运行 */
	private static class FakeConnectionFactory implements RedisConnectionFactory, SmartLifecycle {

		private final List<String> events;

		private boolean running = true;

		FakeConnectionFactory(List<String> events) {
			this.events = events;
		}

		@Override
		public void start() {
			running = true;
		}

		@Override
		public void stop() {
			running = false;
			events.add("连接关了");
		}

		@Override
		public boolean isRunning() {
			return running;
		}

		@Override
		public int getPhase() {
			return 0;
		}

		@Override
		public RedisConnection getConnection() {
			throw new UnsupportedOperationException();
		}

		@Override
		public RedisClusterConnection getClusterConnection() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean getConvertPipelineAndTxResults() {
			return false;
		}

		@Override
		public RedisSentinelConnection getSentinelConnection() {
			throw new UnsupportedOperationException();
		}

		@Override
		public DataAccessException translateExceptionIfPossible(RuntimeException ex) {
			return null;
		}
	}

	/** 排在启动锁后面启动、一启动就失败的东西 */
	private static class FailsToStart implements SmartLifecycle {

		@Override
		public void start() {
			throw new IllegalStateException("登记不上注册中心");
		}

		@Override
		public void stop() {
		}

		@Override
		public boolean isRunning() {
			return false;
		}
	}
}
