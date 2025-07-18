/*
 * Copyright 2018-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.config.client;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.retry.annotation.Retryable;

/**
 * Fetches config server instances.
 *
 * @author Nastya Smirnova
 */
public class ConfigServerInstanceProvider {

	private Log log = LogFactory.getLog(getClass());

	private final Function function;

	private BindHandler bindHandler;

	private Binder binder;

	public ConfigServerInstanceProvider(Function function) {
		this.function = function;
	}

	public ConfigServerInstanceProvider(Function function, Binder binder, BindHandler bindHandler) {
		this.function = function;
		this.binder = binder;
		this.bindHandler = bindHandler;
	}

	void setLog(Log log) {
		this.log = log;
	}

	@Retryable(interceptor = "configServerRetryInterceptor")
	public List<ServiceInstance> getConfigServerInstances(String serviceId) {
		if (log.isDebugEnabled()) {
			log.debug("Locating configserver (" + serviceId + ") via discovery");
		}
		List<ServiceInstance> instances;
		if (binder == null || bindHandler == null) {
			instances = this.function.apply(serviceId);
		}
		else {
			instances = this.function.apply(serviceId, binder, bindHandler, log);
		}
		if (instances.isEmpty()) {
			throw new IllegalStateException("No instances found of configserver (" + serviceId + ")");
		}
		if (log.isDebugEnabled()) {
			log.debug("Located configserver (" + serviceId + ") via discovery. No of instances found: "
					+ instances.size());
		}
		return instances;
	}

	@FunctionalInterface
	public interface Function {

		List<ServiceInstance> apply(String serviceId);

		default List<ServiceInstance> apply(String serviceId, Binder binder, BindHandler bindHandler, Log log) {
			return apply(serviceId);
		};

	}

}
