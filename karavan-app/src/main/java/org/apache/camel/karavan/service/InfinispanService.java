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

import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.configuration.ProfileManager;
import org.apache.camel.karavan.KaravanLifecycleBean;
import org.apache.camel.karavan.model.GroupedKey;
import org.apache.camel.karavan.model.Project;
import org.apache.camel.karavan.model.ProjectFile;
import org.apache.camel.karavan.model.ProjectStatus;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.infinispan.Cache;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.Search;
import org.infinispan.commons.api.BasicCache;
import org.infinispan.commons.api.CacheContainerAdmin;
import org.infinispan.commons.configuration.XMLStringConfiguration;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.SingleFileStoreConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.query.dsl.QueryFactory;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class InfinispanService {

    BasicCache<GroupedKey, Project> projects;

    BasicCache<GroupedKey, ProjectFile> files;

    BasicCache<GroupedKey, ProjectStatus> statuses;

    @Inject
    RemoteCacheManager cacheManager;

    @Inject
    GeneratorService generatorService;

    @ConfigProperty(name = "karavan.config.runtime")
    String runtime;

    private static final String CACHE_CONFIG = "<distributed-cache name=\"%s\">"
            + " <encoding media-type=\"application/x-protostream\"/>"
            + " <groups enabled=\"true\"/>"
            + "</distributed-cache>";

    private static final Logger LOGGER = Logger.getLogger(KaravanLifecycleBean.class.getName());

    void onStart(@Observes StartupEvent ev) {
        if (cacheManager == null) {
            LOGGER.info("InfinispanService is starting in local mode");
            GlobalConfigurationBuilder global = GlobalConfigurationBuilder.defaultClusteredBuilder();
            global.globalState().enable().persistentLocation("karavan-data");
            DefaultCacheManager cacheManager = new DefaultCacheManager(global.build());
            ConfigurationBuilder builder = new ConfigurationBuilder();
            builder.clustering()
                    .cacheMode(CacheMode.LOCAL)
                    .persistence().passivation(false)
                    .addStore(SingleFileStoreConfigurationBuilder.class)
                    .shared(false)
                    .preload(true)
                    .fetchPersistentState(true);
            projects = cacheManager.administration().withFlags(CacheContainerAdmin.AdminFlag.VOLATILE).getOrCreateCache(Project.CACHE, builder.build());
            files = cacheManager.administration().withFlags(CacheContainerAdmin.AdminFlag.VOLATILE).getOrCreateCache(ProjectFile.CACHE, builder.build());
            statuses = cacheManager.administration().withFlags(CacheContainerAdmin.AdminFlag.VOLATILE).getOrCreateCache(ProjectStatus.CACHE, builder.build());
        } else {
            LOGGER.info("InfinispanService is starting in remote mode");
            projects = cacheManager.administration().getOrCreateCache(Project.CACHE, new XMLStringConfiguration(String.format(CACHE_CONFIG, Project.CACHE)));
            files = cacheManager.administration().getOrCreateCache(ProjectFile.CACHE, new XMLStringConfiguration(String.format(CACHE_CONFIG, ProjectFile.CACHE)));
            statuses = cacheManager.administration().getOrCreateCache(ProjectFile.CACHE, new XMLStringConfiguration(String.format(CACHE_CONFIG, ProjectStatus.CACHE)));
        }
        if (ProfileManager.getLaunchMode().isDevOrTest()){
//            generateDevProjects();
        }
    }

    public List<Project> getProjects() {
        return projects.values().stream().collect(Collectors.toList());
    }

    public void saveProject(Project project) {
        GroupedKey key = GroupedKey.create(project.getProjectId(), project.getProjectId());
        boolean isNew = !projects.containsKey(key);
        project.setRuntime(Project.CamelRuntime.valueOf(runtime));
        projects.put(key, project);
        if (isNew){
            String filename = "application.properties";
            String code = generatorService.getDefaultApplicationProperties(project);
            files.put(new GroupedKey(project.getProjectId(), filename), new ProjectFile(filename, code, project.getProjectId()));
        }
    }

    public List<ProjectFile> getProjectFiles(String projectId) {
        if (cacheManager == null) {
            QueryFactory queryFactory = org.infinispan.query.Search.getQueryFactory((Cache<?, ?>) files);
            return queryFactory.<ProjectFile>create("FROM org.apache.camel.karavan.model.ProjectFile WHERE projectId = :projectId")
                    .setParameter("projectId", projectId)
                    .execute().list();
        } else {
            QueryFactory queryFactory = Search.getQueryFactory((RemoteCache<?, ?>) files);
            return queryFactory.<ProjectFile>create("FROM karavan.ProjectFile WHERE projectId = :projectId")
                    .setParameter("projectId", projectId)
                    .execute().list();
        }
    }
    public void saveProjectFile(ProjectFile file) {
        files.put(GroupedKey.create(file.getProjectId(), file.getName()), file);
    }

    public void saveProjectFiles(Map<GroupedKey, ProjectFile> f) {
        files.putAll(f);
    }

    public void deleteProject(String project) {
        projects.remove(GroupedKey.create(project, project));
    }

    public void deleteProjectFile(String project, String filename) {
        files.remove(GroupedKey.create(project, filename));
    }

    public Project getProject(String project) {
        return projects.get(GroupedKey.create(project, project));
    }

    public ProjectStatus getProjectStatus(String projectId) {
        return statuses.get(GroupedKey.create(projectId, projectId));
    }

    public void saveProjectStatus(ProjectStatus status) {
        statuses.put(GroupedKey.create(status.getProjectId(), status.getProjectId()), status);
    }

    private void generateDevProjects(){
        for (int i = 0; i < 10; i++){
            String projectId = "parcel-demo" + i;
            Project p = new Project(projectId, "Demo project " + i, "Demo project placeholder for UI testing purposes", Project.CamelRuntime.valueOf(runtime));
            this.saveProject(p);

            files.put(GroupedKey.create(p.getProjectId(),"new-parcels.yaml"), new ProjectFile("new-parcels.yaml", "flows:", p.getProjectId()));
            files.put(GroupedKey.create(p.getProjectId(),"parcel-confirmation.yaml"), new ProjectFile("parcel-confirmation.yaml", "rest:", p.getProjectId()));
            files.put(GroupedKey.create(p.getProjectId(),"CustomProcessor.java"), new ProjectFile("CustomProcessor.java", "import org.apache.camel.BindToRegistry;\n" +
                    "import org.apache.camel.Exchange;\n" +
                    "import org.apache.camel.Processor;\n" +
                    "\n" +
                    "@BindToRegistry(\"myBean\")\n" +
                    "public class CustomProcessor implements Processor {\n" +
                    "\n" +
                    "  public void process(Exchange exchange) throws Exception {\n" +
                    "      exchange.getIn().setBody(\"Hello world\");\n" +
                    "  }\n" +
                    "}", p.getProjectId()));
        }
    }
}
