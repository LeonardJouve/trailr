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

private data class TourArc(
    val edgeIndex: Int,
    val uuid: String,
    val from: Int,
    val to: Int
)

class TrailService : TrailSolverGrpcKt.TrailSolverCoroutineImplBase() {
    companion object {
        init {
            Loader.loadNativeLibraries()
        }
    }

    private fun makeFlow(solver: MPSolver, graph: Graph, edgeForwardVars: List<MPVariable>, edgeBackwardVars: List<MPVariable>, nodeVars: Map<Int, MPVariable>, originId: Int) {
        val n = graph.nodes.size.toDouble()

        val flowVars = graph.edges.map { edge ->
            FlowVars(
                solver.makeNumVar(0.0, n, "flow_${edge.fromNode}_${edge.toNode}"),
                solver.makeNumVar(0.0, n, "flow_${edge.toNode}_${edge.fromNode}")
            )
        }

        for (i in flowVars.indices) {
            val forwardConstraint = solver.makeConstraint(Double.NEGATIVE_INFINITY, 0.0, "flow_forward_capacity_$i")
            forwardConstraint.setCoefficient(flowVars[i].forward, 1.0)
            forwardConstraint.setCoefficient(edgeForwardVars[i], -n)

            val backwardConstraint = solver.makeConstraint(Double.NEGATIVE_INFINITY, 0.0, "flow_backward_capacity_$i")
            backwardConstraint.setCoefficient(flowVars[i].backward, 1.0)
            backwardConstraint.setCoefficient(edgeBackwardVars[i], -n)
        }

        for (nodeId in graph.nodes.keys) {
            if (nodeId == originId) continue

            val constraint = solver.makeConstraint(0.0, 0.0, "connectivity_flow_$nodeId")

            for (edgeIndex in graph.adjacency[nodeId].orEmpty()) {
                val edge = graph.edges[edgeIndex]

                if (edge.fromNode == nodeId) {
                    constraint.setCoefficient(flowVars[edgeIndex].forward, -1.0)
                    constraint.setCoefficient(flowVars[edgeIndex].backward, 1.0)
                } else {
                    constraint.setCoefficient(flowVars[edgeIndex].forward, 1.0)
                    constraint.setCoefficient(flowVars[edgeIndex].backward, -1.0)
                }
            }

            constraint.setCoefficient(nodeVars[nodeId], -1.0)
        }

        val originFlowConstraint = solver.makeConstraint(0.0, 0.0, "connectivity_origin")

        for (edgeIndex in graph.adjacency[originId].orEmpty()) {
            val edge = graph.edges[edgeIndex]

            if (edge.fromNode == originId) {
                originFlowConstraint.setCoefficient(flowVars[edgeIndex].forward, 1.0)
                originFlowConstraint.setCoefficient(flowVars[edgeIndex].backward, -1.0)
            } else {
                originFlowConstraint.setCoefficient(flowVars[edgeIndex].forward, -1.0)
                originFlowConstraint.setCoefficient(flowVars[edgeIndex].backward, 1.0)
            }
        }

        for (nodeId in graph.nodes.keys) {
            if (nodeId == originId) continue

            originFlowConstraint.setCoefficient(nodeVars[nodeId], -1.0)
        }
    }

    private fun makeEdgeVariables(solver: MPSolver, graph: Graph): Pair<List<MPVariable>, List<MPVariable>> {
        val edgeForwardVars = List(graph.edges.size) { i ->
            solver.makeBoolVar("edge_forward_$i")
        }
        val edgeBackwardVars = List(graph.edges.size) { i ->
            solver.makeBoolVar("edge_backward_$i")
        }

        for (nodeId in graph.nodes.keys) {
            val constraint = solver.makeConstraint(0.0, 0.0, "flow_balance_$nodeId")

            for (edgeIndex in graph.adjacency[nodeId].orEmpty()) {
                val edge = graph.edges[edgeIndex]

                if (edge.fromNode == nodeId) {
                    constraint.setCoefficient(edgeForwardVars[edgeIndex], 1.0)
                    constraint.setCoefficient(edgeBackwardVars[edgeIndex], -1.0)
                } else {
                    constraint.setCoefficient(edgeForwardVars[edgeIndex], -1.0)
                    constraint.setCoefficient(edgeBackwardVars[edgeIndex], 1.0)
                }
            }
        }

        return Pair(edgeForwardVars, edgeBackwardVars)
    }

    private fun makeNodeVariables(solver: MPSolver, graph: Graph, edgeForwardVars: List<MPVariable>, edgeBackwardVars: List<MPVariable>): Map<Int, MPVariable> {
        val nodeVars = graph.nodes.keys.associateWith { nodeId ->
            solver.makeBoolVar("node_$nodeId")
        }

        for (nodeId in graph.nodes.keys) {
            val edges = graph.adjacency[nodeId].orEmpty()

            val hasEdge = solver.makeConstraint(0.0, Double.POSITIVE_INFINITY, "node_has_edge_$nodeId")
            for (edgeIndex in edges) {
                hasEdge.setCoefficient(edgeForwardVars[edgeIndex], 1.0)
                hasEdge.setCoefficient(edgeBackwardVars[edgeIndex], 1.0)
            }
            hasEdge.setCoefficient(nodeVars[nodeId], -1.0)

            for (edgeIndex in edges) {
                val forwardConstraint = solver.makeConstraint(Double.NEGATIVE_INFINITY, 0.0, "forward_node_${nodeId}_${edgeIndex}")
                forwardConstraint.setCoefficient(edgeForwardVars[edgeIndex], 1.0)
                forwardConstraint.setCoefficient(nodeVars[nodeId], -1.0)

                val backwardConstraint = solver.makeConstraint(Double.NEGATIVE_INFINITY, 0.0, "backward_node_${nodeId}_${edgeIndex}")
                backwardConstraint.setCoefficient(edgeBackwardVars[edgeIndex], 1.0)
                backwardConstraint.setCoefficient(nodeVars[nodeId], -1.0)
            }
        }

        return nodeVars
    }

    private fun makeTourConstraint(solver: MPSolver, graph: Graph, edgeForwardVars: List<MPVariable>, edgeBackwardVars: List<MPVariable>) {
        val constraint = solver.makeConstraint(1.0, Double.POSITIVE_INFINITY, "tour_has_edges")

        for (i in graph.edges.indices) {
            constraint.setCoefficient(edgeForwardVars[i], 1.0)
            constraint.setCoefficient(edgeBackwardVars[i], 1.0)
        }
    }

    private fun makeRepeatPenalties(solver: MPSolver, edgeForwardVars: List<MPVariable>, edgeBackwardVars: List<MPVariable>): List<MPVariable> {
        val repeatPenalties = List(edgeForwardVars.size) { i ->
            solver.makeBoolVar("repeat_penalty_$i")
        }

        for (i in edgeForwardVars.indices) {
            val constraint = solver.makeConstraint(-1.0, Double.POSITIVE_INFINITY, "repeat_$i")

            constraint.setCoefficient(repeatPenalties[i], 1.0)

            constraint.setCoefficient(edgeForwardVars[i], -1.0)

            constraint.setCoefficient(edgeBackwardVars[i], -1.0)
        }

        return repeatPenalties
    }

    private fun makePenalty(solver: MPSolver, name: String, target: Double, elements: List<Pair<Double, MPVariable>>): Pair<MPVariable, MPVariable> {
        val total = solver.makeNumVar(0.0, Double.POSITIVE_INFINITY, "total_$name")
        val penalty = solver.makeNumVar(0.0, Double.POSITIVE_INFINITY, "penalty_$name")

        val upperConstraint = solver.makeConstraint(Double.NEGATIVE_INFINITY, target, "penalty_upper_$name")
        upperConstraint.setCoefficient(total, 1.0)
        upperConstraint.setCoefficient(penalty, -1.0)

        val lowerConstraint = solver.makeConstraint(Double.NEGATIVE_INFINITY, -target, "penalty_lower_$name")
        lowerConstraint.setCoefficient(total, -1.0)
        lowerConstraint.setCoefficient(penalty, -1.0)

        val totalConstraint = solver.makeConstraint(0.0, 0.0, "define_total_$name")
        totalConstraint.setCoefficient(total, -1.0)
        for (i in elements.indices) {
            val element = elements[i]
            totalConstraint.setCoefficient(element.second, element.first)
        }

        return Pair(total, penalty)
    }

    private fun elevationGain(edge: Edge, reverse: Boolean): Double {
        var elevation = 0.0
        val coordinates = if (reverse) {
            edge.coordinatesList.asReversed()
        } else {
            edge.coordinatesList
        }

        var previous: Coordinate? = null
        for (coordinate in coordinates) {
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

        return Graph(nodes, edges, adjacency)
    }

    // Hierholzer's algorithm
    private fun reconstructTour(graph: Graph, edgeForwardVars: List<MPVariable>, edgeBackwardVars: List<MPVariable>, originId: Int): Pair<List<String>, List<Int>> {
        val arcs = mutableListOf<TourArc>()

        for (i in graph.edges.indices) {
            val edge = graph.edges[i]

            if (edgeForwardVars[i].solutionValue() > 0.5) {
                arcs += TourArc(i, edge.uuid, edge.fromNode, edge.toNode)
            }

            if (edgeBackwardVars[i].solutionValue() > 0.5) {
                arcs += TourArc(i, edge.uuid, edge.toNode, edge.fromNode)
            }
        }

        if (arcs.isEmpty()) error("Tour contains no edges")

        val outgoing = arcs
            .groupBy { it.from }
            .mapValues { (_, arcs) -> ArrayDeque(arcs) }
            .toMutableMap()

        val nodeStack = ArrayDeque<Int>()
        val arcStack = ArrayDeque<TourArc>()

        val circuitNodes = mutableListOf<Int>()
        val circuitArcs = mutableListOf<TourArc>()

        nodeStack.addLast(originId)

        while (nodeStack.isNotEmpty()) {
            val current = nodeStack.last()

            val available = outgoing[current]

            if (available != null && available.isNotEmpty()) {
                val arc = available.removeFirst()

                arcStack.addLast(arc)
                nodeStack.addLast(arc.to)
            } else {
                nodeStack.removeLast()

                circuitNodes.add(current)

                if (arcStack.isNotEmpty()) {
                    circuitArcs.add(arcStack.removeLast())
                }
            }
        }

        circuitNodes.reverse()
        circuitArcs.reverse()

        check(circuitArcs.size == arcs.size) {
            "Could not reconstruct all selected edges: ${circuitArcs.size}/${arcs.size} reconstructed"
        }
        check(circuitNodes.first() == originId) {
            "Tour does not start at origin"
        }
        check(circuitNodes.last() == originId) {
            "Tour does not return to origin"
        }

        return Pair(circuitArcs.map { it.uuid }, circuitNodes)
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

        val (edgeForwardVars, edgeBackwardVars) = makeEdgeVariables(solver, graph)
        val nodeVars = makeNodeVariables(solver, graph, edgeForwardVars, edgeBackwardVars)
        makeFlow(solver, graph, edgeForwardVars, edgeBackwardVars, nodeVars, request.originId)
        makeTourConstraint(solver, graph, edgeForwardVars, edgeBackwardVars)

        val repeatPenalties = makeRepeatPenalties(solver, edgeForwardVars, edgeBackwardVars)
        val (lengthTotal, lengthPenalty) = makePenalty(
            solver,
            "length",
            request.targetLength,
            graph.edges.flatMapIndexed { i, edge ->
                listOf(
                    Pair(edge.length, edgeForwardVars[i]),
                    Pair(edge.length, edgeBackwardVars[i]),
                )
            }
        )
        val (elevationTotal, elevationPenalty) = makePenalty(
            solver,
            "elevation",
            request.targetElevation,
            graph.edges.flatMapIndexed { i, edge ->
                listOf(
                    Pair(elevationGain(edge, false), edgeForwardVars[i]),
                    Pair(elevationGain(edge, true), edgeBackwardVars[i]),
                )
            }
        )

        val objective = solver.objective()
        objective.setCoefficient(lengthPenalty, request.lengthPenaltyWeight)
        objective.setCoefficient(elevationPenalty, request.elevationPenaltyWeight)
        for (i in repeatPenalties.indices) {
            objective.setCoefficient(repeatPenalties[i], request.repeatPenaltyWeight * graph.edges[i].length)
        }
        objective.setMinimization()

        val t2 = System.nanoTime()
        println("Build time: ${(t2 - t1) / 1_000_000_000.0} s")

        val status = solver.solve()

        val t3 = System.nanoTime()
        println("Solver solved in ${(t3 - t2) / 1_000_000_000.0} s")

        println("Solution status: ${status.name}")
        if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
            return SolveTourResponse.newBuilder()
                .setFound(false)
                .build()
        }

        println("Objective: ${solver.objective().value()}")
        println("Length: ${lengthTotal.solutionValue()}")
        println("Length penalty: ${lengthPenalty.solutionValue() * request.lengthPenaltyWeight}")
        println("Elevation: ${elevationTotal.solutionValue()}")
        println("Elevation penalty: ${elevationPenalty.solutionValue() * request.elevationPenaltyWeight}")
        println("Repeat penalty: ${repeatPenalties.indices.sumOf { i -> repeatPenalties[i].solutionValue() * request.repeatPenaltyWeight * graph.edges[i].length }}")
        println("Selected edges:")
        graph.edges.indices.forEach { i ->
            val edge = graph.edges[i]
            if (edgeForwardVars[i].solutionValue() > 0.5) {
                println("${edge.uuid}: ${edge.fromNode} -> ${edge.toNode}")
            }
            if (edgeBackwardVars[i].solutionValue() > 0.5) {
                println("${edge.uuid}: ${edge.toNode} -> ${edge.fromNode}")
            }
        }

        val (orderedEdges, orderedNodes) = reconstructTour(graph, edgeForwardVars, edgeBackwardVars, request.originId)

        val builder = SolveTourResponse.newBuilder()
            .setLength(lengthTotal.solutionValue())
            .setElevation(elevationTotal.solutionValue())
            .addAllEdgeIds(orderedEdges)
            .addAllNodeIds(orderedNodes)
            .setFound(true)

        return builder.build()
    }
}