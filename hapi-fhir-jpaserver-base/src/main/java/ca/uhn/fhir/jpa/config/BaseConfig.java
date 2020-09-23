package ca.uhn.fhir.jpa.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.i18n.HapiLocalizer;
import ca.uhn.fhir.interceptor.api.IInterceptorBroadcaster;
import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.interceptor.executor.InterceptorService;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IDao;
import ca.uhn.fhir.jpa.batch.BatchJobsConfig;
import ca.uhn.fhir.jpa.batch.api.IBatchJobSubmitter;
import ca.uhn.fhir.jpa.batch.config.NonPersistedBatchConfigurer;
import ca.uhn.fhir.jpa.batch.svc.BatchJobSubmitterImpl;
import ca.uhn.fhir.jpa.binstore.BinaryAccessProvider;
import ca.uhn.fhir.jpa.binstore.BinaryStorageInterceptor;
import ca.uhn.fhir.jpa.bulk.api.IBulkDataExportSvc;
import ca.uhn.fhir.jpa.bulk.provider.BulkDataExportProvider;
import ca.uhn.fhir.jpa.bulk.svc.BulkDataExportSvcImpl;
import ca.uhn.fhir.jpa.dao.HistoryBuilder;
import ca.uhn.fhir.jpa.dao.HistoryBuilderFactory;
import ca.uhn.fhir.jpa.dao.ISearchBuilder;
import ca.uhn.fhir.jpa.dao.SearchBuilder;
import ca.uhn.fhir.jpa.dao.SearchBuilderFactory;
import ca.uhn.fhir.jpa.dao.index.DaoResourceLinkResolver;
import ca.uhn.fhir.jpa.dao.search.SearchBuilder2;
import ca.uhn.fhir.jpa.dao.search.sql.CoordsIndexTable;
import ca.uhn.fhir.jpa.dao.search.sql.DateIndexTable;
import ca.uhn.fhir.jpa.dao.search.sql.NumberIndexTable;
import ca.uhn.fhir.jpa.dao.search.sql.QuantityIndexTable;
import ca.uhn.fhir.jpa.dao.search.sql.ResourceIdPredicateBuilder3;
import ca.uhn.fhir.jpa.dao.search.sql.ResourceLinkIndexTable;
import ca.uhn.fhir.jpa.dao.search.sql.ResourceSqlTable;
import ca.uhn.fhir.jpa.dao.search.sql.SearchParamPresenceTable;
import ca.uhn.fhir.jpa.dao.search.sql.SearchSqlBuilder;
import ca.uhn.fhir.jpa.dao.search.sql.SqlBuilderFactory;
import ca.uhn.fhir.jpa.dao.search.sql.StringIndexTable;
import ca.uhn.fhir.jpa.dao.search.sql.TokenIndexTable;
import ca.uhn.fhir.jpa.dao.search.sql.UriIndexTable;
import ca.uhn.fhir.jpa.dao.tx.HapiTransactionService;
import ca.uhn.fhir.jpa.entity.Search;
import ca.uhn.fhir.jpa.graphql.JpaStorageServices;
import ca.uhn.fhir.jpa.interceptor.CascadingDeleteInterceptor;
import ca.uhn.fhir.jpa.interceptor.JpaConsentContextServices;
import ca.uhn.fhir.jpa.interceptor.OverridePathBasedReferentialIntegrityForDeletesInterceptor;
import ca.uhn.fhir.jpa.model.sched.ISchedulerService;
import ca.uhn.fhir.jpa.packages.IHapiPackageCacheManager;
import ca.uhn.fhir.jpa.packages.IPackageInstallerSvc;
import ca.uhn.fhir.jpa.packages.JpaPackageCache;
import ca.uhn.fhir.jpa.packages.NpmJpaValidationSupport;
import ca.uhn.fhir.jpa.packages.PackageInstallerSvcImpl;
import ca.uhn.fhir.jpa.partition.IPartitionLookupSvc;
import ca.uhn.fhir.jpa.partition.IRequestPartitionHelperSvc;
import ca.uhn.fhir.jpa.partition.PartitionLookupSvcImpl;
import ca.uhn.fhir.jpa.partition.PartitionManagementProvider;
import ca.uhn.fhir.jpa.partition.RequestPartitionHelperSvc;
import ca.uhn.fhir.jpa.provider.DiffProvider;
import ca.uhn.fhir.jpa.provider.SubscriptionTriggeringProvider;
import ca.uhn.fhir.jpa.provider.TerminologyUploaderProvider;
import ca.uhn.fhir.jpa.sched.AutowiringSpringBeanJobFactory;
import ca.uhn.fhir.jpa.sched.HapiSchedulerServiceImpl;
import ca.uhn.fhir.jpa.search.DatabaseBackedPagingProvider;
import ca.uhn.fhir.jpa.search.IStaleSearchDeletingSvc;
import ca.uhn.fhir.jpa.search.PersistedJpaBundleProvider;
import ca.uhn.fhir.jpa.search.PersistedJpaBundleProviderFactory;
import ca.uhn.fhir.jpa.search.PersistedJpaSearchFirstPageBundleProvider;
import ca.uhn.fhir.jpa.search.SearchCoordinatorSvcImpl;
import ca.uhn.fhir.jpa.search.StaleSearchDeletingSvcImpl;
import ca.uhn.fhir.jpa.search.cache.DatabaseSearchCacheSvcImpl;
import ca.uhn.fhir.jpa.search.cache.DatabaseSearchResultCacheSvcImpl;
import ca.uhn.fhir.jpa.search.cache.ISearchCacheSvc;
import ca.uhn.fhir.jpa.search.cache.ISearchResultCacheSvc;
import ca.uhn.fhir.jpa.search.reindex.IResourceReindexingSvc;
import ca.uhn.fhir.jpa.search.reindex.ResourceReindexingSvcImpl;
import ca.uhn.fhir.jpa.searchparam.config.SearchParamConfig;
import ca.uhn.fhir.jpa.searchparam.extractor.IResourceLinkResolver;
import ca.uhn.fhir.jpa.util.MemoryCacheService;
import ca.uhn.fhir.jpa.validation.ValidationSettings;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.consent.IConsentContextServices;
import ca.uhn.fhir.rest.server.interceptor.partition.RequestTenantPartitionInterceptor;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.utilities.cache.FilesystemPackageCacheManager;
import org.hl7.fhir.utilities.graphql.IGraphQLStorageServices;
import org.springframework.batch.core.configuration.annotation.BatchConfigurer;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.core.env.Environment;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.scheduling.concurrent.ScheduledExecutorFactoryBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;

import javax.annotation.Nullable;
import java.util.Date;

/*
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2020 University Health Network
 * %%
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
 * #L%
 */


@Configuration
@EnableJpaRepositories(basePackages = "ca.uhn.fhir.jpa.dao.data")
@ComponentScan(basePackages = "ca.uhn.fhir.jpa", excludeFilters = {
	@ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, value = BaseConfig.class),
	@ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, value = WebSocketConfigurer.class),
	@ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.test\\..*"),
	@ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Test.*"),
	@ComponentScan.Filter(type = FilterType.REGEX, pattern = "ca.uhn.fhir.jpa.subscription.*"),
	@ComponentScan.Filter(type = FilterType.REGEX, pattern = "ca.uhn.fhir.jpa.searchparam.*"),
	@ComponentScan.Filter(type = FilterType.REGEX, pattern = "ca.uhn.fhir.jpa.empi.*"),
	@ComponentScan.Filter(type = FilterType.REGEX, pattern = "ca.uhn.fhir.jpa.starter.*"),
	@ComponentScan.Filter(type = FilterType.REGEX, pattern = "ca.uhn.fhir.jpa.batch.*")
})
@Import({
	SearchParamConfig.class, BatchJobsConfig.class
})
@EnableBatchProcessing
public abstract class BaseConfig {

	public static final String JPA_VALIDATION_SUPPORT_CHAIN = "myJpaValidationSupportChain";
	public static final String JPA_VALIDATION_SUPPORT = "myJpaValidationSupport";
	public static final String TASK_EXECUTOR_NAME = "hapiJpaTaskExecutor";
	public static final String GRAPHQL_PROVIDER_NAME = "myGraphQLProvider";
	public static final String PERSISTED_JPA_BUNDLE_PROVIDER = "PersistedJpaBundleProvider";
	public static final String PERSISTED_JPA_BUNDLE_PROVIDER_BY_SEARCH = "PersistedJpaBundleProvider_BySearch";
	public static final String PERSISTED_JPA_SEARCH_FIRST_PAGE_BUNDLE_PROVIDER = "PersistedJpaSearchFirstPageBundleProvider";
	public static final String SEARCH_BUILDER = "SearchBuilder";
	public static final String HISTORY_BUILDER = "HistoryBuilder";
	private static final String HAPI_DEFAULT_SCHEDULER_GROUP = "HAPI";

	@Autowired
	protected Environment myEnv;

	@Autowired
	private DaoRegistry myDaoRegistry;

	@Bean
	public BatchConfigurer batchConfigurer() {
		return new NonPersistedBatchConfigurer();
	}

	@Bean("myDaoRegistry")
	public DaoRegistry daoRegistry() {
		return new DaoRegistry();
	}

	@Bean
	public DatabaseBackedPagingProvider databaseBackedPagingProvider() {
		return new DatabaseBackedPagingProvider();
	}

	@Bean
	public IBatchJobSubmitter batchJobSubmitter() {
		return new BatchJobSubmitterImpl();
	}

	@Lazy
	@Bean
	public CascadingDeleteInterceptor cascadingDeleteInterceptor(FhirContext theFhirContext, DaoRegistry theDaoRegistry, IInterceptorBroadcaster theInterceptorBroadcaster) {
		return new CascadingDeleteInterceptor(theFhirContext, theDaoRegistry, theInterceptorBroadcaster);
	}

	/**
	 * This method should be overridden to provide an actual completed
	 * bean, but it provides a partially completed entity manager
	 * factory with HAPI FHIR customizations
	 */
	protected LocalContainerEntityManagerFactoryBean entityManagerFactory() {
		LocalContainerEntityManagerFactoryBean retVal = new HapiFhirLocalContainerEntityManagerFactoryBean();
		configureEntityManagerFactory(retVal, fhirContext());
		return retVal;
	}

	public abstract FhirContext fhirContext();

	@Bean
	@Lazy
	public IGraphQLStorageServices graphqlStorageServices() {
		return new JpaStorageServices();
	}

	@Bean
	public ScheduledExecutorFactoryBean scheduledExecutorService() {
		ScheduledExecutorFactoryBean b = new ScheduledExecutorFactoryBean();
		b.setPoolSize(5);
		b.afterPropertiesSet();
		return b;
	}

	@Bean(name = "mySubscriptionTriggeringProvider")
	@Lazy
	public SubscriptionTriggeringProvider subscriptionTriggeringProvider() {
		return new SubscriptionTriggeringProvider();
	}

	@Bean(name = "myAttachmentBinaryAccessProvider")
	@Lazy
	public BinaryAccessProvider binaryAccessProvider() {
		return new BinaryAccessProvider();
	}

	@Bean(name = "myBinaryStorageInterceptor")
	@Lazy
	public BinaryStorageInterceptor binaryStorageInterceptor() {
		return new BinaryStorageInterceptor();
	}

	@Bean
	public MemoryCacheService memoryCacheService() {
		return new MemoryCacheService();
	}

	@Bean
	@Primary
	public IResourceLinkResolver daoResourceLinkResolver() {
		return new DaoResourceLinkResolver();
	}

	@Bean
	public IHapiPackageCacheManager packageCacheManager() {
		JpaPackageCache retVal = new JpaPackageCache();
		retVal.getPackageServers().clear();
		retVal.getPackageServers().add(FilesystemPackageCacheManager.PRIMARY_SERVER);
		retVal.getPackageServers().add(FilesystemPackageCacheManager.SECONDARY_SERVER);
		return retVal;
	}

	@Bean
	public NpmJpaValidationSupport npmJpaValidationSupport() {
		return new NpmJpaValidationSupport();
	}

	@Bean
	public ValidationSettings validationSettings() {
		return new ValidationSettings();
	}

	@Bean
	public ISearchCacheSvc searchCacheSvc() {
		return new DatabaseSearchCacheSvcImpl();
	}

	@Bean
	public ISearchResultCacheSvc searchResultCacheSvc() {
		return new DatabaseSearchResultCacheSvcImpl();
	}

	@Bean
	public ThreadPoolTaskExecutor searchCoordinatorThreadFactory() {
		final ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
		threadPoolTaskExecutor.setThreadNamePrefix("search_coord_");
		threadPoolTaskExecutor.initialize();
		return threadPoolTaskExecutor;
	}

	@Bean
	public TaskScheduler taskScheduler() {
		ConcurrentTaskScheduler retVal = new ConcurrentTaskScheduler();
		retVal.setConcurrentExecutor(scheduledExecutorService().getObject());
		retVal.setScheduledExecutor(scheduledExecutorService().getObject());
		return retVal;
	}

	@Bean(name = TASK_EXECUTOR_NAME)
	public AsyncTaskExecutor taskExecutor() {
		ConcurrentTaskScheduler retVal = new ConcurrentTaskScheduler();
		retVal.setConcurrentExecutor(scheduledExecutorService().getObject());
		retVal.setScheduledExecutor(scheduledExecutorService().getObject());
		return retVal;
	}

	@Bean
	public TaskExecutor jobLaunchingTaskExecutor() {
		ThreadPoolTaskExecutor asyncTaskExecutor = new ThreadPoolTaskExecutor();
		asyncTaskExecutor.setCorePoolSize(5);
		asyncTaskExecutor.setMaxPoolSize(10);
		asyncTaskExecutor.setQueueCapacity(500);
		asyncTaskExecutor.setThreadNamePrefix("JobLauncher-");
		asyncTaskExecutor.initialize();
		return asyncTaskExecutor;
	}


	@Bean
	public IResourceReindexingSvc resourceReindexingSvc() {
		return new ResourceReindexingSvcImpl();
	}

	@Bean
	public IStaleSearchDeletingSvc staleSearchDeletingSvc() {
		return new StaleSearchDeletingSvcImpl();
	}

	@Bean
	public HapiFhirHibernateJpaDialect hibernateJpaDialect() {
		return new HapiFhirHibernateJpaDialect(fhirContext().getLocalizer());
	}

	@Bean
	@Lazy
	public OverridePathBasedReferentialIntegrityForDeletesInterceptor overridePathBasedReferentialIntegrityForDeletesInterceptor() {
		return new OverridePathBasedReferentialIntegrityForDeletesInterceptor();
	}

	@Bean
	public IRequestPartitionHelperSvc requestPartitionHelperService() {
		return new RequestPartitionHelperSvc();
	}

	@Bean
	public PersistenceExceptionTranslationPostProcessor persistenceExceptionTranslationPostProcessor() {
		return new PersistenceExceptionTranslationPostProcessor();
	}

	@Bean
	public HapiTransactionService hapiTransactionService() {
		return new HapiTransactionService();
	}

	@Bean
	public IInterceptorService jpaInterceptorService() {
		return new InterceptorService();
	}

	/**
	 * Subclasses may override
	 */
	protected boolean isSupported(String theResourceType) {
		return myDaoRegistry.getResourceDaoOrNull(theResourceType) != null;
	}

	@Bean
	public IPackageInstallerSvc npmInstallerSvc() {
		return new PackageInstallerSvcImpl();
	}

	@Bean
	public IConsentContextServices consentContextServices() {
		return new JpaConsentContextServices();
	}

	@Bean
	@Lazy
	public DiffProvider diffProvider() {
		return new DiffProvider();
	}

	@Bean
	@Lazy
	public IPartitionLookupSvc partitionConfigSvc() {
		return new PartitionLookupSvcImpl();
	}

	@Bean
	@Lazy
	public PartitionManagementProvider partitionManagementProvider() {
		return new PartitionManagementProvider();
	}

	@Bean
	@Lazy
	public RequestTenantPartitionInterceptor requestTenantPartitionInterceptor() {
		return new RequestTenantPartitionInterceptor();
	}

	@Bean
	@Lazy
	public TerminologyUploaderProvider terminologyUploaderProvider() {
		return new TerminologyUploaderProvider();
	}

	@Bean
	public ISchedulerService schedulerService() {
		return new HapiSchedulerServiceImpl().setDefaultGroup(HAPI_DEFAULT_SCHEDULER_GROUP);
	}

	@Bean
	public AutowiringSpringBeanJobFactory schedulerJobFactory() {
		return new AutowiringSpringBeanJobFactory();
	}

	@Bean
	@Lazy
	public IBulkDataExportSvc bulkDataExportSvc() {
		return new BulkDataExportSvcImpl();
	}

	@Bean
	@Lazy
	public BulkDataExportProvider bulkDataExportProvider() {
		return new BulkDataExportProvider();
	}


	@Bean
	public PersistedJpaBundleProviderFactory persistedJpaBundleProviderFactory() {
		return new PersistedJpaBundleProviderFactory();
	}

	@Bean(name = PERSISTED_JPA_BUNDLE_PROVIDER)
	@Scope("prototype")
	public PersistedJpaBundleProvider persistedJpaBundleProvider(RequestDetails theRequest, String theUuid) {
		return new PersistedJpaBundleProvider(theRequest, theUuid);
	}

	@Bean(name = PERSISTED_JPA_BUNDLE_PROVIDER_BY_SEARCH)
	@Scope("prototype")
	public PersistedJpaBundleProvider persistedJpaBundleProvider(RequestDetails theRequest, Search theSearch) {
		return new PersistedJpaBundleProvider(theRequest, theSearch);
	}

	@Bean(name = PERSISTED_JPA_SEARCH_FIRST_PAGE_BUNDLE_PROVIDER)
	@Scope("prototype")
	public PersistedJpaSearchFirstPageBundleProvider persistedJpaSearchFirstPageBundleProvider(RequestDetails theRequest, Search theSearch, SearchCoordinatorSvcImpl.SearchTask theSearchTask, ISearchBuilder theSearchBuilder) {
		return new PersistedJpaSearchFirstPageBundleProvider(theSearch, theSearchTask, theSearchBuilder, theRequest);
	}

	@Bean
	public SearchBuilderFactory searchBuilderFactory() {
		return new SearchBuilderFactory();
	}

	@Bean
	public SqlBuilderFactory sqlBuilderFactory() {
		return new SqlBuilderFactory();
	}

	@Bean
	@Scope("prototype")
	public CoordsIndexTable indexTableCoords(SearchSqlBuilder theSearchBuilder) {
		return new CoordsIndexTable(theSearchBuilder);
	}

	@Bean
	@Scope("prototype")
	public DateIndexTable indexTableDate(SearchSqlBuilder theSearchBuilder) {
		return new DateIndexTable(theSearchBuilder);
	}

	@Bean
	@Scope("prototype")
	public NumberIndexTable indexTableNumber(SearchSqlBuilder theSearchBuilder) {
		return new NumberIndexTable(theSearchBuilder);
	}

	@Bean
	@Scope("prototype")
	public QuantityIndexTable indexTableQuantity(SearchSqlBuilder theSearchBuilder) {
		return new QuantityIndexTable(theSearchBuilder);
	}

	@Bean
	@Scope("prototype")
	public ResourceLinkIndexTable indexTableReference(SearchSqlBuilder theSearchBuilder) {
		return new ResourceLinkIndexTable(theSearchBuilder);
	}

	@Bean
	@Scope("prototype")
	public ResourceSqlTable indexTableResource(SearchSqlBuilder theSearchBuilder) {
		return new ResourceSqlTable(theSearchBuilder);
	}

	@Bean
	@Scope("prototype")
	public ResourceIdPredicateBuilder3 indexTableResourceId(SearchSqlBuilder theSearchBuilder) {
		return new ResourceIdPredicateBuilder3(theSearchBuilder);
	}

	@Bean
	@Scope("prototype")
	public SearchParamPresenceTable indexTableSearchParamPresence(SearchSqlBuilder theSearchBuilder) {
		return new SearchParamPresenceTable(theSearchBuilder);
	}

	@Bean
	@Scope("prototype")
	public StringIndexTable indexTableString(SearchSqlBuilder theSearchBuilder) {
		return new StringIndexTable(theSearchBuilder);
	}

	@Bean
	@Scope("prototype")
	public TokenIndexTable indexTableToken(SearchSqlBuilder theSearchBuilder) {
		return new TokenIndexTable(theSearchBuilder);
	}

	@Bean
	@Scope("prototype")
	public UriIndexTable indexTableUri(SearchSqlBuilder theSearchBuilder) {
		return new UriIndexTable(theSearchBuilder);
	}

	@Bean(name = SEARCH_BUILDER)
	@Scope("prototype")
	public ISearchBuilder persistedJpaSearchFirstPageBundleProvider(IDao theDao, String theResourceName, Class<? extends IBaseResource> theResourceType) {
		// FIXME: make configurable
		if (false) {
			return new SearchBuilder(theDao, theResourceName, theResourceType);
		}
		return new SearchBuilder2(theDao, theResourceName, theResourceType);
	}

	@Bean
	public HistoryBuilderFactory historyBuilderFactory() {
		return new HistoryBuilderFactory();
	}

	@Bean(name = HISTORY_BUILDER)
	@Scope("prototype")
	public HistoryBuilder persistedJpaSearchFirstPageBundleProvider(@Nullable String theResourceType, @Nullable Long theResourceId, @Nullable Date theRangeStartInclusive, @Nullable Date theRangeEndInclusive) {
		return new HistoryBuilder(theResourceType, theResourceId, theRangeStartInclusive, theRangeEndInclusive);
	}

	public static void configureEntityManagerFactory(LocalContainerEntityManagerFactoryBean theFactory, FhirContext theCtx) {
		theFactory.setJpaDialect(hibernateJpaDialect(theCtx.getLocalizer()));
		theFactory.setPackagesToScan("ca.uhn.fhir.jpa.model.entity", "ca.uhn.fhir.jpa.entity");
		theFactory.setPersistenceProvider(new HibernatePersistenceProvider());
	}

	private static HapiFhirHibernateJpaDialect hibernateJpaDialect(HapiLocalizer theLocalizer) {
		return new HapiFhirHibernateJpaDialect(theLocalizer);
	}
}
