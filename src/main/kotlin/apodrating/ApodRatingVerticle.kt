package apodrating

import apodrating.model.Apod
import apodrating.model.ApodRequest
import apodrating.model.Error
import apodrating.model.Rating
import apodrating.model.RatingRequest
import apodrating.model.emptyApod
import apodrating.model.isEmpty
import apodrating.model.toJsonString
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonObject
import io.vertx.ext.jdbc.JDBCClient
import io.vertx.kotlin.circuitbreaker.CircuitBreakerOptions
import io.vertx.kotlin.core.json.JsonArray
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.net.PemKeyCertOptions
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.ext.sql.executeAwait
import io.vertx.kotlin.ext.sql.getConnectionAwait
import io.vertx.kotlin.ext.sql.queryAwait
import io.vertx.kotlin.ext.sql.queryWithParamsAwait
import io.vertx.kotlin.ext.sql.updateWithParamsAwait
import io.vertx.reactivex.circuitbreaker.CircuitBreaker
import io.vertx.reactivex.core.Vertx
import io.vertx.reactivex.core.http.HttpServer
import io.vertx.reactivex.core.http.HttpServerRequest
import io.vertx.reactivex.ext.web.RoutingContext
import io.vertx.reactivex.ext.web.api.contract.openapi3.OpenAPI3RouterFactory
import io.vertx.reactivex.ext.web.client.WebClient
import io.vertx.reactivex.ext.web.client.predicate.ResponsePredicate
import io.vertx.reactivex.ext.web.codec.BodyCodec
import io.vertx.reactivex.ext.web.handler.StaticHandler
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KLogging
import org.ehcache.Cache
import org.ehcache.config.builders.CacheConfigurationBuilder
import org.ehcache.config.builders.CacheManagerBuilder
import org.ehcache.config.builders.ResourcePoolsBuilder
import java.util.concurrent.atomic.AtomicInteger

class ApodRatingVerticle : CoroutineVerticle() {

    companion object : KLogging()

    private lateinit var client: JDBCClient
    private lateinit var apiKey: String
    private lateinit var apodCache: Cache<String, Apod>
    private lateinit var rxVertx: Vertx
    private lateinit var circuitBreaker: CircuitBreaker

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
        val startupTime = System.currentTimeMillis()
        val apodConfig = ApodRatingConfiguration(config)
        client = JDBCClient.createShared(vertx, apodConfig.toJsonObject())
        apiKey = apodConfig.nasaApiKey

        CacheManagerBuilder.newCacheManagerBuilder()
            .withCache(
                "apodCache",
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        String::class.java,
                        Apod::class.java,
                        ResourcePoolsBuilder.heap(10)
                    )
            ).build()
            .apply {
                this.init()
                apodCache = this.getCache(
                    "apodCache",
                    String::class.java,
                    Apod::class.java
                )
            }

        launch {
            circuitBreaker = CircuitBreaker.create(
                "apod-circuit-breaker", rxVertx,
                CircuitBreakerOptions(
                    maxFailures = 3, // number of failures before opening the circuit
                    timeout = 2000L, // consider a failure if the operation does not succeed in time
                    fallbackOnFailure = true, // do we call the fallback on failure
                    resetTimeout = 1000, // time spent in open state before attempting to re-try
                    maxRetries = 3 // the number of times the circuit breaker tries to redo the operation before failing
                )
            )
        }.join()

        // Populate database
        val statements = listOf(
            "CREATE TABLE APOD (ID INTEGER IDENTITY PRIMARY KEY, DATE_STRING VARCHAR(16))",
            "CREATE TABLE RATING (ID INTEGER IDENTITY PRIMARY KEY, VALUE INTEGER, APOD_ID INTEGER, FOREIGN KEY (APOD_ID) REFERENCES APOD(ID))",
            "INSERT INTO APOD (DATE_STRING) VALUES '2019-01-10'",
            "INSERT INTO APOD (DATE_STRING) VALUES '2018-07-01'",
            "INSERT INTO APOD (DATE_STRING) VALUES '2017-01-01'",
            "INSERT INTO RATING (VALUE, APOD_ID) VALUES 8, 0",
            "INSERT INTO RATING (VALUE, APOD_ID) VALUES 5, 1",
            "INSERT INTO RATING (VALUE, APOD_ID) VALUES 7, 2",
            "INSERT INTO RATING (VALUE, APOD_ID) VALUES 8, 0",
            "INSERT INTO RATING (VALUE, APOD_ID) VALUES 5, 1",
            "INSERT INTO RATING (VALUE, APOD_ID) VALUES 7, 2"

        )

        client.getConnectionAwait().use { connection -> statements.forEach { connection.executeAwait(it) } }

        val http11Server =
            OpenAPI3RouterFactory.rxCreate(rxVertx, "swagger.yaml").map {
                rxVertx.createHttpServer()
                    .requestHandler(createRouter(it))
                    .listen(apodConfig.port)
            }

        val http2Server =
            OpenAPI3RouterFactory.rxCreate(rxVertx, "swagger.yaml").map {
                rxVertx.createHttpServer(http2ServerOptions())
                    .requestHandler(createRouter(it))
                    .listen(apodConfig.h2Port)
            }

        Single.zip<HttpServer, List<Int>>(listOf(http11Server, http2Server)) { servers ->
            servers
                .map { eachServer -> eachServer as HttpServer }
                .map { eachHttpServer ->
                    logger.info { "port: ${eachHttpServer.actualPort()}" }
                    eachHttpServer.actualPort()
                }
        }.doOnSuccess { startFuture?.complete() }
            .subscribeOn(Schedulers.io())
            .subscribe({ logger.info { "started ${it.size} servers in ${System.currentTimeMillis() - startupTime}ms" } })
            { logger.error { it } }
    }

    /**
     * Options necessary for creating an http2 server
     */
    private fun http2ServerOptions(): HttpServerOptions = HttpServerOptions()
        .setKeyCertOptions(PemKeyCertOptions().setCertPath("tls/server-cert.pem").setKeyPath("tls/server-key.pem"))
        .setSsl(true)
        .setUseAlpn(true)

    /**
     * Creates a router for our web servers.
     * @param routerFactory the router factory
     *
     */
    private fun createRouter(routerFactory: OpenAPI3RouterFactory): Handler<HttpServerRequest> =
        routerFactory.apply {
            coroutineHandler("putRating") { handlePutApod(it) }
            coroutineHandler("getRating") { handleGetRating(it) }
            coroutineHandler("getApodForDate") { handleGetApodForDate(it) }
            coroutineHandler("getApods") { handleGetApods(it) }
            coroutineHandler("postApod") { handlePostApod(it) }
            coroutineSecurityHandler("ApiKeyAuth") { handleApiKeyValidation(it) }
        }.router.apply {
            route("/ui/*").handler(StaticHandler.create())
        }

    /**
     * Validate the api and tell the router to route this context to the next matching route.
     */
    private fun handleApiKeyValidation(ctx: RoutingContext) = when (apiKey) {
        ctx.request().getHeader("X-API-KEY") -> ctx.next()
        else -> ctx.response().setStatusCode(401).end(Error(401, "Api key not valid").toJsonString())
    }

    /**
     * Handle a POST request for a single APOD identified by a date string.
     *
     * @param ctx the vertx routing context
     */
    private suspend fun handlePostApod(ctx: RoutingContext) {
        val apodRequest = ApodRequest(ctx.bodyAsJson)
        val apiKeyHeader = ctx.request().getHeader("X-API-KEY")
        val resultSet = client.queryWithParamsAwait("SELECT DATE_STRING FROM APOD WHERE DATE_STRING=?",
            json { array(apodRequest.dateString) })
        when {
            resultSet.rows.size == 0 -> {
                val updateResult = client.updateWithParamsAwait(
                    "INSERT INTO APOD (DATE_STRING) VALUES ?",
                    json { array(apodRequest.dateString) })
                val newId = updateResult.keys.get<Int>(0)
                performApodQuery(newId.toString(), apodRequest.dateString, apiKeyHeader)
                    .subscribe({
                        ctx.response().setStatusCode(201)
                            .putHeader("Location", "/apod/$newId")
                            .end()
                    }) { error ->
                        ctx.response().setStatusCode(500)
                            .end(Error(500, "Could not create apod entry. ${error.message}").toJsonString())
                    }
            }
            else -> ctx.response().setStatusCode(409).end(Error(409, "Entry already exists").toJsonString())
        }
    }

    /**
     * Handle a GET request for all APODs in our database.
     *
     * @param ctx the vertx routing context
     */
    private suspend fun handleGetApods(ctx: RoutingContext) {
        val apiKeyHeader = ctx.request().getHeader("X-API-KEY")
        val result = client.queryAwait("SELECT ID, DATE_STRING FROM APOD ")
        var apods: List<JsonObject>? = null
        when {
            result.rows.size > 0 -> apods = result.rows
            result.rows.size == 0 -> ctx.response().setStatusCode(200).end(JsonArray().encode())
            else -> ctx.response().setStatusCode(400).end()
        }
        apods?.apply {
            val singleApods = this
                .map {
                    runBlocking {
                        performApodQuery(
                            it.getInteger("ID").toString(),
                            it.getString("DATE_STRING"),
                            apiKeyHeader
                        )
                    }
                }
                .toList()
            Single.zip<Apod, List<Apod>>(singleApods) { emittedApodsAsJsonArray ->
                emittedApodsAsJsonArray
                    .map { it as Apod }
                    .filter { !it.isEmpty() }
            }.subscribeOn(Schedulers.io())
                .subscribe({ ctx.response().setStatusCode(200).end(JsonArray(it).encode()) })
                { ctx.response().setStatusCode(500).end(Error(500, "${it.message}").toJsonString()) }
        }
    }

    /**
     * Handle a GET request for a single APOD identified by a date string.
     *
     * @param ctx the vertx routing context
     */
    private suspend fun handleGetApodForDate(ctx: RoutingContext) {
        val apodId = ctx.pathParam("apodId")
        val apiKeyHeader = ctx.request().getHeader("X-API-KEY")
        val result = client.queryWithParamsAwait("SELECT ID, DATE_STRING FROM APOD WHERE ID=?", json { array(apodId) })

        when (result.rows.size) {
            1 -> result.rows[0]?.apply {
                performApodQuery(this.getInteger("ID").toString(), this.getString("DATE_STRING"), apiKeyHeader)
                    .subscribe({
                        when {
                            it == null || it.isEmpty() -> ctx.response().setStatusCode(503).end()
                            else -> ctx.response().setStatusCode(200).end(it.toJsonString())
                        }
                    }) {
                        logger.error { it }
                        ctx.response().setStatusCode(500).end()
                    }
            }
            0 -> ctx.response().setStatusCode(404).end()
            else -> ctx.response().setStatusCode(400).end()
        }
    }

    /**
     * Perform a query against the NASA api
     *
     *  @param date the date string
     *  @param nasaApiKey the api key provided by the client
     */
    private fun performApodQuery(
        id: String,
        date: String,
        nasaApiKey: String
    ): Single<Apod> = when {
        apodCache.containsKey(date) -> Single.just(apodCache.get(date)).doOnSuccess { logger.info { "cache hit: $id" } }
        else -> {
            val counter = AtomicInteger()
            circuitBreaker.rxExecuteCommandWithFallback<Apod>({ future ->
                if (counter.getAndIncrement() > 0) logger.info { "number of retries: ${counter.get() - 1}" }
                createApodWebClient(date, nasaApiKey, id)
                    .subscribe({ future.complete(it) }) { future.fail(it) }
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
    private fun createApodWebClient(
        date: String,
        nasaApiKey: String,
        id: String
    ): Single<Apod> = WebClient.create(rxVertx).getAbs("https://api.nasa.gov")
        .uri("/planetary/apod")
        .addQueryParam("date", date)
        .addQueryParam("api_key", nasaApiKey)
        .addQueryParam("hd", true.toString())
        .expect(ResponsePredicate.SC_SUCCESS)
        .expect(ResponsePredicate.JSON)
        .`as`(BodyCodec.jsonObject())
        .rxSend()
        .map { it.body() }
        .map { Apod(id, it) }
        .doOnSuccess {
            if (!apodCache.containsKey(date)) {
                apodCache.put(date, it)
                logger.info { "added entry to cache: ${it.id}" }
            }
        }

    /**
     * Serve a POST request. Rate an apod
     *
     * @param ctx the vertx RoutingContext
     */
    private suspend fun handlePutApod(ctx: RoutingContext) {
        val apod = ctx.pathParam("apodId")
        val rating = RatingRequest(ctx.bodyAsJson)
        val result = client.queryWithParamsAwait("SELECT ID FROM APOD WHERE ID=?", json { array(apod) })
        when {
            result.rows.size == 1 -> {
                client.updateWithParamsAwait(
                    "INSERT INTO RATING (VALUE, APOD_ID) VALUES ?, ?",
                    json { array(rating.rating, apod) })
                ctx.response().setStatusCode(204).end()
            }
            else -> ctx.response().setStatusCode(404).end(Error(404, "Apod does not exist.").toJsonString())
        }
    }

    /**
     * Serve a GET request. Get the current rating of an apod
     *
     * @param ctx the vertx RoutingContext
     */
    private suspend fun handleGetRating(ctx: RoutingContext) {
        val apod = ctx.pathParam("apodId")
        val result = client.queryWithParamsAwait(
            "SELECT APOD_ID, AVG(VALUE) AS VALUE FROM RATING WHERE APOD_ID=? GROUP BY APOD_ID",
            json { array(apod) })
        when (result.rows.size) {
            1 -> ctx.response().end(Rating(result).toJsonString())
            0 -> ctx.response().setStatusCode(404).end(
                Error(
                    404,
                    "A rating for this Apod entry does not exist"
                ).toJsonString()
            )
            else -> ctx.response().setStatusCode(500).end(Error(500, "Server error").toJsonString())
        }
    }

    /**
     * An extension function for simplifying coroutines usage with Vert.x Web router factories.
     *
     * The arrow operator "->" denotes the function type where it separates parameters types from result type.
     *
     * To find out what this function really does, I suggest you inline it temporarily.
     *
     * @param operationId the operationId from swagger
     * @param function the function that is being executed suspendedly
     */
    private fun OpenAPI3RouterFactory.coroutineHandler(
        operationId: String,
        function: suspend (RoutingContext) -> Unit
    ) =
        addHandlerByOperationId(operationId) {
            launch {
                try {
                    function(it)
                } catch (exception: Exception) {
                    it.fail(exception)
                }
            }
        }

    /**
     * An extension function to handle security
     */
    private fun OpenAPI3RouterFactory.coroutineSecurityHandler(
        securitySchemaName: String,
        function: suspend (RoutingContext) -> Unit
    ) =
        addSecurityHandler(securitySchemaName) {
            launch {
                try {
                    function(it)
                } catch (exception: Exception) {
                    it.fail(exception)
                }
            }
        }
}