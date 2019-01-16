package apodrating

import apodrating.model.deploymentOptionsFromEnv
import io.vertx.reactivex.core.Vertx
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Start our application.
 */
fun main(args: Array<String>) = with(Vertx.vertx()) {
    this.rxDeployVerticle(MainVerticle::class.java.canonicalName, deploymentOptionsFromEnv(this))
        .subscribe()
        .dispose()
}



