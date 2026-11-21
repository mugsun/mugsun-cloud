package com.mugsun.cloud.common.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 共享状态的版本：Redis 里的一个值，所有服务的所有实例读写的是同一个。
 *
 * <p>数据权限规则、接口权限规则和角色授权、租户独立数据源以库里的为准，每个实例在内存里各留了一份。
 * 某个实例上有人改了其中一样，就给这个值换一个新的（见 {@link SharedStateChangeAspect}）；每个实例隔一秒读一次，
 * 和自己上次见到的不一样就从库里重新加载（见 {@link SharedStateSyncTask}）。
 *
 * <p>值是「时间/随机串」，每次都不同，没有用自增的数：Redis 被清空或者主从切换以后，自增的数会回到实例已经见过的值，
 * 实例会以为没有变化。时间只是给人看的，实例只比较前后是否相同。
 */
@Component
@ConditionalOnProperty(name = "mugsun.cloud.sync.enabled", havingValue = "true", matchIfMissing = true)
public class SharedStateVersion {

	static final String KEY = "mugsun:cloud:shared-state:version";

	private static final Logger log = LoggerFactory.getLogger(SharedStateVersion.class);

	private final StringRedisTemplate redis;

	public SharedStateVersion(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/** 现在的版本。还没有实例改过任何东西时是空串。Redis 读不到时抛异常。 */
	public String current() {
		String value = redis.opsForValue().get(KEY);
		return value == null ? "" : value;
	}

	/**
	 * 换一个新版本，让所有实例重新加载。要在改动已经写进库里（事务已经提交）之后调用。
	 *
	 * <p>Redis 不可用时只记日志，不抛异常：改动已经在库里了，别的实例靠定时的重新加载也能跟上，只是晚一些。
	 */
	public void bump() {
		try {
			redis.opsForValue().set(KEY, Instant.now() + "/" + UUID.randomUUID());
			log.debug("本实例上有改动，已通知各实例重新加载");
		} catch (RuntimeException e) {
			log.warn("改动已经保存，但没能经 Redis 通知其它实例，它们要等定时的重新加载才跟上：{}", e.getMessage());
		}
	}
}
