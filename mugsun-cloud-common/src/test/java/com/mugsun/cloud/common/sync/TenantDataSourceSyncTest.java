package com.mugsun.cloud.common.sync;

import com.mugsun.boot.datasource.entity.SysTenantDatasource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户独立数据源的同步：库里的配置变了，本进程跟着注册或注销；没变就什么都不做。
 * 注册表用一个集合代替，记下每次注册和注销的租户编号。
 */
class TenantDataSourceSyncTest {

	private final Set<String> live = new HashSet<>();

	private final List<String> registered = new ArrayList<>();

	private final List<String> unregistered = new ArrayList<>();

	private List<SysTenantDatasource> rows = new ArrayList<>();

	private RuntimeException registerError;

	private boolean registerTakesEffect = true;

	private final TenantDataSourceSync sync = new TenantDataSourceSync(
		() -> rows,
		live::contains,
		config -> {
			registered.add(config.getTenantCode());
			if (registerError != null) {
				throw registerError;
			}
			if (registerTakesEffect) {
				live.add(config.getTenantCode());
			}
		},
		tenant -> {
			unregistered.add(tenant);
			live.remove(tenant);
		});

	@Test
	void dataSourceRegisteredAtStartupIsLeftAlone() {
		live.add("t1");
		rows = List.of(config("t1", "jdbc:postgresql://db1/app"));

		assertThat(sync.run()).isZero();
		assertThat(sync.run()).isZero();
		assertThat(registered).isEmpty();
		assertThat(unregistered).isEmpty();
	}

	@Test
	void configAddedLaterIsRegisteredOnce() {
		rows = List.of(config("t1", "jdbc:postgresql://db1/app"));

		assertThat(sync.run()).isEqualTo(1);
		assertThat(sync.run()).isZero();
		assertThat(registered).containsExactly("t1");
		assertThat(live).containsExactly("t1");
	}

	@Test
	void changedConfigIsRegisteredAgain() {
		live.add("t1");
		rows = List.of(config("t1", "jdbc:postgresql://db1/app"));
		sync.run();

		rows = List.of(config("t1", "jdbc:postgresql://db2/app"));
		assertThat(sync.run()).isEqualTo(1);
		assertThat(sync.run()).isZero();
		assertThat(registered).containsExactly("t1");
	}

	@Test
	void changedPasswordAloneCountsAsChange() {
		live.add("t1");
		SysTenantDatasource before = config("t1", "jdbc:postgresql://db1/app");
		before.setDsPassword("old");
		rows = List.of(before);
		sync.run();

		SysTenantDatasource after = config("t1", "jdbc:postgresql://db1/app");
		after.setDsPassword("new");
		rows = List.of(after);
		assertThat(sync.run()).isEqualTo(1);
	}

	@Test
	void disabledOrDeletedConfigIsUnregistered() {
		live.add("t1");
		live.add("t2");
		rows = List.of(config("t1", "jdbc:postgresql://db1/app"), config("t2", "jdbc:postgresql://db2/app"));
		sync.run();

		rows = List.of(config("t2", "jdbc:postgresql://db2/app"));
		assertThat(sync.run()).isEqualTo(1);
		assertThat(sync.run()).isZero();
		assertThat(unregistered).containsExactly("t1");
		assertThat(live).containsExactly("t2");
		assertThat(registered).isEmpty();
	}

	@Test
	void failedRegistrationIsRetriedNextRound() {
		rows = List.of(config("t1", "jdbc:postgresql://db1/app"));
		registerError = new IllegalStateException("连不上");

		assertThat(sync.run()).isZero();
		assertThat(live).isEmpty();

		registerError = null;
		assertThat(sync.run()).isEqualTo(1);
		assertThat(registered).containsExactly("t1", "t1");
		assertThat(live).containsExactly("t1");
	}

	@Test
	void failedChangeKeepsTheOldDataSourceAndRetries() {
		live.add("t1");
		rows = List.of(config("t1", "jdbc:postgresql://db1/app"));
		sync.run();

		rows = List.of(config("t1", "jdbc:postgresql://db2/app"));
		registerError = new IllegalStateException("连不上");
		assertThat(sync.run()).isZero();
		assertThat(live).containsExactly("t1");
		assertThat(unregistered).isEmpty();

		registerError = null;
		assertThat(sync.run()).isEqualTo(1);
		assertThat(sync.run()).isZero();
	}

	@Test
	void registrationWithoutEffectIsNotCountedAsApplied() {
		rows = List.of(config("t1", "jdbc:postgresql://db1/app"));
		registerTakesEffect = false;

		assertThat(sync.run()).isZero();
		assertThat(sync.run()).isZero();
		assertThat(live).isEmpty();
	}

	@Test
	void dataSourceDroppedLocallyIsRegisteredAgain() {
		live.add("t1");
		rows = List.of(config("t1", "jdbc:postgresql://db1/app"));
		sync.run();

		live.remove("t1");
		assertThat(sync.run()).isEqualTo(1);
		assertThat(live).containsExactly("t1");
	}

	@Test
	void liveDataSourceOfUnknownConfigIsRebuiltAfterTheFirstRound() {
		sync.run();

		// 第一轮以后有人绕过记录直接注册了数据源：不知道它是按哪份配置建的，不能当它就是库里现在这份
		live.add("t1");
		rows = List.of(config("t1", "jdbc:postgresql://db1/app"));
		assertThat(sync.run()).isEqualTo(1);
		assertThat(sync.run()).isZero();
		assertThat(registered).containsExactly("t1");
	}

	@Test
	void dataSourceRegisteredElsewhereInThisProcessIsNotRebuilt() throws Throwable {
		sync.run();

		// 控制器保存以后按刚保存的配置注册：下一轮读到的是同一份，不用再建一次
		SysTenantDatasource saved = config("t1", "jdbc:postgresql://db1/app");
		sync.registerExclusively(saved, () -> live.add("t1"));
		rows = List.of(config("t1", "jdbc:postgresql://db1/app"));

		assertThat(sync.run()).isZero();
		assertThat(registered).isEmpty();
		assertThat(live).containsExactly("t1");
	}

	@Test
	void dataSourceBuiltFromAnOlderSaveIsRebuiltFromTheLatest() throws Throwable {
		sync.run();

		// 本实例的控制器按第一次保存的配置建了连接池；紧接着另一个实例又保存了一次，库里已经是第二份配置
		sync.registerExclusively(config("t1", "jdbc:postgresql://db1/app"), () -> live.add("t1"));
		rows = List.of(config("t1", "jdbc:postgresql://db2/app"));

		assertThat(sync.run()).isEqualTo(1);
		assertThat(sync.run()).isZero();
		assertThat(registered).containsExactly("t1");
	}

	@Test
	void failedRegistrationElsewhereKeepsTheRecord() {
		live.add("t1");
		rows = List.of(config("t1", "jdbc:postgresql://db1/app"));
		sync.run();

		assertThatThrownBy(() -> sync.registerExclusively(config("t1", "jdbc:postgresql://db2/app"), () -> {
			throw new IllegalStateException("连不上");
		})).isInstanceOf(IllegalStateException.class);

		// 连接池还是按原来的配置建的，库里也没变：不重建
		assertThat(sync.run()).isZero();
		assertThat(registered).isEmpty();
	}

	@Test
	void dataSourceUnregisteredElsewhereFollowsTheDatabaseAgain() throws Throwable {
		live.add("t1");
		rows = List.of(config("t1", "jdbc:postgresql://db1/app"));
		sync.run();

		// 控制器先注销、再删库里的配置。删除还没提交时赶上的那一轮会按库里的配置重新注册，提交以后的一轮再注销
		sync.unregisterExclusively("t1", () -> live.remove("t1"));
		assertThat(sync.run()).isEqualTo(1);
		assertThat(live).containsExactly("t1");

		rows = List.of();
		assertThat(sync.run()).isEqualTo(1);
		assertThat(live).isEmpty();
	}

	@Test
	void registrationElsewhereWaitsForTheRunningRound() throws Exception {
		CountDownLatch reading = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		List<String> order = Collections.synchronizedList(new ArrayList<>());
		TenantDataSourceSync blocking = new TenantDataSourceSync(
			() -> {
				reading.countDown();
				try {
					release.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				order.add("定时比较");
				return List.of();
			},
			tenant -> false,
			config -> {
			},
			tenant -> {
			});
		Thread round = new Thread(blocking::run);
		round.start();
		assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();

		Thread elsewhere = new Thread(() -> {
			try {
				blocking.registerExclusively(config("t1", "jdbc:postgresql://db1/app"), () -> order.add("控制器注册"));
			} catch (Throwable e) {
				throw new IllegalStateException(e);
			}
		});
		elsewhere.start();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (elsewhere.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		// 这一轮比较还没结束：别处的注册等在外面
		assertThat(elsewhere.getState()).isEqualTo(Thread.State.BLOCKED);
		assertThat(order).isEmpty();

		release.countDown();
		round.join(10_000);
		elsewhere.join(10_000);
		assertThat(order).containsExactly("定时比较", "控制器注册");
	}

	@Test
	void fingerprintCoversInheritedFields() {
		Child a = new Child();
		Child b = new Child();
		assertThat(TenantDataSourceSync.fingerprint(a)).isEqualTo(TenantDataSourceSync.fingerprint(b));

		b.inherited = "changed";
		assertThat(TenantDataSourceSync.fingerprint(a)).isNotEqualTo(TenantDataSourceSync.fingerprint(b));

		b.inherited = a.inherited;
		b.own = "changed";
		assertThat(TenantDataSourceSync.fingerprint(a)).isNotEqualTo(TenantDataSourceSync.fingerprint(b));
	}

	private static SysTenantDatasource config(String tenant, String url) {
		SysTenantDatasource config = new SysTenantDatasource();
		config.setTenantCode(tenant);
		config.setDsUrl(url);
		config.setDsUsername("app");
		config.setStatus(1);
		return config;
	}

	static class Parent {

		String inherited = "a";
	}

	static class Child extends Parent {

		String own = "b";
	}
}
