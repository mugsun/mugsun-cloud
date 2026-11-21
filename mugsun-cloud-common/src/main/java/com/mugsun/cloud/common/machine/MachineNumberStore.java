package com.mugsun.cloud.common.machine;

import java.time.Duration;

/** 机器号登记在哪儿。运行时是 Redis（{@link RedisMachineNumberStore}），单测里换成内存里的一张表。 */
interface MachineNumberStore {

	/** 这次从第几个号开始找。所有实例共用一个计数，从 0 开始，每调一次加一 */
	long nextStart();

	/** 这个号没有人登记时，登记到 owner 名下并返回 true；已经有人登记就返回 false */
	boolean claim(int number, String owner, Duration ttl);

	/** 给自己名下的号续期 */
	Renewal renew(int number, String owner, Duration ttl);

	/** 这个号登记在 owner 名下才删 */
	void release(int number, String owner);

	enum Renewal {

		/** 还在自己名下，过期时间往后延了 */
		RENEWED,

		/** 登记已经过期、还没有被别的实例领走，重新登记到了自己名下 */
		RECLAIMED,

		/** 已经登记在别的实例名下 */
		TAKEN
	}
}
