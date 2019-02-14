package apodrating.webapi

import apodrating.model.Rating
import apodrating.model.asRatingRequest
import apodrating.model.toJsonObject
import io.reactivex.Maybe
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.json.JsonArray
import io.vertx.ext.jdbc.JDBCClient
import io.vertx.ext.web.api.OperationRequest
import io.vertx.ext.web.api.OperationResponse
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.serviceproxy.ServiceException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import mu.KLogging
import org.apache.http.HttpStatus

class RatingServiceImpl(val client: JDBCClient) : RatingService {

    companion object : KLogging()

    private val jdbc = io.vertx.reactivex.ext.jdbc.JDBCClient(client)

    override fun getRating(
        apodId: String,
        context: OperationRequest,
        resultHandler: Handler<AsyncResult<OperationResponse>>
    ) = runBlocking { getRatingSuspend(apodId, resultHandler) }

    override fun putRating(
        apodId: String,
        context: OperationRequest,
        resultHandler: Handler<AsyncResult<OperationResponse>>
    ) {
        runBlocking { putRatingSuspending(apodId, context, resultHandler) }
    }

    private suspend fun getRatingSuspend(apodId: String, resultHandler: Handler<AsyncResult<OperationResponse>>) =
        coroutineScope<Unit> {
            jdbc.rxQuerySingleWithParams(
                "SELECT APOD_ID, AVG(VALUE) AS VALUE FROM RATING WHERE APOD_ID=? GROUP BY APOD_ID",
                JsonArray().add(apodId)
            ).map { Rating(it.getInteger(0), it.getInteger(1)) }
                .map { Future.succeededFuture(OperationResponse.completedWithJson(it.toJsonObject())) }
                .switchIfEmpty(handleApodNotFound())
                .subscribe(resultHandler::handle) { handleFailure(resultHandler, it) }
        }

    private suspend fun putRatingSuspending(
        apodId: String,
        context: OperationRequest,
        resultHandler: Handler<AsyncResult<OperationResponse>>
    ) =
        coroutineScope<Unit> {
            jdbc.rxQuerySingleWithParams("SELECT ID FROM APOD WHERE ID=?",
                json { array(apodId) }
            ).map {
                it.getInteger(0)
            }.flatMap {
                jdbc.rxUpdateWithParams(
                    "INSERT INTO RATING (VALUE, APOD_ID) VALUES ?, ?",
                    json { array(asRatingRequest(context.params.getJsonObject("body")).rating, apodId) }
                ).toMaybe()
            }.map {
                Future.succeededFuture(
                    OperationResponse().setStatusCode(HttpStatus.SC_NO_CONTENT)
                )
            }.switchIfEmpty(handleApodNotFound())
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

