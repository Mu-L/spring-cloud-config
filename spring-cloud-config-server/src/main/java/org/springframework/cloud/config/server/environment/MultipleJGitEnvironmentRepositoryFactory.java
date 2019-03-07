/*
 * Copyright 2018-2019 the original author or authors.
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

package org.springframework.cloud.config.server.environment;

import java.util.Optional;

import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.HttpTransport;

import org.springframework.cloud.config.server.config.ConfigServerProperties;
import org.springframework.cloud.config.server.ssh.FileBasedSshTransportConfigCallback;
import org.springframework.cloud.config.server.ssh.PropertiesBasedSshTransportConfigCallback;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * @author Dylan Roberts
 */
public class MultipleJGitEnvironmentRepositoryFactory implements
		EnvironmentRepositoryFactory<MultipleJGitEnvironmentRepository, MultipleJGitEnvironmentProperties> {

	private ConfigurableEnvironment environment;

	private ConfigServerProperties server;

	private Optional<ConfigurableHttpConnectionFactory> connectionFactory;

	private Optional<TransportConfigCallback> customTransportConfigCallback;

	@Deprecated
	public MultipleJGitEnvironmentRepositoryFactory(ConfigurableEnvironment environment,
			ConfigServerProperties server,
			Optional<TransportConfigCallback> customTransportConfigCallback) {
		this(environment, server, Optional.empty(), customTransportConfigCallback);
	}

	public MultipleJGitEnvironmentRepositoryFactory(ConfigurableEnvironment environment,
			ConfigServerProperties server,
			Optional<ConfigurableHttpConnectionFactory> connectionFactory,
			Optional<TransportConfigCallback> customTransportConfigCallback) {
		this.environment = environment;
		this.server = server;
		this.connectionFactory = connectionFactory;
		this.customTransportConfigCallback = customTransportConfigCallback;
	}

	@Override
	public MultipleJGitEnvironmentRepository build(
			MultipleJGitEnvironmentProperties environmentProperties) throws Exception {
		if (this.connectionFactory.isPresent()) {
			HttpTransport.setConnectionFactory(this.connectionFactory.get());
			this.connectionFactory.get().addConfiguration(environmentProperties);
		}

		MultipleJGitEnvironmentRepository repository = new MultipleJGitEnvironmentRepository(
				this.environment, environmentProperties);
		repository.setTransportConfigCallback(this.customTransportConfigCallback
				.orElse(buildTransportConfigCallback(environmentProperties)));
		if (this.server.getDefaultLabel() != null) {
			repository.setDefaultLabel(this.server.getDefaultLabel());
		}
		return repository;
	}

	private TransportConfigCallback buildTransportConfigCallback(
			MultipleJGitEnvironmentProperties gitEnvironmentProperties) {
		if (gitEnvironmentProperties.isIgnoreLocalSshSettings()) {
			return new PropertiesBasedSshTransportConfigCallback(
					gitEnvironmentProperties);
		}
		return new FileBasedSshTransportConfigCallback(gitEnvironmentProperties);
	}

}