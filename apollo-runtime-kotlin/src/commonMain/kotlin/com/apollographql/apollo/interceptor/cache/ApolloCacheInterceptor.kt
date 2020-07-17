package com.apollographql.apollo.interceptor.cache

import com.apollographql.apollo.api.ApolloExperimental
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Response
import com.apollographql.apollo.api.Response.Companion.builder
import com.apollographql.apollo.api.ResponseField
import com.apollographql.apollo.api.internal.NoOpResolveDelegate
import com.apollographql.apollo.cache.CacheExecutionContext
import com.apollographql.apollo.cache.CacheHeaders
import com.apollographql.apollo.cache.normalized.CacheKey
import com.apollographql.apollo.cache.normalized.CacheKeyResolver
import com.apollographql.apollo.cache.normalized.Record
import com.apollographql.apollo.cache.normalized.internal.CacheFieldValueResolver
import com.apollographql.apollo.cache.normalized.internal.CacheKeyBuilder
import com.apollographql.apollo.cache.normalized.internal.ReadableStore
import com.apollographql.apollo.cache.normalized.internal.RealCacheKeyBuilder
import com.apollographql.apollo.cache.normalized.internal.ResponseNormalizer
import com.apollographql.apollo.cache.normalized.simple.SimpleNormalizedCache
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import com.apollographql.apollo.interceptor.ApolloRequest
import com.apollographql.apollo.interceptor.ApolloRequestInterceptor
import com.apollographql.apollo.interceptor.ApolloResponse
import com.apollographql.apollo.internal.response.RealResponseReader
import com.apollographql.apollo.internal.response.RealResponseWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

@ApolloExperimental
class ApolloCacheInterceptor : ApolloRequestInterceptor {
  val normalizedCache = SimpleNormalizedCache()
  val readableStore = object : ReadableStore {
    override fun read(key: String, cacheHeaders: CacheHeaders): Record? {
      return normalizedCache.loadRecord(key, cacheHeaders)
    }

    override fun read(keys: Collection<String>, cacheHeaders: CacheHeaders): Collection<Record> {
      return keys.mapNotNull { normalizedCache.loadRecord(it, cacheHeaders) }
    }
  }

  override fun <D : Operation.Data> intercept(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    return flow {
      val response = readFromCache(request)
      if (response != null) {
        emit(ApolloResponse(requestUuid = request.requestUuid, response = response, executionContext = request.executionContext + CacheExecutionContext(true)))
      } else {
        chain.proceed(request).collect {
          if (it.response.data != null) {
            writeToCache(request, it.response.data!!)
          }
          emit(it.copy(executionContext = it.executionContext + CacheExecutionContext(false)))
        }
      }
    }
  }

  private fun <D : Operation.Data> writeToCache(request: ApolloRequest<D>, data: D) {
    val operation = request.operation
    val writer = RealResponseWriter(operation.variables(), request.scalarTypeAdapters)
    data.marshaller().marshal(writer)

    val responseNormalizer = object : ResponseNormalizer<Map<String, Any>?>() {
      override fun resolveCacheKey(field: ResponseField,
                                   record: Map<String, Any>?): CacheKey {
        return CacheKeyResolver.DEFAULT.fromFieldRecordSet(field, record!!)
      }

      override fun cacheKeyBuilder(): CacheKeyBuilder {
        return RealCacheKeyBuilder()
      }
    }

    responseNormalizer.willResolveRootQuery(operation);
    writer.resolveFields(responseNormalizer)
    normalizedCache.merge(responseNormalizer.records()?.filterNotNull() ?: emptySet(), CacheHeaders.NONE)
  }

  private fun <D : Operation.Data> readFromCache(request: ApolloRequest<D>): Response<D>? {
    val operation = request.operation
    val rootRecord = normalizedCache.loadRecord(CacheKeyResolver.rootKeyForOperation(operation).key, CacheHeaders.NONE) ?: return null

    val fieldValueResolver = CacheFieldValueResolver(readableStore,
        operation.variables(),
        CacheKeyResolver.DEFAULT,
        CacheHeaders.NONE,
        RealCacheKeyBuilder()
    )
    val responseReader = RealResponseReader(operation.variables(), rootRecord, fieldValueResolver, request.scalarTypeAdapters, NoOpResolveDelegate())
    val data = operation.wrapData(operation.responseFieldMapper().map(responseReader))
    return builder<D>(operation)
        .data(data)
        .build()
  }
}