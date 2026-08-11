package ch.trailr.solver

data class Graph(
    val nodes: Map<Int, Node>,
    val edges: List<Edge>,
    val adjacency: Map<Int, List<Int>>,
)
