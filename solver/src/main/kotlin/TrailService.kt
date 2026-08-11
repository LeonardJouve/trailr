package ch.trailr.solver

class TrailService : TrailSolverGrpcKt.TrailSolverCoroutineImplBase() {
    override suspend fun solveTour(request: SolveTourRequest): SolveTourResponse {
        // TODO: call CP-SAT solver

        return SolveTourResponse.newBuilder()
            .setLength(10.0)
            .build()
    }
}