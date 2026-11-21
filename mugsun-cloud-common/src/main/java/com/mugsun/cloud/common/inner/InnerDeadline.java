package com.mugsun.cloud.common.inner;

/**
 * 当前线程正在处理的那次内部调用，调用方最多等到什么时候。{@link InnerRequestFilter} 在调用接口之前记下，结束时清掉；
 * {@link InnerQueryTimeout} 据此给这期间发出的查询设时限。
 *
 * <p>记在线程上，所以只管得到处理这次调用的线程自己发出的查询；接口里另开线程去查的管不到。
 */
final class InnerDeadline {

	/** 到期的时刻，用 {@link System#nanoTime()} 的刻度 */
	private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();

	private InnerDeadline() {
	}

	/** @param deadlineNanos 到期的时刻，用 {@link System#nanoTime()} 的刻度 */
	static void set(long deadlineNanos) {
		DEADLINE.set(deadlineNanos);
	}

	static void clear() {
		DEADLINE.remove();
	}

	/** 当前线程是不是在处理一次说了「最多等多久」的内部调用 */
	static boolean active() {
		return DEADLINE.get() != null;
	}

	/** 调用方是不是已经不等了 */
	static boolean passed() {
		Long deadline = DEADLINE.get();
		return deadline != null && deadline - System.nanoTime() <= 0;
	}

	/**
	 * 离调用方不再等还剩多少秒，向上取整。已经过了也返回 1：给语句设时限时 0 表示不限，不能用。
	 * 当前线程不在处理这样的调用时返回 0。
	 */
	static int secondsLeft() {
		Long deadline = DEADLINE.get();
		if (deadline == null) {
			return 0;
		}
		long nanos = deadline - System.nanoTime();
		if (nanos <= 0) {
			return 1;
		}
		return (int) Math.min((nanos + 999_999_999L) / 1_000_000_000L, Integer.MAX_VALUE);
	}
}
