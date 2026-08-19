package ch.trailr.solver

import com.google.ortools.Loader
import com.google.ortools.linearsolver.MPSolver
import com.google.ortools.linearsolver.MPVariable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.collections.orEmpty
import kotlin.math.exp

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

private data class Chain(
    val edgeIds: List<String>,
    val nodeIds: List<Int>,
    val coordinates: List<Coordinate>
)

private data class ContractedEdge(
    val id: String,
    val fromNode: Int,
    val toNode: Int,
    val length: Double,
    val forwardChain: Chain,
    val backwardChain: Chain
)

private fun Chain.reversed(): Chain = Chain(
    edgeIds = edgeIds.asReversed(),
    nodeIds = nodeIds.asReversed(),
    coordinates = coordinates.asReversed()
)

private fun ContractedEdge.otherEndpoint(node: Int): Int =
    if (fromNode == node) toNode else fromNode

class TrailService : TrailSolverGrpcKt.TrailSolverCoroutineImplBase() {
    companion object {
        init {
            Loader.loadNativeLibraries()
        }
    }

    private val solveMutex = Mutex()

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

    private fun makeExponentialLengthPenalty(solver: MPSolver, lengthPenalty: MPVariable, target: Double, maxPossibleLength: Double, weight: Double) {
        if (weight == 0.0 || target <= 0.0) return

        val maxDeviation = maxOf(target, maxPossibleLength - target)
        val segments = 20
        val width = maxDeviation / segments
        if (width <= 0.0) return

        val scale = target / 10.0
        val maxExp = 20.0
        val objective = solver.objective()
        val segmentVars = List(segments) { i ->
            solver.makeNumVar(0.0, 1.0, "length_penalty_segment_$i")
        }

        val totalConstraint = solver.makeConstraint(0.0, 0.0, "length_penalty_segments_total")
        totalConstraint.setCoefficient(lengthPenalty, -1.0)
        segmentVars.forEach { totalConstraint.setCoefficient(it, width) }

        for (i in 0 until segments - 1) {
            val orderConstraint = solver.makeConstraint(Double.NEGATIVE_INFINITY, 0.0, "length_penalty_segment_order_$i")
            orderConstraint.setCoefficient(segmentVars[i], -1.0)
            orderConstraint.setCoefficient(segmentVars[i + 1], 1.0)
        }

        for (i in segmentVars.indices) {
            val low = i * width
            val high = (i + 1) * width
            val cappedHigh = minOf(high / scale, maxExp)
            val cappedLow = minOf(low / scale, maxExp)
            val segmentCost = weight * (exp(cappedHigh) - exp(cappedLow))
            objective.setCoefficient(segmentVars[i], segmentCost)
        }
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

    private fun contractGraph(graph: Graph, originId: Int): Pair<Graph, List<ContractedEdge>> {
        var nextId = 0
        fun newId() = "merged-${nextId++}"

        val activeEdges = mutableListOf<ContractedEdge>()
        graph.edges.forEach { edge ->
            val forwardChain = Chain(
                edgeIds = listOf(edge.uuid),
                nodeIds = listOf(edge.fromNode, edge.toNode),
                coordinates = edge.coordinatesList
            )
            activeEdges.add(
                ContractedEdge(
                    id = edge.uuid,
                    fromNode = edge.fromNode,
                    toNode = edge.toNode,
                    length = edge.length,
                    forwardChain = forwardChain,
                    backwardChain = forwardChain.reversed()
                )
            )
        }

        val adjacency = mutableMapOf<Int, MutableList<ContractedEdge>>()
        graph.nodes.keys.forEach { adjacency[it] = mutableListOf() }
        activeEdges.forEach { edge ->
            adjacency.getOrPut(edge.fromNode) { mutableListOf() }.add(edge)
            adjacency.getOrPut(edge.toNode) { mutableListOf() }.add(edge)
        }

        val queue = ArrayDeque<Int>()
        adjacency.entries.forEach { (node, edges) ->
            if (node != originId && edges.size == 2) queue.add(node)
        }

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val incident = adjacency[node] ?: continue
            if (incident.size != 2) continue

            val e1 = incident[0]
            val e2 = incident[1]

            val a = e1.otherEndpoint(node)
            val b = e2.otherEndpoint(node)
            if (a == b) continue

            val chain1 = if (e1.fromNode == a) e1.forwardChain else e1.backwardChain
            val chain2 = if (e2.fromNode == node) e2.forwardChain else e2.backwardChain

            val newForwardChain = Chain(
                edgeIds = chain1.edgeIds + chain2.edgeIds,
                nodeIds = chain1.nodeIds + chain2.nodeIds.drop(1),
                coordinates = chain1.coordinates + chain2.coordinates.drop(1)
            )

            val newEdge = ContractedEdge(
                id = newId(),
                fromNode = a,
                toNode = b,
                length = e1.length + e2.length,
                forwardChain = newForwardChain,
                backwardChain = newForwardChain.reversed()
            )

            adjacency[a]?.remove(e1)
            adjacency[b]?.remove(e2)
            adjacency[node]?.clear()
            activeEdges.remove(e1)
            activeEdges.remove(e2)

            adjacency.getOrPut(a) { mutableListOf() }.add(newEdge)
            adjacency.getOrPut(b) { mutableListOf() }.add(newEdge)
            activeEdges.add(newEdge)

            if (a != originId && adjacency[a]?.size == 2) queue.add(a)
            if (b != originId && adjacency[b]?.size == 2) queue.add(b)
        }

        val remainingNodes = graph.nodes.filter { (id, _) ->
            id == originId || adjacency[id]?.isNotEmpty() == true
        }
        val contractedEdges = activeEdges.map { edge ->
            Edge.newBuilder()
                .setUuid(edge.id)
                .setFromNode(edge.fromNode)
                .setToNode(edge.toNode)
                .setLength(edge.length)
                .addAllCoordinates(edge.forwardChain.coordinates)
                .build()
        }

        val contractedAdjacency = mutableMapOf<Int, MutableList<Int>>()
        remainingNodes.keys.forEach { contractedAdjacency[it] = mutableListOf() }
        contractedEdges.forEachIndexed { index, edge ->
            contractedAdjacency.getOrPut(edge.fromNode) { mutableListOf() }.add(index)
            contractedAdjacency.getOrPut(edge.toNode) { mutableListOf() }.add(index)
        }

        return Pair(Graph(remainingNodes, contractedEdges, contractedAdjacency), activeEdges)
    }

    private fun expandTour(
        contractedEdgeIds: List<String>,
        contractedNodeIds: List<Int>,
        activeEdges: List<ContractedEdge>
    ): Pair<List<String>, List<Int>> {
        val edgeMap = activeEdges.associateBy { it.id }
        val originalEdges = mutableListOf<String>()
        val originalNodes = mutableListOf<Int>()

        for (i in contractedEdgeIds.indices) {
            val edge = edgeMap[contractedEdgeIds[i]] ?: error("Unknown contracted edge ${contractedEdgeIds[i]}")
            val fromNode = contractedNodeIds[i]
            val chain = if (edge.fromNode == fromNode) edge.forwardChain else edge.backwardChain

            if (originalNodes.isEmpty()) {
                originalNodes.add(chain.nodeIds.first())
            }
            originalEdges.addAll(chain.edgeIds)
            originalNodes.addAll(chain.nodeIds.drop(1))
        }

        return Pair(originalEdges, originalNodes)
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

    override suspend fun solveTour(request: SolveTourRequest): SolveTourResponse = solveMutex.withLock {
        val graph = buildGraph(request)
        val (contractedGraph, activeEdges) = contractGraph(graph, request.originId)

        val solver = MPSolver.createSolver("SCIP") ?: return@withLock SolveTourResponse
            .newBuilder()
            .setFound(false)
            .build()

        if (request.timeLimitSeconds > 0.0) {
            solver.setTimeLimit((request.timeLimitSeconds * 1000).toLong())
        }

        val t1 = System.nanoTime()

        val (edgeForwardVars, edgeBackwardVars) = makeEdgeVariables(solver, contractedGraph)
        val nodeVars = makeNodeVariables(solver, contractedGraph, edgeForwardVars, edgeBackwardVars)
        makeFlow(solver, contractedGraph, edgeForwardVars, edgeBackwardVars, nodeVars, request.originId)
        makeTourConstraint(solver, contractedGraph, edgeForwardVars, edgeBackwardVars)

        val repeatPenalties = makeRepeatPenalties(solver, edgeForwardVars, edgeBackwardVars)
        val (lengthTotal, lengthPenalty) = makePenalty(
            solver,
            "length",
            request.targetLength,
            contractedGraph.edges.flatMapIndexed { i, edge ->
                listOf(
                    Pair(edge.length, edgeForwardVars[i]),
                    Pair(edge.length, edgeBackwardVars[i]),
                )
            }
        )
        val maxPossibleLength = contractedGraph.edges.sumOf { it.length } * 2.0
        makeExponentialLengthPenalty(
            solver,
            lengthPenalty,
            request.targetLength,
            maxPossibleLength,
            request.exponentialLengthPenaltyWeight
        )

        val (elevationTotal, elevationPenalty) = makePenalty(
            solver,
            "elevation",
            request.targetElevation,
            contractedGraph.edges.flatMapIndexed { i, edge ->
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
            objective.setCoefficient(repeatPenalties[i], request.repeatPenaltyWeight * contractedGraph.edges[i].length)
        }
        objective.setMinimization()

        val t2 = System.nanoTime()
        println("Build time: ${(t2 - t1) / 1_000_000_000.0} s")

        val status = solver.solve()

        val t3 = System.nanoTime()
        println("Solver solved in ${(t3 - t2) / 1_000_000_000.0} s")

        println("Solution status: ${status.name}")
        if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
            return@withLock SolveTourResponse.newBuilder()
                .setFound(false)
                .build()
        }

        println("Objective: ${solver.objective().value()}")
        println("Length: ${lengthTotal.solutionValue()}")
        println("Length penalty: ${lengthPenalty.solutionValue() * request.lengthPenaltyWeight}")
        println("Elevation: ${elevationTotal.solutionValue()}")
        println("Elevation penalty: ${elevationPenalty.solutionValue() * request.elevationPenaltyWeight}")
        println("Repeat penalty: ${repeatPenalties.indices.sumOf { i -> repeatPenalties[i].solutionValue() * request.repeatPenaltyWeight * contractedGraph.edges[i].length }}")
        if (request.exponentialLengthPenaltyWeight != 0.0) {
            val scale = request.targetLength / 10.0
            val deviation = lengthPenalty.solutionValue()
            println("Exponential length penalty: ${request.exponentialLengthPenaltyWeight * (exp(minOf(deviation / scale, 20.0)) - 1.0)}")
        }
        println("Selected contracted edges:")
        contractedGraph.edges.indices.forEach { i ->
            val edge = contractedGraph.edges[i]
            if (edgeForwardVars[i].solutionValue() > 0.5) {
                println("${edge.uuid}: ${edge.fromNode} -> ${edge.toNode}")
            }
            if (edgeBackwardVars[i].solutionValue() > 0.5) {
                println("${edge.uuid}: ${edge.toNode} -> ${edge.fromNode}")
            }
        }

        val (contractedEdgeIds, contractedNodeIds) = reconstructTour(contractedGraph, edgeForwardVars, edgeBackwardVars, request.originId)
        val (orderedEdges, orderedNodes) = expandTour(contractedEdgeIds, contractedNodeIds, activeEdges)

        val builder = SolveTourResponse.newBuilder()
            .setLength(lengthTotal.solutionValue())
            .setElevation(elevationTotal.solutionValue())
            .addAllEdgeIds(orderedEdges)
            .addAllNodeIds(orderedNodes)
            .setFound(true)

        return builder.build()
    }
}