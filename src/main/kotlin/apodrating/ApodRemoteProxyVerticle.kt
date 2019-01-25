package apodrating

import apodrating.model.Apod
import apodrating.model.asApod
import apodrating.model.emptyApod
import apodrating.model.isEmpty
import apodrating.model.toJsonObject
import io.reactivex.Single
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.circuitbreaker.CircuitBreakerOptions
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.reactivex.circuitbreaker.CircuitBreaker
import io.vertx.reactivex.core.Vertx
import io.vertx.reactivex.ext.web.client.WebClient
import io.vertx.reactivex.ext.web.client.predicate.ResponsePredicate
import io.vertx.reactivex.ext.web.codec.BodyCodec
import kotlinx.coroutines.launch
import mu.KLogging
import org.apache.http.HttpStatus
import org.ehcache.Cache
import org.ehcache.config.builders.CacheConfigurationBuilder
import org.ehcache.config.builders.CacheManagerBuilder
import org.ehcache.config.builders.ResourcePoolsBuilder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Implementation of a remote proxy for the APOD API.
 */
class ApodRemoteProxyVerticle : CoroutineVerticle() {

    companion object : KLogging()

    private lateinit var rxVertx: Vertx
    private lateinit var circuitBreaker: CircuitBreaker
    private lateinit var webClient: WebClient
    private lateinit var apodCache: Cache<String, Apod>

    /**
     * - Start the verticle.
     * - Initialize Database
     * - Initialize vertx router
     * - Initialize webserver
     */
    override fun start(startFuture: Future<Void>?) {
        rxVertx = Vertx(vertx)
        launch {
            startUp(startFuture)
        }
    }

    private suspend fun startUp(startFuture: Future<Void>?) {
        launch {
            CacheManagerBuilder.newCacheManagerBuilder()
                .withCache(
                    CACHE_ALIAS,
                    CacheConfigurationBuilder
                        .newCacheConfigurationBuilder(
                            String::class.java,
                            Apod::class.java,
                            ResourcePoolsBuilder.heap(HEAP_POOL_SIZE)
                        )
                ).build()
                .apply {
                    this.init()
                    apodCache = this.getCache(
                        CACHE_ALIAS,
                        String::class.java,
                        Apod::class.java
                    )
                }
        }.join()

        launch {
            circuitBreaker = CircuitBreaker.create(
                CIRCUIT_BREAKER_NAME, rxVertx,
                CircuitBreakerOptions(
                    maxFailures = 3, // number of failures before opening the circuit
                    timeout = 2000L, // consider a failure if the operation does not succeed in time
                    fallbackOnFailure = true, // do we call the fallback on failure
                    resetTimeout = 1000, // time spent in open state before attempting to re-try
                    maxRetries = 3 // the number of times the circuit breaker tries to redo the operation before failing
                )
            )
        }.join()

        launch {
            webClient = WebClient.create(rxVertx)
        }.join()

        rxVertx.eventBus()
            .consumer<JsonObject>(EVENTBUS_ADDRESS) { message ->
                val id: String = message.body().getString("id")
                val dateString: String = message.body().getString("date")
                val nasaApiKey: String = message.body().getString("nasaApiKey")
                performApodQuery(id, dateString, nasaApiKey).subscribe({
                    when {
                        it == null || it.isEmpty() -> message.fail(
                            HttpStatus.SC_SERVICE_UNAVAILABLE,
                            "APOD API is temporarily not available"
                        )
                        else -> message.reply(it.toJsonObject())
                    }
                }) {
                    logger.error { it }
                    message.fail(HttpStatus.SC_INTERNAL_SERVER_ERROR, "Server error")
                }
            }
        startFuture?.tryComplete()
        logger.info { "Proxy started" }
    }

    /**
     * Perform a query against the NASA api
     *
     *  @param date the date string
     *  @param nasaApiKey the api key provided by the client
     */
    private fun performApodQuery(id: String, date: String, nasaApiKey: String): Single<Apod> = when {
        apodCache.containsKey(date) -> Single.just(apodCache.get(date))
            .doOnSuccess { ApodRatingVerticle.logger.info { "cache hit: $id" } }
        else -> with(AtomicInteger()) {
            circuitBreaker.rxExecuteCommandWithFallback<Apod>({ future ->
                if (this.getAndIncrement() > 0)
                    logger.info { "number of retries: ${this.get() - 1}" }
                rxSendGet(date, nasaApiKey, id)
                    .doOnSuccess {
                        if (!apodCache.containsKey(date)) {
                            apodCache.put(date, it)
                            ApodRatingVerticle.logger.info { "added entry to cache: ${it.id}" }
                        }
                    }.subscribe({ future.complete(it) }) { future.fail(it) }
            }) {
                logger.error { "Circuit opened. Error: $it - message: ${it.message}" }
                emptyApod()
            }
        }

    }

    /**
     * Create a web client instance for querying the NASA api.
     *
     * @param date the date String
     * @param nasaApiKey  the NASA api key
     * @param id the apod id
     */
    private fun rxSendGet(date: String, nasaApiKey: String, id: String): Single<Apod> =
        webClient.getAbs("https://api.nasa.gov")
            .uri("/planetary/apod")
            .addQueryParam("date", date)
            .addQueryParam("api_key", nasaApiKey)
            .addQueryParam("hd", true.toString())
            .expect(ResponsePredicate.SC_SUCCESS)
            .expect(ResponsePredicate.JSON)
            .`as`(BodyCodec.jsonObject())
            .rxSend()
            .map { it.body() }
            .map { asApod(id, it) }
}