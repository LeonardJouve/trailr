package ch.trailr.solver

import io.grpc.ServerBuilder

fun main() {
    val trailService = TrailService()

    val server = ServerBuilder
        .forPort(3001)
        .addService(trailService)
        .build()

    Runtime.getRuntime().addShutdownHook(Thread {
        server.shutdown()
        server.awaitTermination()
    })

    server.start()
    server.awaitTermination()
}