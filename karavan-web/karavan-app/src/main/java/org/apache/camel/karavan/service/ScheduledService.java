/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.karavan.service;

import io.quarkus.scheduler.Scheduled;
import org.apache.camel.karavan.docker.DockerService;
import org.apache.camel.karavan.infinispan.InfinispanService;
import org.apache.camel.karavan.shared.ConfigService;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@ApplicationScoped
public class ScheduledService {

    private static final Logger LOGGER = Logger.getLogger(ScheduledService.class.getName());

    @Inject
    DockerService dockerService;

    @Inject
    ProjectService projectService;

    @Inject
    CamelService camelService;

    @Inject
    InfinispanService infinispanService;

    @Scheduled(every = "{karavan.container.statistics.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void collectContainersStats() {
        dockerService.collectContainersStats();
    }

    @Scheduled(every = "{karavan.container.infinispan.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void checkInfinispanHealth() {
        if (!infinispanService.isReady()) {
            dockerService.checkInfinispanHealth();
        }
    }

    @Scheduled(every = "{karavan.camel.status.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void collectCamelStatuses() {
        LOGGER.info("Collect info statuses");
        if (ConfigService.inKubernetes()) {
            // collect Camel statuses
            camelService.collectCamelStatuses();
        }
    }

    @Scheduled(every = "{karavan.git.pull.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void pullCommitsFromGit() {
        projectService.pullCommits();
    }

}
