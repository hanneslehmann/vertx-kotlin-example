package apodrating.webapi

import apodrating.model.ApodRatingConfiguration
import apodrating.model.Rating
import apodrating.model.asRatingRequest
import apodrating.model.toJsonObject
import io.reactivex.Maybe
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.api.OperationRequest
import io.vertx.ext.web.api.OperationResponse
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.reactivex.core.Vertx
import io.vertx.reactivex.ext.jdbc.JDBCClient
import io.vertx.serviceproxy.ServiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mu.KLogging
import org.apache.http.HttpStatus

/**
 * Implementation of all APOD rating related queries.
 *
 * @see https://vertx.io/docs/vertx-web-api-service/java/
 */
class RatingServiceImpl(
    val vertx: Vertx,
    val config: JsonObject,
    private val apodConfig: ApodRatingConfiguration = ApodRatingConfiguration(config),
    private val jdbc: JDBCClient = JDBCClient.createShared(vertx, apodConfig.toJdbcConfig())
) : RatingService {

    companion object : KLogging()

    /**
     * Get a rating for an apod
     */
    override fun getRating(
        apodId: String,
        context: OperationRequest,
        resultHandler: Handler<AsyncResult<OperationResponse>>
    ) = runBlocking<Unit> {
        withContext(Dispatchers.IO) {
            jdbc.rxQuerySingleWithParams(
                "SELECT APOD_ID, AVG(VALUE) AS VALUE FROM RATING WHERE APOD_ID=? GROUP BY APOD_ID",
                JsonArray().add(apodId)
            )
        }.map { Rating(it.getInteger(0), it.getInteger(1)) }
            .map { Future.succeededFuture(OperationResponse.completedWithJson(it.toJsonObject())) }
            .doOnError { logger.error(it) { "Error during Rating query" } }
            .switchIfEmpty(handleApodNotFound())
            .subscribe(resultHandler::handle) { handleFailure(resultHandler, it) }
    }

    /**
     * Add a rating for an apod
     */
    override fun putRating(
        apodId: String,
        context: OperationRequest,
        resultHandler: Handler<AsyncResult<OperationResponse>>
    ) = runBlocking<Unit> {
        withContext(Dispatchers.IO) {
            jdbc.rxQuerySingleWithParams("SELECT ID FROM APOD WHERE ID=?",
                json { array(apodId) }
            )
        }.map {
            it.getInteger(0)
        }.flatMap {
            runBlocking {
                withContext(Dispatchers.IO) {
                    jdbc.rxUpdateWithParams(
                        "INSERT INTO RATING (VALUE, APOD_ID) VALUES ?, ?",
                        json { array(asRatingRequest(context.params.getJsonObject("body")).rating, apodId) }
                    ).toMaybe()
                }
            }
        }
            .map { Future.succeededFuture(OperationResponse().setStatusCode(HttpStatus.SC_NO_CONTENT)) }
            .doOnError { logger.error(it) { "Error during Rating query" } }
            .switchIfEmpty(handleApodNotFound())
            .subscribe(resultHandler::handle) { handleFailure(resultHandler, it) }

    }

    private fun handleApodNotFound(): Maybe<Future<OperationResponse>>? {
        return Maybe.just(
            Future.succeededFuture(
                OperationResponse().setStatusCode(HttpStatus.SC_NOT_FOUND).setStatusMessage(
                    "This apod does not exist."
                )
            )
        )
    }

    private fun handleFailure(
        resultHandler: Handler<AsyncResult<OperationResponse>>,
        it: Throwable
    ) {
        resultHandler.handle(
            Future.failedFuture(
                ServiceException(
                    HttpStatus.SC_INTERNAL_SERVER_ERROR,
                    it.localizedMessage
                )
            )
        )
    }
}

