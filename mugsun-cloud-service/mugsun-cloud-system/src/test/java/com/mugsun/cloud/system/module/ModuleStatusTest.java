package com.mugsun.cloud.system.module;

import com.mugsun.boot.common.module.InvalidModuleSwitchException;
import com.mugsun.boot.common.module.ModuleSwitch;
import com.mugsun.boot.system.service.ParamService;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * system 服务怎么判断 GIS、埋点这些可选模块在不在、开没开：注册中心用一个假的代替，时间用手动拨的钟。
 */
class ModuleStatusTest {

	private static final String GIS = "mugsun-gis";

	private static final String TRACK = "mugsun-track";

	private final FakeDiscovery discovery = new FakeDiscovery();

	private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

	private final ServicePresence presence =
		new ServicePresence(discovery, Duration.ofSeconds(5), Duration.ofSeconds(60), nanos::get);

	private final ParamService paramService = mock(ParamService.class);

	private final CloudGisModuleStatus gis = new CloudGisModuleStatus(presence, paramService, GIS, null);

	@Test
	void absentWhenNoInstanceWasEverSeen() {
		assertThat(presence.present(GIS)).isFalse();
	}

	@Test
	void presentWhenAnInstanceIsRegistered() {
		discovery.up();
		assertThat(presence.present(GIS)).isTrue();
	}

	@Test
	void registryIsAskedAtMostOncePerCachePeriod() {
		discovery.up();
		presence.present(GIS);
		presence.present(GIS);
		assertThat(discovery.calls).isEqualTo(1);

		advance(Duration.ofSeconds(6));
		presence.present(GIS);
		assertThat(discovery.calls).isEqualTo(2);
	}

	@Test
	void staysPresentWhileInstancesAreBrieflyGone() {
		discovery.up();
		presence.present(GIS);

		discovery.down();
		advance(Duration.ofSeconds(6));
		assertThat(presence.present(GIS)).isTrue();

		advance(Duration.ofSeconds(50));
		assertThat(presence.present(GIS)).isTrue();

		advance(Duration.ofSeconds(11));
		assertThat(presence.present(GIS)).isFalse();

		discovery.up();
		advance(Duration.ofSeconds(6));
		assertThat(presence.present(GIS)).isTrue();
	}

	@Test
	void graceStartsWhenTheInstancesAreFirstFoundMissing() {
		discovery.up();
		presence.present(GIS);

		// 很久没人问，服务正好在这时候重启：不能因为上次查到实例已经是很久以前就立刻算不在
		advance(Duration.ofMinutes(10));
		discovery.down();
		assertThat(presence.present(GIS)).isTrue();

		advance(Duration.ofSeconds(55));
		assertThat(presence.present(GIS)).isTrue();

		advance(Duration.ofSeconds(6));
		assertThat(presence.present(GIS)).isFalse();
	}

	@Test
	void refreshNoticesAMissingServiceWithoutAnyoneAsking() {
		discovery.up();
		presence.refresh();
		assertThat(discovery.calls).isEqualTo(1);

		discovery.down();
		advance(Duration.ofSeconds(6));
		presence.refresh();

		// 之后一直没人打开页面：过了宽限期，第一个来的人看到的就是「不在」
		advance(Duration.ofSeconds(61));
		assertThat(presence.present(GIS)).isFalse();
	}

	@Test
	void refreshAsksTheRegistryOnlyWhenTheAnswerHasExpired() {
		presence.refresh();
		advance(Duration.ofSeconds(1));
		presence.refresh();
		assertThat(discovery.calls).isEqualTo(1);

		advance(Duration.ofSeconds(5));
		presence.refresh();
		assertThat(discovery.calls).isEqualTo(2);
	}

	@Test
	void nothingIsRefreshedUntilAServiceIsWatched() {
		FakeDiscovery own = new FakeDiscovery();
		ServicePresence unwatched = new ServicePresence(own, Duration.ofSeconds(5), Duration.ofSeconds(60), nanos::get);
		new CloudGisModuleStatus(unwatched, paramService, GIS, "false");

		unwatched.refresh();
		assertThat(own.calls).isZero();

		unwatched.present(GIS);
		advance(Duration.ofSeconds(6));
		unwatched.refresh();
		assertThat(own.calls).isEqualTo(2);
	}

	@Test
	void keepsTheLastAnswerWhenTheRegistryCannotBeReached() {
		discovery.error = new IllegalStateException("连不上注册中心");
		assertThat(presence.present(GIS)).isFalse();

		discovery.error = null;
		discovery.up();
		advance(Duration.ofSeconds(6));
		assertThat(presence.present(GIS)).isTrue();

		discovery.error = new IllegalStateException("连不上注册中心");
		advance(Duration.ofMinutes(10));
		assertThat(presence.present(GIS)).isTrue();
	}

	@Test
	void gisIsDisabledWhenTheServiceIsNotDeployed() {
		when(paramService.getValue(ModuleSwitch.PARAM_GIS)).thenReturn("true");

		assertThat(gis.present()).isFalse();
		assertThat(gis.enabled()).isFalse();
	}

	@Test
	void gisIsEnabledByDefaultWhenTheServiceIsDeployed() {
		discovery.up();
		when(paramService.getValue(ModuleSwitch.PARAM_GIS)).thenReturn(null);

		assertThat(gis.present()).isTrue();
		assertThat(gis.enabled()).isTrue();
	}

	@Test
	void systemParamTurnsGisOffEvenIfTheServiceIsDeployed() {
		discovery.up();
		when(paramService.getValue(ModuleSwitch.PARAM_GIS)).thenReturn("false");

		assertThat(gis.present()).isTrue();
		assertThat(gis.enabled()).isFalse();
	}

	@Test
	void illegalSystemParamValueCountsAsOff() {
		discovery.up();
		when(paramService.getValue(ModuleSwitch.PARAM_GIS)).thenReturn("maybe");

		assertThat(gis.enabled()).isFalse();
	}

	@Test
	void configSwitchTurnsGisOffWithoutAskingTheRegistry() {
		discovery.up();
		when(paramService.getValue(ModuleSwitch.PARAM_GIS)).thenReturn("true");
		CloudGisModuleStatus off = new CloudGisModuleStatus(presence, paramService, GIS, "false");

		assertThat(off.present()).isFalse();
		assertThat(off.enabled()).isFalse();
		assertThat(discovery.calls).isZero();
	}

	@Test
	void configSwitchAcceptsTrueInAnyCase() {
		discovery.up();
		CloudGisModuleStatus on = new CloudGisModuleStatus(presence, paramService, GIS, "TRUE");

		assertThat(on.enabled()).isTrue();
	}

	@Test
	void illegalConfigSwitchFailsAtStartup() {
		for (String raw : new String[] {"", "1", "0", " true", "yes"}) {
			assertThatThrownBy(() -> new CloudGisModuleStatus(presence, paramService, GIS, raw))
				.isInstanceOf(InvalidModuleSwitchException.class)
				.hasMessageContaining("mugsun.gis.enabled")
				.hasMessageContaining("MUGSUN_GIS_ENABLED");
		}
	}

	@Test
	void trackIsOffWhenOnlyOtherServicesAreDeployed() {
		CloudTrackModuleStatus track = new CloudTrackModuleStatus(presence, TRACK, null);
		discovery.up(GIS);

		assertThat(track.present()).isFalse();
		assertThat(track.enabled()).isFalse();
	}

	@Test
	void trackIsOnWhenItsServiceIsDeployed() {
		CloudTrackModuleStatus track = new CloudTrackModuleStatus(presence, TRACK, null);
		discovery.up(TRACK);

		assertThat(track.present()).isTrue();
		assertThat(track.enabled()).isTrue();
		assertThat(gis.present()).isFalse();
	}

	@Test
	void configSwitchTurnsTrackOffWithoutAskingTheRegistry() {
		discovery.up(TRACK);
		CloudTrackModuleStatus off = new CloudTrackModuleStatus(presence, TRACK, "false");

		assertThat(off.present()).isFalse();
		assertThat(off.enabled()).isFalse();
		assertThat(discovery.calls).isZero();
	}

	@Test
	void illegalTrackConfigSwitchFailsAtStartup() {
		for (String raw : new String[] {"", "1", " true"}) {
			assertThatThrownBy(() -> new CloudTrackModuleStatus(presence, TRACK, raw))
				.isInstanceOf(InvalidModuleSwitchException.class)
				.hasMessageContaining("mugsun.track.enabled")
				.hasMessageContaining("MUGSUN_TRACK_ENABLED");
		}
	}

	@Test
	void refreshChecksEveryWatchedService() {
		new CloudTrackModuleStatus(presence, TRACK, null);

		presence.refresh();
		assertThat(discovery.calls).isEqualTo(2);
	}

	private void advance(Duration duration) {
		nanos.addAndGet(duration.toNanos());
	}

	private static final class FakeDiscovery implements DiscoveryClient {

		private final Set<String> registered = new HashSet<>();

		private RuntimeException error;

		private int calls;

		void up() {
			up(GIS);
		}

		void up(String serviceId) {
			registered.add(serviceId);
		}

		void down() {
			registered.clear();
		}

		@Override
		public String description() {
			return "fake";
		}

		@Override
		public List<ServiceInstance> getInstances(String serviceId) {
			calls++;
			if (error != null) {
				throw error;
			}
			if (!registered.contains(serviceId)) {
				return List.of();
			}
			return List.of(new DefaultServiceInstance(serviceId + "-1", serviceId, "127.0.0.1", 8100, false));
		}

		@Override
		public List<String> getServices() {
			return List.copyOf(registered);
		}
	}
}
