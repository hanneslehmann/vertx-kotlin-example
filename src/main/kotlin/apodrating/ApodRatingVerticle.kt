package apodrating

import apodrating.model.ApodRatingConfiguration
import apodrating.model.Error
import apodrating.model.toJsonString
import apodrating.webapi.ApodQueryService
import apodrating.webapi.ApodQueryServiceImpl
import apodrating.webapi.RatingService
import apodrating.webapi.RatingServiceImpl
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.http.HttpServerOptions
import io.vertx.ext.jdbc.JDBCClient
import io.vertx.kotlin.core.net.pemKeyCertOptionsOf
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.ext.sql.executeAwait
import io.vertx.kotlin.ext.sql.getConnectionAwait
import io.vertx.reactivex.core.Vertx
import io.vertx.reactivex.core.http.HttpServer
import io.vertx.reactivex.core.http.HttpServerRequest
import io.vertx.reactivex.ext.web.RoutingContext
import io.vertx.reactivex.ext.web.api.contract.openapi3.OpenAPI3RouterFactory
import io.vertx.reactivex.ext.web.handler.StaticHandler
import io.vertx.serviceproxy.ServiceBinder
import kotlinx.coroutines.launch
import mu.KLogging
import org.apache.http.HttpStatus

/**
 * Implements our REST interface
 */
class ApodRatingVerticle : CoroutineVerticle() {

    companion object : KLogging()

    private lateinit var client: JDBCClient
    private lateinit var apiKey: String
    private lateinit var rxVertx: Vertx

    /**
     * - Start the verticle.
     * - Initialize Database
     * - Initialize vertx router
     * - Initialize webserver
     */
    override fun start(startFuture: Future<Void>?) {
        launch {
            val apodConfig = ApodRatingConfiguration(config)
            client = JDBCClient.createShared(vertx, apodConfig.toJdbcConfig())
            apiKey = apodConfig.nasaApiKey
            rxVertx = Vertx(vertx)
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
                "INSERT INTO RATING (VALUE, APOD_ID) VALUES 7, 2",
                "ALTER TABLE APOD ADD CONSTRAINT APOD_UNIQUE UNIQUE(DATE_STRING)"
            )
            with(ServiceBinder(vertx)) {
                this.setAddress("rating_service.apod").register(
                    RatingService::class.java,
                    RatingServiceImpl(rxVertx, config)
                )
                this.setAddress("apod_query_service.apod").register(
                    ApodQueryService::class.java,
                    ApodQueryServiceImpl(rxVertx, config)
                )
            }

            client.getConnectionAwait().use { connection -> statements.forEach { connection.executeAwait(it) } }
            val http11Server =
                OpenAPI3RouterFactory.rxCreate(rxVertx, "swagger.yaml").map {
                    it.mountServicesFromExtensions()
                    rxVertx.createHttpServer()
                        .requestHandler(createRouter(it))
                        .listen(apodConfig.port)
                }
            val http2Server =
                OpenAPI3RouterFactory.rxCreate(rxVertx, "swagger.yaml").map {
                    it.mountServicesFromExtensions()
                    rxVertx.createHttpServer(http2ServerOptions())
                        .requestHandler(createRouter(it))
                        .listen(apodConfig.h2Port)
                }
            Single.zip(listOf(http11Server, http2Server)) {
                it
                    .filterIsInstance<HttpServer>()
                    .map { eachHttpServer ->
                        logger.info { "port: ${eachHttpServer.actualPort()}" }
                        eachHttpServer.actualPort()
                    }
            }.doOnSuccess { startFuture?.complete() }
                .subscribeOn(Schedulers.io())
                .subscribe({ logger.info { "started ${it.size} servers" } })
                { logger.error { it } }
        }
    }

    private fun createRouter(routerFactory: OpenAPI3RouterFactory): Handler<HttpServerRequest> =
        routerFactory.apply {
            coroutineSecurityHandler(API_AUTH_KEY) { handleApiKeyValidation(it, apiKey) }
        }.router.apply {
            route(STATIC_PATH).handler(StaticHandler.create())
        }

    private fun OpenAPI3RouterFactory.coroutineSecurityHandler(
        securitySchemaName: String,
        function: suspend (RoutingContext) -> Unit
    ) =
        addSecurityHandler(securitySchemaName) {
            launch { function(it) }
        }

    private fun handleApiKeyValidation(ctx: RoutingContext, apiKey: String) =
        when (ctx.request().getHeader("X-API-KEY")) {
            apiKey -> ctx.next()
            else -> ctx.response().setStatusCode(HttpStatus.SC_UNAUTHORIZED).end(
                Error(
                    HttpStatus.SC_UNAUTHORIZED,
                    "Api key not valid"
                ).toJsonString()
            )
        }

    private fun http2ServerOptions(): HttpServerOptions = HttpServerOptions()
        .setKeyCertOptions(
            pemKeyCertOptionsOf(certPath = "tls/server-cert.pem", keyPath = "tls/server-key.pem")
        )
        .setSsl(true)
        .setUseAlpn(true)
}
