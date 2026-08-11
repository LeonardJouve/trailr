package ch.trailr.solver

import com.google.ortools.Loader
import com.google.ortools.linearsolver.MPSolver
import com.google.ortools.linearsolver.MPVariable
import kotlin.collections.orEmpty
import kotlin.math.roundToInt

data class FlowVars(
    val forward: MPVariable,
    val backward: MPVariable,
)

class TrailService : TrailSolverGrpcKt.TrailSolverCoroutineImplBase() {
    companion object {
        init {
            Loader.loadNativeLibraries()
        }
    }

    private fun makeFlow(solver: MPSolver, graph: Graph, nodeVars: Map<Int, MPVariable>, edgeVars: List<MPVariable>, originId: Int) {
        val nodeCount = graph.nodes.size.toDouble()
        val flowVars = graph.edges.mapIndexed { i, edge ->
            FlowVars(
                solver.makeNumVar(0.0, nodeCount, "flow_${i}_${edge.fromNode}_${edge.toNode}"),
                solver.makeNumVar(0.0, nodeCount, "flow_${i}_${edge.toNode}_${edge.fromNode}")
            )
        }

        for (i in graph.edges.indices) {
            val constraint = solver.makeConstraint(Double.NEGATIVE_INFINITY, 0.0, "flow_capacity_$i")

            constraint.setCoefficient(flowVars[i].forward, 1.0)
            constraint.setCoefficient(flowVars[i].backward, 1.0)
            constraint.setCoefficient(edgeVars[i], -nodeCount)
        }

        for (nodeId in graph.nodes.keys) {
            if (nodeId == originId) continue

            val constraint = solver.makeConstraint(0.0, 0.0, "flow_balance_$nodeId")

            for (edgeIndex in graph.adjacency[nodeId].orEmpty()) {
                val flow = flowVars[edgeIndex]

                if (graph.edges[edgeIndex].fromNode == nodeId) {
                    constraint.setCoefficient(flow.forward, -1.0)
                    constraint.setCoefficient(flow.backward, 1.0)
                } else {
                    constraint.setCoefficient(flow.forward, 1.0)
                    constraint.setCoefficient(flow.backward, -1.0)
                }
            }

            constraint.setCoefficient(nodeVars[nodeId], -1.0)
        }

        val originConstraint = solver.makeConstraint(0.0, 0.0, "origin_flow")
        for (edgeIndex in graph.adjacency[originId].orEmpty()) {
            val flow = flowVars[edgeIndex]

            if (graph.edges[edgeIndex].fromNode == originId) {
                originConstraint.setCoefficient(flow.forward, 1.0)
                originConstraint.setCoefficient(flow.backward, -1.0)
            } else {
                originConstraint.setCoefficient(flow.forward, -1.0)
                originConstraint.setCoefficient(flow.backward, 1.0)
            }
        }

        for (nodeId in graph.nodes.keys) {
            if (nodeId == originId) continue
            originConstraint.setCoefficient(nodeVars[nodeId], -1.0)
        }
    }

    private fun makeGraphConstraint(solver: MPSolver, graph: Graph, originId: Int): Pair<Map<Int, MPVariable>, List<MPVariable>> {
        val edgeVars = List(graph.edges.size) { i ->
            solver.makeIntVar(0.0, 2.0, "edge_$i")
        }
        val nodeVars = graph.nodes.keys.associateWith { nodeId ->
            solver.makeBoolVar("node_$nodeId")
        }

        for (nodeId in graph.nodes.keys) {
            val constraint = solver.makeConstraint(0.0, 0.0, "degree_$nodeId")

            for (edgeIndex in graph.adjacency[nodeId].orEmpty()) {
                constraint.setCoefficient(edgeVars[edgeIndex], 1.0)
            }
            constraint.setCoefficient(nodeVars[nodeId], -2.0)
        }

        val originConstraint = solver.makeConstraint(1.0, 1.0, "origin")
        originConstraint.setCoefficient(nodeVars[originId], 1.0)

        return Pair(nodeVars, edgeVars)
    }

    private fun makeRepeatPenalties(solver: MPSolver, edgeVars: List<MPVariable>): List<MPVariable> {
        val repeatPenalties = List(edgeVars.size) { i ->
            solver.makeNumVar(0.0, Double.POSITIVE_INFINITY, "repeat_penalty_$i")
        }

        for (i in edgeVars.indices) {
            val constraint = solver.makeConstraint(-1.0, Double.POSITIVE_INFINITY, "repeat_$i")

            constraint.setCoefficient(repeatPenalties[i], 1.0)
            constraint.setCoefficient(edgeVars[i], -1.0)
        }

        return repeatPenalties
    }

    private fun <T> makePenalty(solver: MPSolver, name: String, target: Double, elements: List<Pair<T, MPVariable>>, getter: (element: T) -> Double): Pair<MPVariable, MPVariable> {
        val total = solver.makeNumVar(0.0, Double.POSITIVE_INFINITY, "total_$name")
        val penalty = solver.makeNumVar(0.0, Double.POSITIVE_INFINITY, "penalty_$name")

        val lengthUpper = solver.makeConstraint(Double.NEGATIVE_INFINITY, target, "penalty_upper_$name")
        lengthUpper.setCoefficient(total, 1.0)
        lengthUpper.setCoefficient(penalty, -1.0)

        val lengthLower = solver.makeConstraint(Double.NEGATIVE_INFINITY, -target, "penalty_lower_$name")
        lengthLower.setCoefficient(total, -1.0)
        lengthLower.setCoefficient(penalty, -1.0)

        val totalConstraint = solver.makeConstraint(0.0, 0.0, "define_total_$name")
        totalConstraint.setCoefficient(total, -1.0)
        for (i in elements.indices) {
            val element = elements[i]
            totalConstraint.setCoefficient(element.second, getter(element.first))
        }

        return Pair(total, penalty)
    }

    private fun elevationGain(edge: Edge): Double {
        var elevation = 0.0
        var previous: Coordinate? = null
        for (coordinate in edge.coordinatesList) {
            if (previous != null) {
                elevation += Math.max(0.0, coordinate.z - previous.z)
            }
            previous = coordinate
        }

        return elevation
    }

    private fun buildGraph(request: SolveTourRequest): Graph {
        val nodes = request.nodesList.associateBy { it.id }
        val edges = request.edgesList
        val adjacency = mutableMapOf<Int, MutableList<Int>>()

        edges.forEachIndexed { index, edge ->
            adjacency
                .getOrPut(edge.fromNode) { mutableListOf() }
                .add(index)
            adjacency
                .getOrPut(edge.toNode) { mutableListOf() }
                .add(index)
        }

        return Graph(
            nodes = nodes,
            edges = edges,
            adjacency = adjacency,
        )
    }

    private fun reconstructTour(graph: Graph, edgeVars: List<MPVariable>, originId: Int): Pair<List<String>, List<Int>> {
        val remaining = graph.edges.indices.associateWith { i ->
            edgeVars[i].solutionValue().roundToInt()
        }.toMutableMap()

        val orderedEdges = mutableListOf<String>()
        val orderedNodes = mutableListOf<Int>()

        var currentNode = originId
        orderedNodes.add(currentNode)

        while (currentNode != originId || orderedEdges.isEmpty()) {

            val edgeIndex = graph.adjacency[currentNode]
                .orEmpty()
                .firstOrNull { remaining[it]!! > 0 }
                ?: error("Could not continue tour from node $currentNode")

            val edge = graph.edges[edgeIndex]

            val nextNode = when (currentNode) {
                edge.fromNode -> edge.toNode
                edge.toNode -> edge.fromNode
                else -> error("Invalid adjacency")
            }

            orderedEdges.add(edge.uuid)
            orderedNodes.add(nextNode)

            remaining[edgeIndex] = remaining[edgeIndex]!! - 1

            currentNode = nextNode
        }

        check(currentNode == originId)
        check(remaining.values.all { it == 0 })

        return orderedEdges to orderedNodes
    }

    override suspend fun solveTour(request: SolveTourRequest): SolveTourResponse {
        val graph = buildGraph(request)

        val solver = MPSolver.createSolver("SCIP")
        if (solver == null) {
            return SolveTourResponse
                .newBuilder()
                .setFound(false)
                .build()
        }

        val t1 = System.nanoTime()

        val (nodeVars, edgeVars) = makeGraphConstraint(solver, graph, request.originId)

        makeFlow(solver, graph, nodeVars, edgeVars, request.originId)

        val repeatPenalties = makeRepeatPenalties(solver, edgeVars)

        val (lengthTotal, lengthPenalty) = makePenalty(
            solver,
            "length",
            request.targetLength,
            graph.edges.mapIndexed { i, edge -> edge to edgeVars[i] },
        ) { edge -> edge.length }

        val (elevationTotal, elevationPenalty) = makePenalty(
            solver,
            "elevation",
            request.targetElevation,
            graph.edges.mapIndexed { i, edge -> edge to edgeVars[i] },
        ) { edge -> elevationGain(edge) }

        val objective = solver.objective()

        objective.setCoefficient(lengthPenalty, 1.0)
        objective.setCoefficient(elevationPenalty, 10.0) // 10 weight
        for (repeatPenalty in repeatPenalties) {
            objective.setCoefficient(repeatPenalty, 5.0)
        }

        objective.setMinimization()

        val t2 = System.nanoTime()
        println("Build time: ${(t2 - t1) / 1_000_000_000.0} s")

        val status = solver.solve()

        val t3 = System.nanoTime()
        println("Solver solved in ${(t3 - t2) / 1_000_000_000.0} s")

        if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
            return SolveTourResponse.newBuilder()
                .setFound(false)
                .build()
        }

        println("Objective: ${solver.objective().value()}")
        println("Length: ${lengthTotal.solutionValue()}")
        println("Length penalty: ${lengthPenalty.solutionValue()}")
        println("Elevation: ${elevationTotal.solutionValue()}")
        println("Elevation penalty: ${elevationPenalty.solutionValue()}")
        println("Selected edges:")
        graph.edges.indices.forEach { i ->
            val count = edgeVars[i].solutionValue().roundToInt()
            if (count == 0) return@forEach

            val edge = graph.edges[i]
            println("${edge.uuid}: ${edge.fromNode} -> ${edge.toNode}, count=$count")
        }

        val (orderedEdges, orderedNodes) = reconstructTour(graph, edgeVars, request.originId)

        val builder = SolveTourResponse.newBuilder()
            .setLength(lengthTotal.solutionValue())
            .setElevation(elevationTotal.solutionValue())
            .addAllEdgeIds(orderedEdges)
            .addAllNodeIds(orderedNodes)
            .setFound(true)

        return builder.build()
    }
}