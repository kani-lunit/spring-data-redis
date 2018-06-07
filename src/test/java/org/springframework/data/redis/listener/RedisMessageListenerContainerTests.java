/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.data.redis.listener;

import static org.hamcrest.core.Is.*;
import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

import java.util.concurrent.Executor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.Subscription;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * @author Mark Paluch
 * @author Christoph Strobl
 */
public class RedisMessageListenerContainerTests {

	private final Object handler = new Object() {

		@SuppressWarnings("unused")
		public void handleMessage(Object message) {}
	};

	private final MessageListenerAdapter adapter = new MessageListenerAdapter(handler);

	private RedisMessageListenerContainer container;

	private RedisConnectionFactory connectionFactoryMock;
	private RedisConnection connectionMock;
	private Subscription subscriptionMock;
	private Executor executorMock;

	@Before
	public void setUp() throws Exception {

		executorMock = mock(Executor.class);
		connectionFactoryMock = mock(LettuceConnectionFactory.class);
		connectionMock = mock(RedisConnection.class);
		subscriptionMock = mock(Subscription.class);

		container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactoryMock);
		container.setBeanName("container");
		container.setTaskExecutor(new SyncTaskExecutor());
		container.setSubscriptionExecutor(executorMock);
		container.setMaxSubscriptionRegistrationWaitingTime(1);
		container.afterPropertiesSet();
	}

	@After
	public void tearDown() throws Exception {

		container.destroy();
	}

	@Test // DATAREDIS-415
	public void interruptAtStart() throws Exception {

		final Thread main = Thread.currentThread();

		// interrupt thread once Executor.execute is called
		doAnswer(new Answer<Object>() {

			@Override
			public Object answer(final InvocationOnMock invocationOnMock) throws Throwable {

				main.interrupt();
				return null;
			}
		}).when(executorMock).execute(any(Runnable.class));

		container.addMessageListener(adapter, new ChannelTopic("a"));
		container.start();

		// reset the interrupted flag to not destroy the teardown
		assertThat(Thread.interrupted(), is(true));

		assertThat(container.isRunning(), is(false));
	}

	@Test // DATAREDIS-840
	public void containerShouldStopGracefullyOnUnsubscribeErrors() {

		when(connectionFactoryMock.getConnection()).thenReturn(connectionMock);
		doThrow(new IllegalStateException()).when(subscriptionMock).pUnsubscribe();

		doAnswer(new Answer() {

			@Override
			public Object answer(InvocationOnMock invocation) throws Throwable {

				Runnable r = invocation.getArgumentAt(0, Runnable.class);
				new Thread(r).start();
				return null;
			}
		}).when(executorMock).execute(any(Runnable.class));

		doAnswer(new Answer() {

			@Override
			public Object answer(InvocationOnMock invocation) throws Throwable {

				when(connectionMock.isSubscribed()).thenReturn(true);
				return null;
			}
		}).when(connectionMock).subscribe(any(MessageListener.class), any(byte[][].class));

		container.addMessageListener(adapter, new ChannelTopic("a"));
		container.start();

		when(connectionMock.getSubscription()).thenReturn(subscriptionMock);

		container.stop();

		assertThat(container.isRunning(), is(false));
		verify(subscriptionMock).pUnsubscribe();
	}
}
