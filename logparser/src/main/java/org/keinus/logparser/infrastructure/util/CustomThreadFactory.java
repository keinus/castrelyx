package org.keinus.logparser.infrastructure.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CustomThreadFactory implements ThreadFactory {
	private static final Logger LOGGER = LoggerFactory.getLogger(CustomThreadFactory.class);
	private final AtomicInteger threadNum = new AtomicInteger(0);
	private final String namePrefix;
	
	public CustomThreadFactory(String namePrefix) {
		this.namePrefix = namePrefix;
	}

	@Override
	public Thread newThread(Runnable r) {
		Thread thread = Thread.ofVirtual()
				.name(String.format("%s-%d", this.namePrefix, this.threadNum.getAndIncrement()))
				.unstarted(r);
		
		LOGGER.info("Virtual Thread created: {}", thread.getName());
		return thread;
	}
}