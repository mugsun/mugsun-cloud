package com.mugsun.cloud.common.sync;

import com.mugsun.boot.config.ApiScopeRuleLoader;
import com.mugsun.boot.config.DataScopeRuleLoader;
import com.mugsun.boot.datasource.entity.SysTenantDatasource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 什么时候从库里重新加载：Redis 里的版本变了，或者离上次加载满了一个兜底间隔。时间和版本都由测试控制。
 */
class SharedStateSyncTaskTest {

	private static final long INTERVAL_MS = 30_000;

	private static final long CHECK_MS = 1_000;

	private final DataScopeRuleLoader dataScopeRules = mock(DataScopeRuleLoader.class);

	private final ApiScopeRuleLoader apiScopeRules = mock(ApiScopeRuleLoader.class);

	/** 读了几次库里的租户数据源配置 */
	private final AtomicInteger dataSourceRuns = new AtomicInteger();

	private final TenantDataSourceSync dataSources = new TenantDataSourceSync(
		() -> {
			dataSourceRuns.incrementAndGet();
			return List.<SysTenantDatasource>of();
		},
		tenant -> false,
		config -> {
		},
		tenant -> {
		});

	/** Redis 里现在的版本。null 表示 Redis 读不到 */
	private String version = "";

	private long now;

	private final SharedStateSyncTask task = new SharedStateSyncTask(dataScopeRules, apiScopeRules, dataSources,
		this::readVersion, INTERVAL_MS, CHECK_MS, () -> now);

	private String readVersion() {
		if (version == null) {
			throw new RedisConnectionFailureException("连不上 Redis");
		}
		return version;
	}

	private void tickAfter(long millis) {
		now += TimeUnit.MILLISECONDS.toNanos(millis);
		task.tick();
	}

	private void assertRuleLoads(int count) {
		verify(dataScopeRules, times(count)).reload();
		verify(apiScopeRules, times(count)).reload();
	}

	@Test
	void doesNothingBeforeTheApplicationIsReady() {
		tickAfter(INTERVAL_MS);

		assertRuleLoads(0);
		assertThat(dataSourceRuns).hasValue(0);
	}

	@Test
	void loadsOnceRightAfterStartupThenWaitsForAChange() {
		task.becomeReady();
		// 启动完成时先对一遍数据源，规则在启动过程中已经加载过
		assertThat(dataSourceRuns).hasValue(1);
		assertRuleLoads(0);

		// 第一次读到版本：启动期间别的实例可能改过东西，先加载一遍
		tickAfter(CHECK_MS);
		assertRuleLoads(1);
		assertThat(dataSourceRuns).hasValue(2);

		tickAfter(CHECK_MS);
		tickAfter(CHECK_MS);
		assertRuleLoads(1);
		assertThat(dataSourceRuns).hasValue(2);
	}

	@Test
	void reloadsWhenTheVersionChanges() {
		task.becomeReady();
		tickAfter(CHECK_MS);

		version = "改过一次";
		tickAfter(CHECK_MS);
		assertRuleLoads(2);
		assertThat(dataSourceRuns).hasValue(3);

		tickAfter(CHECK_MS);
		assertRuleLoads(2);
	}

	@Test
	void reloadsEveryIntervalEvenWithoutAChange() {
		task.becomeReady();
		tickAfter(CHECK_MS);

		tickAfter(INTERVAL_MS - CHECK_MS);
		assertRuleLoads(1);

		tickAfter(CHECK_MS);
		assertRuleLoads(2);
	}

	@Test
	void fallsBackToTheIntervalWhileRedisIsUnavailable() {
		version = null;
		task.becomeReady();

		tickAfter(CHECK_MS);
		assertRuleLoads(0);

		tickAfter(INTERVAL_MS - CHECK_MS);
		assertRuleLoads(1);

		// Redis 恢复：读到的是没见过的版本，不用等下一个间隔
		version = "断开期间有人改过";
		tickAfter(CHECK_MS);
		assertRuleLoads(2);

		tickAfter(CHECK_MS);
		assertRuleLoads(2);
	}

	@Test
	void retriesOnTheNextCheckWhenLoadingFailed() {
		task.becomeReady();
		tickAfter(CHECK_MS);

		version = "改过一次";
		doThrow(new DataAccessResourceFailureException("库连不上")).doNothing().when(dataScopeRules).reload();
		tickAfter(CHECK_MS);
		// 一类规则没加载成，另一类照常更新
		assertRuleLoads(2);

		// 没加载成功，这个版本就不算见过：下一次检查接着试，不等兜底间隔
		tickAfter(CHECK_MS);
		assertRuleLoads(3);

		tickAfter(CHECK_MS);
		assertRuleLoads(3);
	}

	@Test
	void stopsLoadingWhenTheApplicationStopsAcceptingTraffic() {
		task.becomeReady();
		tickAfter(CHECK_MS);

		task.onReadinessChanged(new AvailabilityChangeEvent<>(this, ReadinessState.REFUSING_TRAFFIC));
		version = "改过一次";
		tickAfter(INTERVAL_MS);

		assertRuleLoads(1);
	}

	@Test
	void marksTheThreadOnlyWhileLoading() {
		AtomicBoolean whileLoading = new AtomicBoolean();
		doAnswer(call -> {
			whileLoading.set(SharedStateSyncTask.isSyncing());
			return null;
		}).when(dataScopeRules).reload();

		task.becomeReady();
		tickAfter(CHECK_MS);

		assertThat(whileLoading).isTrue();
		assertThat(SharedStateSyncTask.isSyncing()).isFalse();
	}

	@Test
	void checksOnItsOwnThreadOnceTheApplicationIsReady() throws InterruptedException {
		SharedStateSyncTask running = new SharedStateSyncTask(dataScopeRules, apiScopeRules, dataSources,
			() -> "启动前就有的版本", INTERVAL_MS, 100, System::nanoTime);
		try {
			verify(dataScopeRules, never()).reload();
			running.onReadinessChanged(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));

			verify(dataScopeRules, timeout(5_000)).reload();
			verify(apiScopeRules, timeout(5_000)).reload();
		} finally {
			running.destroy();
		}

		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (tickerAlive() && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		assertThat(tickerAlive()).as("应用停止后检查线程要退出").isFalse();
	}

	private static boolean tickerAlive() {
		return Thread.getAllStackTraces().keySet().stream()
			.anyMatch(thread -> thread.isAlive() && "shared-state-sync".equals(thread.getName()));
	}
}
