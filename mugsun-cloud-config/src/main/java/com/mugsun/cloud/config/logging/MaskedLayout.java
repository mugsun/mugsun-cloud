package com.mugsun.cloud.config.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.LayoutBase;

/**
 * 包在原来的格式外面：一行日志照原样排好以后，整行过一遍 {@link LogSecrets#mask} 再交出去。
 * 换的是排好的整行，所以消息和异常堆栈里的都管得到，和日志格式怎么写没有关系。
 */
final class MaskedLayout extends LayoutBase<ILoggingEvent> {

	private final Layout<ILoggingEvent> delegate;

	MaskedLayout(Layout<ILoggingEvent> delegate) {
		this.delegate = delegate;
	}

	@Override
	public String doLayout(ILoggingEvent event) {
		return LogSecrets.mask(delegate.doLayout(event));
	}

	@Override
	public String getFileHeader() {
		return delegate.getFileHeader();
	}

	@Override
	public String getPresentationHeader() {
		return delegate.getPresentationHeader();
	}

	@Override
	public String getPresentationFooter() {
		return delegate.getPresentationFooter();
	}

	@Override
	public String getFileFooter() {
		return delegate.getFileFooter();
	}

	@Override
	public String getContentType() {
		return delegate.getContentType();
	}

	@Override
	public Context getContext() {
		return delegate.getContext();
	}

	@Override
	public void start() {
		delegate.start();
	}

	@Override
	public void stop() {
		delegate.stop();
	}

	@Override
	public boolean isStarted() {
		return delegate.isStarted();
	}
}
