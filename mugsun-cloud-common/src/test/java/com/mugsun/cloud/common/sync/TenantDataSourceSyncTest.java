package com.mugsun.cloud.common.sync;

import com.mugsun.boot.datasource.entity.SysTenantDatasource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
